#!/usr/bin/env python3
"""Compare a trimmed desktop module, a java.base patch, and a private FreeMarker fork."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


base = load("minimal-jre")
beans = load("beans-runtime")


def source_hashes(directory):
    return {p.relative_to(directory).as_posix(): base.digest(p)
            for p in sorted(directory.rglob("*")) if p.suffix in (".c", ".h")}


def build(args):
    key = beans.platform_key()
    work = ROOT / "build/desktop-free" / key
    if work.exists():
        raise RuntimeError(f"Output exists: {work}; choose a fresh build tree")
    work.mkdir(parents=True)
    jdk = Path(args.jdk or os.environ["JAVA_HOME"]).resolve()
    tool = lambda name: beans.tool(jdk, name)
    env = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GDCC_SHARED_INCLUDE",
                 "GDCC_SHARED_C_COMPILER_CACHE", "ZIG_HOME", "CLASSPATH"):
        env.pop(name, None)
    metadata = json.loads((base.INPUT / "snapshot.json").read_text(encoding="utf-8"))
    for relative, expected in metadata["files"].items():
        if base.digest(base.INPUT / relative) != expected:
            raise RuntimeError("Snapshot changed: " + relative)
    native = work / "native" / key
    native.parent.mkdir()
    if os.name == "nt":
        with tarfile.open(base.INPUT / "windows-x86_64.tar.gz") as source:
            source.extractall(native.parent, filter="data")
    else:
        base.run(["bash", ROOT / "scripts/jre-native-bundle.sh", native],
                 log=work / "native.log", env=env)
    env["GDCC_TINYCC_HOME"] = str(native)
    base.run([sys.executable, ROOT / "scripts/beans-runtime.py", "--jdk", jdk,
              "--out", work / "beans", "--negative-test"], log=work / "beans-build.log", env=env)
    base.run([sys.executable, ROOT / "scripts/freemarker-no-desktop.py", "--jdk", jdk,
              "--out", work / "freemarker"], log=work / "freemarker-build.log", env=env)

    baseline = work / "baseline-jre"
    base.run([tool("jlink"), "--add-modules", base.PROFILES["full"], "--strip-debug",
              "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", baseline],
             log=work / "baseline-jlink.log", env=env)
    fork_image = work / "fork-jre"
    base.run([tool("jlink"), "--add-modules", "java.base,java.logging,java.xml,jdk.httpserver",
              "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
              "--output", fork_image], log=work / "fork-jlink.log", env=env)
    patched_image = work / "patch-jre"
    shutil.copytree(fork_image, patched_image, symlinks=True)
    patch_jar = patched_image / "beans-patch.jar"
    with zipfile.ZipFile(patch_jar, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted((work / "beans/classes").rglob("*.class")):
            if path.name != "module-info.class":
                info = zipfile.ZipInfo(path.relative_to(work / "beans/classes").as_posix(),
                                       date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(info, path.read_bytes())
    shutil.copytree(work / "beans/jre/legal/java.desktop", patched_image / "legal/java.desktop",
                    symlinks=True)
    classes = work / "probe-classes"
    original_fm = base.INPUT / "app/lib/freemarker-2.3.34.jar"
    original_jar = base.INPUT / "app/gdcc-0.0.2.jar"
    base.run([tool("javac"), "--release", "25", "-cp", os.pathsep.join([str(original_jar), str(original_fm)]),
              "-d", classes, ROOT / "probes/GdccRuntimeProbe.java",
              ROOT / "probes/FreeMarkerSemanticsProbe.java"], log=work / "javac.log", env=env)

    def patch_flags(image):
        return ["--patch-module", "java.base=" + str(image / "beans-patch.jar"),
                "--add-exports=java.base/java.beans=ALL-UNNAMED",
                "--add-exports=java.base/javax.swing.tree=ALL-UNNAMED"]

    result = {"platform": key, "snapshot_jar_sha256": base.digest(original_jar), "variants": {}}
    reference_sources = reference_semantics = None
    unmodified_gae = base.run([beans.tool(fork_image, "java"), "-cp",
                              os.pathsep.join([str(classes), str(work / "freemarker/download/freemarker-gae-2.3.34.jar")]),
                              "FreeMarkerSemanticsProbe"], log=work / "unmodified-gae.log", env=env, check=False)
    if unmodified_gae.returncode != 2 or "java/beans/IntrospectionException" not in unmodified_gae.stdout:
        raise RuntimeError("Expected unmodified GAE to retain its JavaBeans dependency")
    variants = [("baseline", baseline), ("beans-module", work / "beans/jre"),
                ("beans-patch", patched_image), ("freemarker-fork", fork_image)]
    for name, image in variants:
        env["GDCC_TINYCC_HOME"] = str(native)
        app = work / (name + "-app")
        shutil.copytree(base.INPUT / "app", app, ignore=shutil.ignore_patterns("native"))
        if name == "freemarker-fork":
            shutil.copy2(work / "freemarker/freemarker-gae-2.3.34-no-desktop.jar",
                         app / "lib/freemarker-2.3.34.jar")
        jar = app / original_jar.name
        java = beans.tool(image, "java")
        extra = patch_flags(image) if name == "beans-patch" else []
        modules = base.run([java, *extra, "--list-modules"], log=work / (name + "-modules.log"), env=env)
        module_names = {line.split("@")[0] for line in modules.stdout.splitlines()}
        expected_modules = {"java.base", "java.logging", "java.xml", "jdk.httpserver"}
        if name == "baseline":
            expected_modules |= {"java.desktop", "java.datatransfer", "java.prefs"}
        elif name == "beans-module":
            expected_modules.add("java.desktop")
        if module_names != expected_modules:
            raise RuntimeError(f"Unexpected modules for {name}: {module_names}")
        env["JAVA_HOME"] = str(image)
        semantics = base.run([java, *extra, *base.FLAGS, "-cp",
                              os.pathsep.join([str(classes), str(app / "lib/freemarker-2.3.34.jar")]),
                              "FreeMarkerSemanticsProbe"], log=work / (name + "-semantics.log"), env=env)
        semantic_lines = [line for line in semantics.stdout.splitlines() if line.startswith("FMSEM_")
                          and not line.startswith("FMSEM_JAVA_")]
        if reference_semantics is None:
            reference_semantics = semantic_lines
        elif semantic_lines != reference_semantics:
            raise RuntimeError("FreeMarker core semantics differ: " + name)
        env["GDCC_SHARED_C_COMPILER_CACHE"] = str(work / (name + "-backend-cache"))
        fixture = work / (name + "-fixture")
        shutil.copytree(ROOT / "fixture", fixture,
                        ignore=shutil.ignore_patterns("obj", "compiler-cache", "*.so", "*.dylib", "*.dll"))
        extension = ".dll" if os.name == "nt" else ".dylib" if key.startswith("macos") else ".so"
        library = work / ("probe" + extension)
        base.run([java, *extra, *base.FLAGS, "-cp", os.pathsep.join([str(classes), str(jar)]),
                  "GdccRuntimeProbe", fixture, library], log=work / (name + "-backend.log"), env=env)
        base.godot_test(work, library, args.godot, name + "-backend", env)
        env["GDCC_SHARED_C_COMPILER_CACHE"] = str(work / (name + "-cli-cache"))
        output = work / (name + "-cli") / "demo"
        base.run([java, *extra, *base.FLAGS, "-jar", jar, "--backend", "tcc", "-o", output,
                  ROOT / "fixture/source.gd"], log=work / (name + "-cli.log"), env=env)
        generated = source_hashes(output)
        if not generated or not any(path.endswith("entry.c") for path in generated):
            raise RuntimeError("Missing generated C project")
        if reference_sources is None:
            reference_sources = generated
        elif generated != reference_sources:
            raise RuntimeError("Generated C/header tree differs from baseline: " + name)
        libraries = list(output.glob("*" + extension))
        if len(libraries) != 1:
            raise RuntimeError("Missing unique CLI library")
        base.godot_test(work, libraries[0], args.godot, name + "-cli", env)
        if name == "baseline":
            result["baseline_image_bytes"] = sum(p.stat().st_size for p in image.rglob("*") if p.is_file())
            continue
        dist = work / ("gdcc-" + name + "-" + key)
        shutil.copytree(app, dist, ignore=shutil.ignore_patterns("native"))
        shutil.copytree(image, dist / "jre", symlinks=True)
        shutil.copytree(native, dist / "tinycc")
        shutil.copy2(base.INPUT / "GDCC-LICENSE", dist)
        if name == "beans-module":
            shutil.copy2(ROOT / "probes/beans-module/module-info.java", dist / "beans-module-info.java")
        if name == "freemarker-fork":
            for directory in ("patched-src", "patch", "license"):
                shutil.copytree(work / "freemarker" / directory, dist / "freemarker-audit" / directory)
            shutil.copy2(work / "freemarker/provenance.json", dist / "freemarker-audit/provenance.json")
        else:
            shutil.copy2(work / "beans/provenance.json", dist / "beans-provenance.json")
        sh_extra = (' --patch-module "java.base=$HERE/jre/beans-patch.jar"'
                    ' --add-exports=java.base/java.beans=ALL-UNNAMED'
                    ' --add-exports=java.base/javax.swing.tree=ALL-UNNAMED') if name == "beans-patch" else ""
        cmd_extra = (
            ' --patch-module "java.base=%~dp0jre\\beans-patch.jar"'
            ' --add-exports=java.base/java.beans=ALL-UNNAMED'
            ' --add-exports=java.base/javax.swing.tree=ALL-UNNAMED' if name == "beans-patch" else "")
        (dist / "gdcc.sh").write_text('#!/bin/sh\nset -eu\n'
            'HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n'
            'export GDCC_TINYCC_HOME="$HERE/tinycc"\n'
            f'exec "$HERE/jre/bin/java"{sh_extra} {" ".join(base.FLAGS)} -jar "$HERE/{jar.name}" "$@"\n',
            encoding="utf-8")
        (dist / "gdcc.sh").chmod(0o755)
        (dist / "gdcc.cmd").write_text('@echo off\r\nsetlocal\r\n'
            'set "GDCC_TINYCC_HOME=%~dp0tinycc"\r\n'
            f'"%~dp0jre\\bin\\java.exe"{cmd_extra} {" ".join(base.FLAGS)} -jar "%~dp0{jar.name}" %*\r\n',
            encoding="utf-8")
        packed = work / (dist.name + ".tar.gz")
        base.archive(dist, packed)
        relocated = work / (name + " relocated")
        relocated.mkdir()
        with tarfile.open(packed) as source:
            source.extractall(relocated, filter="tar")
        installed = relocated / dist.name
        launcher = installed / ("gdcc.cmd" if os.name == "nt" else "gdcc.sh")
        relocated_output = work / (name + "-relocated-cli/demo")
        command = [launcher, "--backend", "tcc", "-o", relocated_output, ROOT / "fixture/source.gd"]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", "call", *command]
        base.run(command, log=work / (name + "-relocated.log"), env=env)
        libs = list(relocated_output.glob("*" + extension))
        if len(libs) != 1:
            raise RuntimeError("Relocated CLI did not produce a native library")
        base.godot_test(work, libs[0], args.godot, name + "-relocated", env)
        # Use the same runtime flags for server mode, with no global flag mutation.
        rpc_work = work / (name + "-rpc")
        rpc_work.mkdir()
        rpc_extra = patch_flags(installed / "jre") if name == "beans-patch" else []
        env["GDCC_TINYCC_HOME"] = str(installed / "tinycc")
        base.rpc_test(beans.tool(installed / "jre", "java"), installed / jar.name, rpc_work, env,
                      extra_flags=rpc_extra)
        image_archive = work / ("jre-" + name + "-" + key + ".tar.gz")
        base.archive(image, image_archive)
        result["variants"][name] = {"modules": sorted(module_names), "generated_files": len(generated),
            "generated_sources_equal": True, "semantics_equal": True, "backend_godot": "pass",
            "cli_godot": "pass", "relocated_godot_rpc": "pass",
            "image_bytes": sum(p.stat().st_size for p in image.rglob("*") if p.is_file()),
            "image_archive_bytes": image_archive.stat().st_size, "image_sha256": base.digest(image_archive),
            "distribution_bytes": packed.stat().st_size, "distribution_sha256": base.digest(packed)}

    # Exhibit, rather than hide, the fork's custom BeanInfo incompatibility on a full JDK.
    for name, fm in (("reference", original_fm), ("fork", work / "freemarker/freemarker-gae-2.3.34-no-desktop.jar")):
        extended = base.run([tool("java"), "-cp", os.pathsep.join([str(classes), str(fm)]),
                             "FreeMarkerSemanticsProbe", "--mode=extended"],
                            log=work / (name + "-extended.log"), env=env)
        expected = "FMSEM_EXT_CUSTOM_SYNTHETIC=" + ("real" if name == "reference" else "ERR:")
        if expected not in extended.stdout:
            raise RuntimeError("Expected custom BeanInfo difference was not observed")
    result["fork_custom_beaninfo"] = "known incompatible; demonstrated"
    for name, image, fm in (("reference", baseline, original_fm),
                            ("fork", fork_image, work / "freemarker/freemarker-gae-2.3.34-no-desktop.jar")):
        setting = base.run([beans.tool(image, "java"), "-cp", os.pathsep.join([str(classes), str(fm)]),
                            "FreeMarkerSemanticsProbe", "--mode=setting"],
                           log=work / (name + "-setting.log"), env=env, check=False)
        if name == "reference" and (setting.returncode or "FMSEM_SETTING=PASS" not in setting.stdout):
            raise RuntimeError("Reference object-builder setting failed")
        if name == "fork" and (setting.returncode != 2 or "java/beans/" not in setting.stdout):
            raise RuntimeError("Expected unsupported named-setting desktop dependency")
    result["fork_named_object_builder_settings"] = "unsupported without desktop; demonstrated"
    (work / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("DESKTOP_FREE_SUITE_PASS::" + key, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk")
    parser.add_argument("--godot", required=True, type=lambda p: str(Path(p).resolve()))
    build(parser.parse_args())
