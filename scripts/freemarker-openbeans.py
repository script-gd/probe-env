#!/usr/bin/env python3
"""Build a java.desktop-free FreeMarker jar by swapping java.beans for OpenBeans.

Base: org.freemarker:freemarker-gae:2.3.34 (GAE edition already drops Swing from Template/TemplateElement). Four
sources (ext/beans/ClassIntrospector, ext/beans/BeansWrapper, ext/beans/MethodSorter,
core/_ObjectBuilderSettingEvaluator) are LF-normalized and their java.beans.* references rewritten to
com.googlecode.openbeans.*, then recompiled with --release 8; no reflection shim is used. Dependency:
me.champeau.openbeans:openbeans:1.0.2 (Maven Central, Apache-2.0) -- an archived Apache Harmony java.beans
redistribution, not maintained by Apache FreeMarker. The candidate manifest gains "Class-Path: openbeans-1.0.2.jar";
both jars deploy as siblings. Self-verifies core/named-setting semantics and manifest-only resolution. Never edits
runtime-input or gdcc; refuses an existing output directory. The gdcc CLI/TinyCC/Godot matrix is owned by the caller.
Usage: python3 scripts/freemarker-openbeans.py --jdk tools/jdk25 --out build/fm-openbeans
"""
from __future__ import annotations
import argparse
import importlib.util
import json
import os
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


nd = load("freemarker-no-desktop")  # pinned GAE inputs + zip/javac helpers
GAE_VERSION = nd.GAE_VERSION
OB_VERSION = "1.0.2"
OB_BASE = f"https://repo1.maven.org/maven2/me/champeau/openbeans/openbeans/{OB_VERSION}"
OB_SHA256 = {
    f"openbeans-{OB_VERSION}.jar": "027e2fe284ff319a6f78a5f07609ed9f7c76bfbac63da2abae133191a65afa83",
    f"openbeans-{OB_VERSION}-sources.jar": "da8cf31709c220a04bcade195063f69f0e7ebe04f95c8e5d67746f5d17127f7a",
    f"openbeans-{OB_VERSION}.pom": "f119a495a02ba01456de395d484bd29bdde06252f63dff02e9f80c4bb72ff04c",
}
SOURCES = (("freemarker/ext/beans/ClassIntrospector.java", 10),
           ("freemarker/ext/beans/BeansWrapper.java", 5),
           ("freemarker/ext/beans/MethodSorter.java", 1),
           ("freemarker/core/_ObjectBuilderSettingEvaluator.java", 2))
SOURCE_FILES = [rel for rel, _ in SOURCES]
CANDIDATE_NAME = f"freemarker-gae-{GAE_VERSION}-openbeans.jar"
MANIFEST_CLASSPATH = f"openbeans-{OB_VERSION}.jar"
LIMIT = "java.base,java.logging,java.xml"
PROBE = ROOT / "probes" / "FreeMarkerSemanticsProbe.java"
DIVERGENCE = ("java.beans is swapped for archived com.googlecode.openbeans (Apache Harmony); custom "
              "BeanInfo/PropertyEditor must use that package; MethodAppearanceDecision exposes its PropertyDescriptor; "
              "only the four sources are recompiled; verified only for the probe cases, not the upstream test suite")


def sh(cmd, log, env=None, check=True):
    result = nd.run([str(c) for c in cmd], env=env)
    Path(log).write_text(result.stdout + result.stderr, encoding="utf-8")
    if check and result.returncode:
        nd.die(f"command failed ({result.returncode}): {cmd[0]} (see {log})")
    return result


def patch_sources(src_root):
    counts = {}
    for rel, expected in SOURCES:
        path = src_root / rel
        raw = path.read_bytes()
        if b"\r\n" not in raw:
            nd.die(f"expected CRLF source (byte-exact pin): {rel}")
        text = raw.replace(b"\r\n", b"\n").decode("utf-8")
        found = text.count("java.beans.")
        if "\r" in text or found != expected:
            nd.die(f"line endings or java.beans. count wrong for {rel}: {found} != {expected}")
        text = text.replace("java.beans.", "com.googlecode.openbeans.")
        if "java.beans." in text or text.count("com.googlecode.openbeans.") != found:
            nd.die(f"replacement failed for {rel}")
        path.write_bytes(text.encode("utf-8"))
        counts[rel] = found
    return counts


