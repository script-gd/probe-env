#!/usr/bin/env python3
"""Build a java.desktop-free FreeMarker candidate jar from the official freemarker-gae artifact.

Base: org.freemarker:freemarker-gae:2.3.34 (Maven Central, pinned by SHA-256). The GAE edition does not use Swing
from Template/TemplateElement, so only the java.beans introspection classes need replacing. The patch at
patches/freemarker-no-desktop.patch is applied to the official sources jar; only the patched sources are
recompiled with `javac --release 8`; every other class is retained from the official jar.

Build-only: no semantics probe or gdcc/Godot tests here; the caller owns reference/candidate comparisons and the full
matrix. Never modifies runtime-input or gdcc, and refuses to write into an existing output directory.
Requires git, a JDK 25 home via --jdk or $JAVA_HOME, and Python 3.9+.
Usage: python3 scripts/freemarker-no-desktop.py --out build/fm-no-desktop --jdk tools/jdk25
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GAE_VERSION = "2.3.34"
ABS_URL = f"https://repo1.maven.org/maven2/org/freemarker/freemarker-gae/{GAE_VERSION}"

# Pinned Maven Central inputs (SHA-256).
INPUT_SHA256 = {
    f"freemarker-gae-{GAE_VERSION}.jar": "fa091265a1aafc292de8ef06302fefb9a4d6426f1dd8d3d08817033f8de7637c",
    f"freemarker-gae-{GAE_VERSION}-sources.jar": "b6d478b80761c803a78f10864b39019dbf99d818de58dd8a3750a26332ddb47e",
    f"freemarker-gae-{GAE_VERSION}.pom": "0ef10ebfa3a1ddfec8b004b4e84dec01fdfb664b7605d9167ee3bc41f34a2510",
}
PATCH_REL = Path("patches") / "freemarker-no-desktop.patch"
PATCH_SHA256 = "86f89de0ca6b2d4cc19e780bd8315ef0357945733f7558e307790c7195dc60fb"

# Post-patch SHA-256, pinned so a silent/no-op or drifted apply is caught.
PATCHED_SOURCE_SHA256 = {
    "freemarker/ext/beans/ClassIntrospector.java": "c4faa1bec81f4db5f8ea25b0161a5e0823839df91a5350c33afece87e8cc2e48",
    "freemarker/ext/beans/BeansWrapper.java": "4566b04d0eaefe8d47d50ce16ee3534e4e53ea378382d7791aac5bb224719565",
    "freemarker/ext/beans/MethodSorter.java": "7d8f9007beab5a8e2df2fe8a69c0d19105a626b5aee0ee03585c615fdb6375a8",
    "freemarker/ext/beans/_BeanPropertyDescriptor.java": "75e8e5f2dfe7470cfa522be23d046f13bd8107a6849825f610d680acc3eba75b",
}
LF_NORMALIZE_FILES = ["freemarker/ext/beans/ClassIntrospector.java",
                      "freemarker/ext/beans/BeansWrapper.java",
                      "freemarker/ext/beans/MethodSorter.java"]
ADDED_SOURCE = "freemarker/ext/beans/_BeanPropertyDescriptor.java"
SOURCES_TO_COMPILE = [ADDED_SOURCE, "freemarker/ext/beans/MethodSorter.java",
                      "freemarker/ext/beans/ClassIntrospector.java", "freemarker/ext/beans/BeansWrapper.java"]
KNOWN_DIVERGENCES = [
    "custom java.beans.BeanInfo contributions are ignored",
    "ambiguous getX()/isX() selection approximated (isX preferred)",
    "indexed-vs-normal getter type-mismatch merge rule not reproduced",
    "treatDefaultMethodsAsBeanMembers == false not honored",
    "property/method enumeration order differs from java.beans.Introspector",
    "named object-builder settings still use java.beans (unsupported without desktop)",
    "debugger/JSP and other unexercised optional desktop paths are not removed",
]

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def log(msg: str) -> None:
    print(f"[freemarker-no-desktop] {msg}", flush=True)

def die(msg: str) -> None:
    raise SystemExit(f"error: {msg}")

def run(cmd: list[str], cwd: Path | None = None, env: dict | None = None) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, cwd=str(cwd) if cwd else None, env=env,
                            capture_output=True, text=True, encoding="utf-8")
    return result

def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    log(f"downloading {url}")
    with urllib.request.urlopen(url, timeout=120) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    tmp.replace(dest)

def fetch_pinned(download_dir: Path) -> dict[str, Path]:
    paths = {}
    for name, expected in INPUT_SHA256.items():
        dest = download_dir / name
        download(f"{ABS_URL}/{name}", dest)
        actual = sha256_file(dest)
        if actual != expected:
            die(f"input SHA-256 mismatch for {name}: {actual} != {expected}")
        paths[name] = dest
    return paths

def prepare_sources(sources_jar: Path, src_root: Path) -> None:
    src_root.mkdir(parents=True)
    with zipfile.ZipFile(sources_jar) as zf:
        zf.extractall(src_root)
    for rel in LF_NORMALIZE_FILES:  # sources jar is CRLF; the patch is LF
        p = src_root / rel
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n"))

def git_apply(patch_path: Path, src_root: Path) -> None:
    """Apply the patch with git in the isolated src root, and fail if it silently no-ops (repo interception)."""
    env = os.environ.copy()
    env["GIT_CEILING_DIRECTORIES"] = str(src_root.parent)
    env["GIT_OPTIONAL_LOCKS"] = "0"
    base = ["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", "-p1"]
    try:
        check = run(base + ["--check", str(patch_path)], cwd=src_root, env=env)
    except FileNotFoundError:
        die("git is required but was not found on PATH")
    if check.returncode != 0:
        die(f"git apply --check failed:\n{check.stderr.strip()}")
    applied = run(base + [str(patch_path)], cwd=src_root, env=env)
    if applied.returncode != 0:
        die(f"git apply failed:\n{applied.stderr.strip()}")
    if not (src_root / ADDED_SOURCE).exists():
        die("git apply was a no-op; the added source file is missing")
    log("applied patch with git apply")

def verify_patched_sources(src_root: Path) -> None:
    for rel, expected in PATCHED_SOURCE_SHA256.items():
        actual = sha256_file(src_root / rel)
        if actual != expected:
            die(f"patched source SHA-256 mismatch for {rel}: {actual} != {expected}")

def find_javac(jdk_arg: str | None) -> tuple[str, Path]:
    jdk = jdk_arg or os.environ.get("JAVA_HOME")
    if not jdk:
        die("no JDK given: pass --jdk <home> or set JAVA_HOME")
    jdk_home = Path(jdk).resolve()
    for name in ("javac.exe", "javac"):  # explicit Windows and POSIX tool paths
        cand = jdk_home / "bin" / name
        if cand.exists():
            return str(cand), jdk_home
    die(f"javac not found under {jdk_home / 'bin'} (no fallback to a system JVM)")

def compile_sources(javac: str, src_root: Path, classes_dir: Path, official_jar: Path) -> None:
    classes_dir.mkdir(parents=True)
    cmd = [javac, "--release", "8", "-implicit:none", "-nowarn", "-encoding", "UTF-8", "-d", str(classes_dir),
           "-cp", str(official_jar), "-sourcepath", str(src_root)]
    cmd += [str(src_root / rel) for rel in SOURCES_TO_COMPILE]
    log("compiling patched sources with javac --release 8")
    result = run(cmd)
    if result.returncode != 0:
        sys.stderr.write(result.stdout + result.stderr)
        die("javac failed")

def rewrite_jar(official_jar: Path, classes_dir: Path, out_jar: Path) -> int:
    compiled = {p.relative_to(classes_dir).as_posix(): p.read_bytes()
                for p in classes_dir.rglob("*.class")}
    if not compiled:
        die("no compiled classes produced")
    allowed = [str(Path(source).with_suffix("")).replace("\\", "/") for source in SOURCES_TO_COMPILE]
    if any(not any(name == stem + ".class" or name.startswith(stem + "$") for stem in allowed)
           for name in compiled):
        die("javac emitted classes outside the explicitly patched sources")
    tmp = out_jar.with_suffix(".jar.part")
    with zipfile.ZipFile(official_jar) as zin, zipfile.ZipFile(tmp, "w") as zout:
        seen = set()
        for it in zin.infolist():
            if it.filename in compiled:
                data = compiled[it.filename]
                seen.add(it.filename)
            else:
                data = zin.read(it.filename)
            zi = zipfile.ZipInfo(it.filename, it.date_time)
            zi.compress_type, zi.external_attr = it.compress_type, it.external_attr
            zi.internal_attr, zi.create_system, zi.flag_bits = it.internal_attr, it.create_system, it.flag_bits
            zout.writestr(zi, data)
        for name, data in compiled.items():
            if name not in seen:
                zi = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(zi, data)
    tmp.replace(out_jar)
    with zipfile.ZipFile(official_jar) as original, zipfile.ZipFile(out_jar) as patched:
        for name in original.namelist():
            if name not in compiled and original.read(name) != patched.read(name):
                die("unpatched jar entry changed: " + name)
    return len(seen)

def copy_audit_material(out: Path, src_root: Path, patch_path: Path, sources_jar: Path) -> None:
    (out / "patch").mkdir(parents=True)
    shutil.copy2(patch_path, out / "patch" / patch_path.name)
    for rel in PATCHED_SOURCE_SHA256:
        dest = out / "patched-src" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_root / rel, dest)
    (out / "license").mkdir()
    with zipfile.ZipFile(sources_jar) as zf:
        for n in zf.namelist():
            if n.startswith("META-INF/") and Path(n).name in ("LICENSE", "NOTICE"):
                (out / "license" / Path(n).name).write_bytes(zf.read(n))

def write_provenance(out: Path, inputs: dict[str, Path], patch_path: Path, jar_path: Path,
                     javac: str, jdk_home: Path) -> None:
    ver = run([javac, "-version"])
    provenance = {
        "candidate": f"freemarker-gae-{GAE_VERSION}-no-desktop",
        "base_artifact": {"group": "org.freemarker", "artifact": "freemarker-gae",
                          "version": GAE_VERSION, "maven_base_url": ABS_URL},
        "inputs_sha256": {name: sha256_file(p) for name, p in sorted(inputs.items())},
        "patch": {"path": str(PATCH_REL), "sha256": sha256_file(patch_path), "applied_with": "git apply"},
        "patched_sources_sha256": {rel: sha256_file(out / "patched-src" / rel)
                                   for rel in sorted(PATCHED_SOURCE_SHA256)},
        "output_jar": {"path": jar_path.name, "sha256": sha256_file(jar_path)},
        "tools": {"jdk_home": str(jdk_home), "javac": (ver.stdout + ver.stderr).strip(),
                  "python": sys.version.split()[0], "javac_release": "8"},
        "license": {"name": "Apache License, Version 2.0",
                    "url": "http://www.apache.org/licenses/LICENSE-2.0.txt"},
        "known_divergences": KNOWN_DIVERGENCES,
        "note": "build-only; semantics probe and gdcc/Godot matrix are owned by the caller",
    }
    (out / "provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8")

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Build a java.desktop-free FreeMarker candidate jar.")
    parser.add_argument("--out", required=True, help="output work directory (must not exist)")
    parser.add_argument("--jdk", default=None, help="JDK home (default: $JAVA_HOME)")
    args = parser.parse_args(argv)

    out = Path(args.out).resolve()
    if out.exists():
        die(f"output path already exists: {out}")
    patch_path = REPO_ROOT / PATCH_REL
    if not patch_path.exists():
        die(f"missing patch: {patch_path}")
    if sha256_file(patch_path) != PATCH_SHA256:
        die("patch SHA-256 does not match the pinned value")

    javac, jdk_home = find_javac(args.jdk)
    out.mkdir(parents=True)

    inputs = fetch_pinned(out / "download")
    official_jar = inputs[f"freemarker-gae-{GAE_VERSION}.jar"]
    sources_jar = inputs[f"freemarker-gae-{GAE_VERSION}-sources.jar"]

    src_root = out / "src"
    prepare_sources(sources_jar, src_root)
    git_apply(patch_path, src_root)
    verify_patched_sources(src_root)

    classes_dir = out / "classes"
    compile_sources(javac, src_root, classes_dir, official_jar)

    out_jar = out / f"freemarker-gae-{GAE_VERSION}-no-desktop.jar"
    replaced = rewrite_jar(official_jar, classes_dir, out_jar)
    copy_audit_material(out, src_root, patch_path, sources_jar)
    write_provenance(out, inputs, patch_path, out_jar, javac, jdk_home)

    log(f"replaced {replaced} jar entries; output: {out_jar}")
    log(f"output jar SHA-256: {sha256_file(out_jar)}")
    log(f"provenance: {out / 'provenance.json'}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
