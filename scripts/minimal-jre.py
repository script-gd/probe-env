#!/usr/bin/env python3
"""Snapshot compiled gdcc outputs, then link and test native, classpath JRE images.

Python and a JDK are build/test tools only; the resulting distribution needs neither.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
INPUT = ROOT / "runtime-input"
PROFILES = {"base": "java.base", "cli": "java.base,java.desktop,java.logging",
            "full": "java.base,java.desktop,java.logging,jdk.httpserver"}
FLAGS = ["--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny"]


def run(args, *, log=None, env=None, check=True):
    args = [str(arg) for arg in args]
    print("+", subprocess.list2cmdline(args), flush=True)
    result = subprocess.run(args, cwd=ROOT, env=env, text=True, encoding="utf-8",
                            errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=300)
    print(result.stdout, flush=True)
    if log:
        Path(log).write_text(result.stdout, encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError(f"Exit {result.returncode}: {args[0]} (see {log})")
    return result


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def archive(source, destination):
    # Preserve executable modes and symlinks, normalize archive metadata (not JDK contents).
    with destination.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0,
                                                     filename="") as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as output:
            def normalize(info):
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                return info
            output.add(source, arcname=source.name, filter=normalize)


def snapshot(gdcc):
    """Copies binaries/resources only. Does not build or change the gdcc checkout."""
    app = INPUT / "app"
    classes = gdcc / "build/classes/java/main"
    resources = gdcc / "build/resources/main"
    if not (classes / "gd/script/gdcc/backend/c/build/TinyCcObjCache.class").is_file():
        raise RuntimeError("Expected already-compiled gdcc output with the TinyCC object cache")
    if app.exists():
        shutil.rmtree(app)
    (app / "lib").mkdir(parents=True)
    packed = app / "gdcc-0.0.2.jar"
    jar_tool = Path(os.environ["JAVA_HOME"]) / "bin" / ("jar.exe" if os.name == "nt" else "jar")
    run([jar_tool, "--create", "--file", packed,
         "--date=2026-01-01T00:00:00Z", "--manifest", gdcc / "build/tmp/jar/MANIFEST.MF",
         "-C", classes, ".", "-C", resources, "."])
    for jar in sorted((gdcc / "build/libs/lib").glob("*.jar")):
        shutil.copy2(jar, app / "lib")
    shutil.copy2(gdcc / "LICENSE.md", INPUT / "GDCC-LICENSE")
    archive(gdcc / "build/tinycc-bundle/windows-x86_64", INPUT / "windows-x86_64.tar.gz")
    with zipfile.ZipFile(packed) as jar:
        version = jar.read("gdcc-version.properties").decode()
    metadata = {"checkout_commit_at_snapshot": run(["git", "-C", gdcc, "rev-parse", "HEAD"]).stdout.strip(),
                "packaged_from": ["build/classes/java/main", "build/resources/main", "build/tmp/jar/MANIFEST.MF"],
                "jar_version_resource": version,
                "files": {p.relative_to(INPUT).as_posix(): digest(p) for p in sorted(INPUT.rglob("*"))
                          if p.is_file() and p.name != "snapshot.json"}}
    (INPUT / "snapshot.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def godot_test(work, library, godot, label, env):
    project = work / (label + "-godot")
    project.mkdir()
    for name in ("project.godot", "run.gd"):
        shutil.copy2(ROOT / "godot" / name, project)
    (project / "bin").mkdir()
    shutil.copy2(library, project / "bin" / library.name)
    family = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}[platform.system()]
    (project / "probe.gdextension").write_text(
        '[configuration]\nentry_symbol = "gdextension_entry"\ncompatibility_minimum = "4.5"\n'
        f'[libraries]\n{family}.debug = "res://bin/{library.name}"\n', encoding="utf-8")
    (project / ".godot").mkdir()
    (project / ".godot/extension_list.cfg").write_text("res://probe.gdextension\n", encoding="utf-8")
    result = run([godot, "--headless", "--path", project, "--script", "run.gd"],
                 log=work / (label + "-godot.log"), env=env)
    if "GDCC_PROJECT_PASS" not in result.stdout.splitlines():
        raise RuntimeError("Godot did not execute the generated extension")


def rpc_test(java, jar, work, env, *, extra_flags=()):
    # Bind only loopback. The temporary-port race is bounded by startup timeout and fails closed.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with (work / "rpc.log").open("w", encoding="utf-8") as output:
        proc = subprocess.Popen([str(java), *extra_flags, *FLAGS, "-jar", str(jar), "serve", "--port", str(port)],
                                cwd=work, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            for attempt in range(60):
                if proc.poll() is not None:
                    raise RuntimeError("gdcc serve exited before HTTP readiness")
                request = urllib.request.Request(f"http://127.0.0.1:{port}/rpc",
                    data=b'{"jsonrpc":"2.0","id":1,"method":"server.ping"}',
                    headers={"Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(request, timeout=2) as response:
                        value = json.load(response)
                    if value.get("result") != "pong" or value.get("id") != 1:
                        raise RuntimeError(f"Unexpected RPC response: {value}")
                    (work / "rpc-response.json").write_text(json.dumps(value), encoding="utf-8")
                    print("JRE_GDCC_RPC_PASS", flush=True)
                    return
                except urllib.error.URLError:
                    time.sleep(0.25)
            raise RuntimeError("gdcc serve HTTP readiness timeout")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


def build(args):
    system = platform.system()
    arch = {"AMD64": "x86_64", "arm64": "aarch64"}.get(platform.machine(), platform.machine())
    key = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}[system] + "-" + arch
    work = ROOT / "build/minimal-jre" / key
    if work.exists():
        raise RuntimeError(f"Output already exists; remove explicitly to rebuild: {work}")
    work.mkdir(parents=True)
    metadata = json.loads((INPUT / "snapshot.json").read_text(encoding="utf-8"))
    for relative, expected in metadata["files"].items():
        if digest(INPUT / relative) != expected:
            raise RuntimeError(f"Snapshot integrity failed: {relative}")
    jdk = Path(args.jdk or os.environ["JAVA_HOME"]).resolve()
    exe = ".exe" if system == "Windows" else ""
    tool = lambda name: jdk / "bin" / (name + exe)
    run([tool("java"), "-XshowSettings:properties", "-version"], log=work / "jdk.log")
    run([tool("jlink"), "--help"], log=work / "jlink-help.log")
    bundle = work / "native" / key
    bundle.parent.mkdir()
    if system == "Windows":
        with tarfile.open(INPUT / "windows-x86_64.tar.gz") as source:
            source.extractall(bundle.parent, filter="data")
    else:
        run(["bash", ROOT / "scripts/jre-native-bundle.sh", bundle], log=work / "native-build.log")

    # jdeps cannot resolve gdcc's compileOnly module dependency. Analyze an unmodified
    # class-only view instead, while keeping all shipped jars byte-for-byte intact.
    analysis = work / "analysis"
    analysis.mkdir()
    # gdparser extracts native libraries beside its jars: execute a work copy, never the snapshot.
    shutil.copytree(INPUT / "app", work / "app", ignore=shutil.ignore_patterns("native"))
    jar_input = next((work / "app").glob("gdcc-*.jar"))
    with zipfile.ZipFile(jar_input) as jar:
        for name in jar.namelist():
            if name.endswith(".class") and not name.startswith("META-INF/") and name != "module-info.class":
                jar.extract(name, analysis)
    for recursive in (False, True):
        run([tool("jdeps"), "--multi-release", "25", "--ignore-missing-deps", "--print-module-deps",
             *(["--recursive"] if recursive else []), "--class-path", str(INPUT / "app/lib/*"), analysis],
            log=work / ("jdeps-recursive.log" if recursive else "jdeps-direct.log"))
    classes = work / "probe-classes"
    run([tool("javac"), "--release", "25", "-cp", jar_input, "-d", classes,
         ROOT / "probes/GdccRuntimeProbe.java"], log=work / "javac.log")
    env = os.environ.copy()
    # Prevent external JDK option injection or pre-existing gdcc caches from masking missing modules.
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GDCC_SHARED_INCLUDE",
                 "GDCC_SHARED_C_COMPILER_CACHE", "ZIG_HOME"):
        env.pop(name, None)
    env["GDCC_TINYCC_HOME"] = str(bundle)
    results = {"platform": key, "snapshot": metadata, "profiles": {}}
    for profile, modules in PROFILES.items():
        image = work / ("jre-" + profile)
        run([tool("jlink"), "--add-modules", modules, "--strip-debug", "--no-header-files",
             "--no-man-pages", "--compress=zip-6", "--output", image], log=work / (profile + "-jlink.log"))
        java = image / "bin" / ("java" + exe)
        actual = run([java, "--list-modules"], log=work / (profile + "-modules.log")).stdout
        actual_modules = {line.split("@")[0] for line in actual.splitlines()}
        if not set(modules.split(",")).issubset(actual_modules):
            raise RuntimeError("Missing linked modules")
        if any(name.startswith("jdk.internal.vm") for name in actual_modules):
            raise RuntimeError("Use the pinned Temurin JDK, not a GraalVM runtime image")
        if (image / "bin" / ("javac" + exe)).exists():
            raise RuntimeError("A JDK was packaged instead of a runtime")
        env["JAVA_HOME"] = str(image)
        env["GDCC_SHARED_C_COMPILER_CACHE"] = str(work / (profile + "-backend-cache"))
        fixture = work / (profile + "-fixture")
        shutil.copytree(ROOT / "fixture", fixture,
                        ignore=shutil.ignore_patterns("obj", "compiler-cache", "*.so", "*.dylib", "*.dll"))
        library = work / ("probe.dll" if system == "Windows" else
                          "libprobe.dylib" if system == "Darwin" else "libprobe.so")
        run([java, *FLAGS, "-cp", os.pathsep.join([str(classes), str(jar_input)]),
             "GdccRuntimeProbe", fixture, library], log=work / (profile + "-backend.log"), env=env)
        godot_test(work, library, args.godot, profile + "-backend", env)
        cli_output = work / (profile + "-cli") / "demo"
        env["GDCC_SHARED_C_COMPILER_CACHE"] = str(work / (profile + "-cli-cache"))
        cli = run([java, *FLAGS, "-jar", jar_input, "--backend", "tcc", "--gde", "4.5.1",
                   "-o", cli_output, ROOT / "fixture/source.gd"],
                  log=work / (profile + "-cli.log"), env=env, check=False)
        if cli.returncode == 0:
            libs = list(cli_output.glob("*.dll" if system == "Windows" else
                                       "*.dylib" if system == "Darwin" else "*.so"))
            if len(libs) != 1:
                raise RuntimeError(f"CLI did not produce exactly one native library: {libs}")
            godot_test(work, libs[0], args.godot, profile + "-cli", env)
        elif profile != "base":
            raise RuntimeError("CLI-capable JRE failed real gdcc CLI compilation")
        elif "NoClassDefFoundError: java/util/logging/Logger" not in cli.stdout:
            raise RuntimeError("Control failed for an unexpected reason, not the omitted logging module")
        package = work / (image.name + ".tar.gz")
        archive(image, package)
        results["profiles"][profile] = {
            "root_modules": modules, "modules": sorted(actual_modules),
            "scope": {"full": "gdcc-cli-and-rpc", "cli": "gdcc-cli", "base": "backend-only-control"}[profile],
            "image_bytes": sum(p.stat().st_size for p in image.rglob("*") if p.is_file()),
            "archive_bytes": package.stat().st_size, "archive_sha256": digest(package),
            "backend_and_godot": "pass", "cli_and_godot": "pass" if cli.returncode == 0 else "fail"}
    no_desktop = run([java, "--limit-modules", "java.base,java.logging,java.xml,jdk.httpserver",
                      *FLAGS, "-jar", jar_input, "--backend", "tcc", "-o", work / "no-desktop/demo",
                      ROOT / "fixture/source.gd"], log=work / "no-desktop.log", env=env, check=False)
    if no_desktop.returncode == 0 or "NoClassDefFoundError: java/beans/IntrospectionException" not in no_desktop.stdout:
        raise RuntimeError("Expected a java.beans failure with java.desktop omitted")
    dist = work / ("gdcc-" + key)
    shutil.copytree(INPUT / "app", dist, ignore=shutil.ignore_patterns("native"))
    shutil.copytree(work / "jre-full", dist / "jre", symlinks=True)
    shutil.copytree(bundle, dist / "tinycc")
    shutil.copy2(INPUT / "GDCC-LICENSE", dist)
    jar_name = jar_input.name
    (dist / "gdcc.sh").write_text('#!/bin/sh\nset -eu\n'
        'HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n'
        'export GDCC_TINYCC_HOME="$HERE/tinycc"\n'
        f'exec "$HERE/jre/bin/java" {" ".join(FLAGS)} -jar "$HERE/{jar_name}" "$@"\n',
        encoding="utf-8")
    (dist / "gdcc.sh").chmod(0o755)
    (dist / "gdcc.cmd").write_text('@echo off\r\nsetlocal\r\n'
        'set "GDCC_TINYCC_HOME=%~dp0tinycc"\r\n'
        f'"%~dp0jre\\bin\\java.exe" {" ".join(FLAGS)} -jar "%~dp0{jar_name}" %*\r\n',
        encoding="utf-8")
    packed = work / (dist.name + ".tar.gz")
    archive(dist, packed)
    # Test the archive, not just its build tree, under a relocated path containing spaces.
    relocated = work / "relocated distribution"
    relocated.mkdir()
    with tarfile.open(packed) as source:
        source.extractall(relocated, filter="tar")
    relocated_dist = relocated / dist.name
    java = relocated_dist / "jre/bin" / ("java" + exe)
    env["JAVA_HOME"] = str(relocated_dist / "jre")
    env["GDCC_TINYCC_HOME"] = str(relocated_dist / "tinycc")
    jar = relocated_dist / jar_name
    run([java, *FLAGS, "-jar", jar, "--version"], log=work / "relocated-version.log", env=env)
    launcher = relocated_dist / ("gdcc.cmd" if system == "Windows" else "gdcc.sh")
    launch_args = [str(launcher), "--backend", "tcc", "-o", str(work / "relocated-cli/demo"),
                   str(ROOT / "fixture/source.gd")]
    if system == "Windows":
        launch_args = ["cmd.exe", "/d", "/c", "call", *launch_args]
    run(launch_args, log=work / "relocated-launcher.log", env=env)
    extension = ".dll" if system == "Windows" else ".dylib" if system == "Darwin" else ".so"
    relocated_libs = list((work / "relocated-cli/demo").glob("*" + extension))
    if len(relocated_libs) != 1:
        raise RuntimeError("Relocated launcher failed to produce a single native library")
    godot_test(work, relocated_libs[0], args.godot, "relocated-cli", env)
    rpc_test(java, jar, work, env)
    results["distribution"] = {"archive_bytes": packed.stat().st_size, "sha256": digest(packed),
                               "relocated_rpc": "pass"}
    (work / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("MINIMAL_JRE_SUITE_PASS::" + key, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    capture = sub.add_parser("snapshot")
    capture.add_argument("--gdcc", type=Path, required=True)
    probe = sub.add_parser("build")
    probe.add_argument("--jdk")
    probe.add_argument("--godot", required=True, type=lambda p: str(Path(p).resolve()))
    options = parser.parse_args()
    if options.command == "snapshot":
        snapshot(options.gdcc.resolve())
    else:
        build(options)