def build(official_jar, src_root, classes_dir, out_jar, javac, ob_jar, log):
    classes_dir.mkdir(parents=True)
    cmd = [javac, "--release", "8", "-implicit:none", "-nowarn", "-encoding", "UTF-8", "-d", classes_dir,
           "-cp", os.pathsep.join([str(official_jar), str(ob_jar)]), "-sourcepath", str(src_root)]
    cmd += [str(src_root / rel) for rel in SOURCE_FILES]
    sh(cmd, log)
    nd.SOURCES_TO_COMPILE = SOURCE_FILES  # reuse nd.rewrite_jar's allowed-prefix + unchanged-entry checks
    replaced = nd.rewrite_jar(official_jar, classes_dir, out_jar)
    with zipfile.ZipFile(out_jar) as archive:
        entries = [(info, archive.read(info.filename)) for info in archive.infolist()]
    tmp = out_jar.with_suffix(".jar.part")
    with zipfile.ZipFile(tmp, "w") as target:
        for info, data in entries:
            if info.filename == "META-INF/MANIFEST.MF":
                if b"Class-Path" in data or not data.endswith(b"\r\n\r\n"):
                    nd.die("unexpected manifest shape; refusing to rewrite")
                data = data[:-2] + f"Class-Path: {MANIFEST_CLASSPATH}\r\n\r\n".encode("ascii")
            target.writestr(info, data)
    tmp.replace(out_jar)
    shutil.rmtree(classes_dir)
    return replaced


def verify(java, javac, official_jar, candidate_jar, work, env):
    work.mkdir(parents=True)
    classes = work / "probe-classes"
    classes.mkdir()
    sh([javac, "--release", "25", "-cp", official_jar, "-d", classes, PROBE], work / "probe-javac.log", env)
    ref = sh([java, "-cp", f"{classes}{os.pathsep}{official_jar}", "FreeMarkerSemanticsProbe", "--mode=core"],
             work / "reference-core.log", env)
    if "FMSEM_RESULT=PASS" not in ref.stdout or "FMSEM_JAVA_DESKTOP=present" not in ref.stdout:
        nd.die("reference GAE core probe did not pass on the full JDK")
    cand = sh([java, "--limit-modules", LIMIT, "-cp", f"{classes}{os.pathsep}{candidate_jar}",
               "FreeMarkerSemanticsProbe", "--mode=core"], work / "candidate-core.log", env)
    if "FMSEM_RESULT=PASS" not in cand.stdout:
        nd.die("candidate core probe failed")
    if "FMSEM_JAVA_BEANS=absent" not in cand.stdout or "FMSEM_JAVA_DESKTOP=absent" not in cand.stdout:
        nd.die("candidate probe JVM still exposes java.beans/java.desktop")
    semantic = lambda text: [ln for ln in text.splitlines()
                             if ln.startswith("FMSEM_") and not ln.startswith("FMSEM_JAVA_")]
    if semantic(ref.stdout) != semantic(cand.stdout):
        nd.die("candidate core semantics differ from reference GAE")
    setting = sh([java, "--limit-modules", LIMIT, "-cp", f"{classes}{os.pathsep}{candidate_jar}",
                  "FreeMarkerSemanticsProbe", "--mode=setting"], work / "candidate-setting.log", env)
    if "FMSEM_SETTING=PASS" not in setting.stdout:
        nd.die("named object-builder setting failed without java.desktop")
    isolated = work / "no-sibling"
    isolated.mkdir()
    shutil.copy2(candidate_jar, isolated / candidate_jar.name)
    missing = nd.run([str(java), "--limit-modules", LIMIT,
                      "-cp", f"{classes}{os.pathsep}{isolated / candidate_jar.name}",
                      "FreeMarkerSemanticsProbe", "--mode=core"], env=env)
    (work / "no-sibling.log").write_text(missing.stdout + missing.stderr, encoding="utf-8")
    if missing.returncode == 0 or "com/googlecode/openbeans" not in (missing.stdout + missing.stderr):
        nd.die("expected the dependency to be reachable only via the manifest Class-Path")
    return {"reference_core": "pass", "candidate_core": "pass", "semantics_equal": True,
            "candidate_java_beans": "absent", "named_setting": "pass", "manifest_classpath_resolution": "pass"}


def finish(out, src_root, inputs, counts, candidate_jar, ob_jar, src_hashes, javac, jdk_home, results):
    fm, ob = out / "audit" / "freemarker", out / "audit" / "openbeans"
    for target in (fm, ob):
        target.mkdir(parents=True)
    with zipfile.ZipFile(inputs[f"freemarker-gae-{GAE_VERSION}-sources.jar"]) as archive:
        for name in archive.namelist():
            if name in ("META-INF/LICENSE", "META-INF/NOTICE"):
                (fm / Path(name).name).write_bytes(archive.read(name))
    for rel in SOURCE_FILES:
        dest = fm / "patched-src" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_root / rel, dest)
    for name in OB_SHA256:
        shutil.copy2(inputs[name], ob / name)
    shutil.copy2(fm / "LICENSE", ob / "LICENSE")
    (ob / "LICENSE-PROVENANCE.txt").write_text(
        f"me.champeau.openbeans:openbeans:{OB_VERSION} (SPDX: Apache-2.0). No standalone LICENSE/NOTICE is bundled; "
        "the license is declared in the POM and every source header ('Licensed to the Apache Software Foundation ... "
        "Version 2.0'). The complete Apache license text is copied from the pinned FreeMarker source distribution.\n",
        encoding="utf-8")
    (ob / "NOTICE").write_text(
        "OpenBeans repackages java.beans from the retired Apache Harmony project as com.googlecode.openbeans for "
        "Android. Upstream is archived (code.google.com/archive/p/openbeans); this 1.0.2 coordinate is a rebuild, not "
        "an actively maintained Apache release, and is used here only as a java.beans drop-in for the candidate jar.\n",
        encoding="utf-8")
    version = nd.run([javac, "-version"])
    data = {
        "candidate": CANDIDATE_NAME,
        "base_artifact": {"group": "org.freemarker", "artifact": "freemarker-gae",
                          "version": GAE_VERSION, "maven_base_url": nd.ABS_URL},
        "openbeans_artifact": {"group": "me.champeau.openbeans", "artifact": "openbeans",
                               "version": OB_VERSION, "maven_base_url": OB_BASE,
                               "license": "Apache-2.0", "status": "archived Harmony redistribution"},
        "input_sha256": {name: nd.sha256_file(path) for name, path in sorted(inputs.items())},
        "patched_source_sha256": src_hashes, "java_beans_replacement_counts": counts,
        "manifest_classpath": MANIFEST_CLASSPATH,
        "outputs": {candidate_jar.name: nd.sha256_file(candidate_jar), ob_jar.name: nd.sha256_file(ob_jar)},
        "tools": {"jdk_home": str(jdk_home), "javac": (version.stdout + version.stderr).strip(),
                  "python": sys.version.split()[0], "javac_release": "8"},
        "verification": results, "known_divergence": DIVERGENCE,
    }
    (out / "provenance.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main(argv):
    parser = argparse.ArgumentParser(description="Build a java.desktop-free FreeMarker jar with OpenBeans.")
    parser.add_argument("--out", required=True, help="output directory (must not exist)")
    parser.add_argument("--jdk", default=None, help="JDK home (default: $JAVA_HOME)")
    args = parser.parse_args(argv)
    out = Path(args.out).resolve()
    if out.exists():
        nd.die(f"output path already exists: {out}")
    if not PROBE.exists():
        nd.die(f"missing probe: {PROBE}")
    javac, jdk_home = nd.find_javac(args.jdk)
    java = jdk_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.exists():
        nd.die(f"java not found under {jdk_home / 'bin'}")
    env = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "GDCC_SHARED_INCLUDE"):
        env.pop(name, None)
    out.mkdir(parents=True)
    inputs = nd.fetch_pinned(out / "download")
    for name, digest in OB_SHA256.items():
        dest = out / "download" / name
        nd.download(f"{OB_BASE}/{name}", dest)
        if nd.sha256_file(dest) != digest:
            nd.die(f"input SHA-256 mismatch for {name}")
        inputs[name] = dest
    official_jar = inputs[f"freemarker-gae-{GAE_VERSION}.jar"]
    ob_jar = out / MANIFEST_CLASSPATH
    shutil.copy2(inputs[MANIFEST_CLASSPATH], ob_jar)
    src_root = out / "src"
    src_root.mkdir()
    with zipfile.ZipFile(inputs[f"freemarker-gae-{GAE_VERSION}-sources.jar"]) as archive:
        archive.extractall(src_root)
    counts = patch_sources(src_root)
    src_hashes = {rel: nd.sha256_file(src_root / rel) for rel in SOURCE_FILES}
    candidate_jar = out / CANDIDATE_NAME
    replaced = build(official_jar, src_root, out / "classes", candidate_jar, javac, ob_jar, out / "javac.log")
    results = {"probe": verify(java, javac, official_jar, candidate_jar, out / "verify", env)}
    finish(out, src_root, inputs, counts, candidate_jar, ob_jar, src_hashes, javac, jdk_home, results)
    nd.log(f"replaced {replaced} jar entries; probe PASS; out: {out}")
    nd.log(f"{candidate_jar.name} = {nd.sha256_file(candidate_jar)}")
    nd.log(f"{MANIFEST_CLASSPATH} = {nd.sha256_file(ob_jar)}")
    print("FREEMARKER_OPENBEANS_PASS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
