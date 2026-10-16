#!/usr/bin/env python3
"""Build a pruned, JavaBeans-only java.desktop runtime from the matching Temurin JMODs.
gdcc only needs java.desktop for FreeMarker's JavaBean introspection, but the module
bundles AWT/Swing/ImageIO/Sound/Print and their native libraries. This tool rebuilds it
from the original JDK bytecode, keeping java/beans/**, com/sun/beans/** and
javax/swing/{SwingContainer.class, tree/TreeNode.class, tree/TreePath.class}.
The java.desktop name is kept because java.base grants jdk.internal.access and
sun.reflect.generics.reflectiveObjects only to it, and ModuleLoaderMap maps it to a
built-in loader; a differently named module lands on the application loader, which cannot
define java.* packages (see --negative-test). The pinned Temurin jmods archive is placed on
jlink's module path so the custom java.desktop shadows the original; java.base's
ModuleHashes binding is stripped from a copy so jlink accepts the rebuilt module. Nothing
in the JDK, gdcc, or runtime-input is modified, and --out must not already exist.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import platform as host_platform
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
MODULE_INFO = ROOT / "probes" / "beans-module" / "module-info.java"
PINNED_JAVA_VERSION = "25.0.2"
PINNED_RELEASE = "25.0.2+10"
RELEASE_BASE = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/"
JMOD_ASSETS = {
    "linux-x86_64": ("OpenJDK25U-jmods_x64_linux_hotspot_25.0.2_10.tar.gz",
                     "6bb76a10192a53f2a0db8b846f1d1ff0fafa77dff95db95d8c9e0fd4c6f95f62"),
    "macos-aarch64": ("OpenJDK25U-jmods_aarch64_mac_hotspot_25.0.2_10.tar.gz",
                      "011b925f73d77f113ba3db9f50dd4a04fc631f65097a6def9a23addaa14664a2"),
    "windows-x86_64": ("OpenJDK25U-jmods_x64_windows_hotspot_25.0.2_10.zip",
                       "a28a3c75911d8f5a02571e46a1859aec2e172a4829bf6e9a89715c882a853d77"),
}
LINK_MODULES = ["java.base", "java.desktop", "java.logging", "java.xml", "jdk.httpserver"]
PRUNED_PATHS = ["java/beans", "com/sun/beans", "javax/swing/SwingContainer.class",
                "javax/swing/tree/TreeNode.class", "javax/swing/tree/TreePath.class"]
GUI_NATIVE_LIBS = {f"lib{n}.{e}" for n in ("awt", "awt_headless", "awt_xawt", "fontmanager",
                   "freetype", "javajpeg", "jawt", "jsound", "lcms", "mlib_image", "splashscreen")
                   for e in ("so", "dylib")} | {f"{n}.dll" for n in ("awt", "awt_headless",
                   "awt_xawt", "fontmanager", "freetype", "jpeg", "jawt", "jsound", "lcms",
                   "mlib_image", "splashscreen")}
CONSTANT_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
                  15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
NEGATIVE_DESCRIPTOR = "module gdcc.beans {\n    requires java.base;\n    exports java.beans;\n}\n"
SMOKE_SOURCE = """\
import java.beans.*;
public final class BeansSmoke {
    public static class Bean {
        private String name; private int count;
        public String getName() { return name; } public void setName(String v) { name = v; }
        public int getCount() { return count; } public void setCount(int v) { count = v; }
    }
    public static void main(String[] args) throws Exception {
        BeanInfo info = Introspector.getBeanInfo(Bean.class);
        StringBuilder names = new StringBuilder();
        for (PropertyDescriptor d : info.getPropertyDescriptors()) names.append(d.getName()).append(',');
        if (!names.toString().contains("name") || !names.toString().contains("count"))
            throw new AssertionError("unexpected properties: " + names);
        System.out.println("BEANS_SMOKE_PASS properties=" + names
                + " methods=" + info.getMethodDescriptors().length
                + " bean=" + info.getBeanDescriptor().getBeanClass().getName());
    }
}
"""
def platform_key() -> str:
    machine = host_platform.machine().lower()
    key = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(host_platform.system(), "")
    key += "-" + ("x86_64" if machine in ("x86_64", "amd64") else
                  "aarch64" if machine in ("arm64", "aarch64") else machine)
    if key not in JMOD_ASSETS:
        raise SystemExit(f"unsupported host platform: {key}")
    return key
def run(args, *, log=None, check=True, timeout=1800):
    args = [str(a) for a in args]
    print("+", subprocess.list2cmdline(args), flush=True)
    result = subprocess.run(args, cwd=str(ROOT), text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    print(result.stdout, flush=True)
    if log:
        Path(log).write_text(result.stdout, encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError(f"Exit {result.returncode}: {args[0]} (see {log})")
    return result
def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()
def tool(jdk: Path, name: str) -> Path:
    return jdk / "bin" / (name + (".exe" if os.name == "nt" else ""))
def pinned_java_version(jdk: Path) -> str:
    release = (jdk / "release").read_text(encoding="utf-8", errors="replace")
    def field(name):
        found = re.search(rf'^{name}="([^"]*)"', release, re.M)
        return found.group(1) if found else ""
    if field("JAVA_VERSION") != PINNED_JAVA_VERSION or field("SEMANTIC_VERSION") != PINNED_RELEASE:
        raise RuntimeError(f"JDK must be the pinned Temurin {PINNED_RELEASE}")
    return PINNED_JAVA_VERSION
def download_verified(asset: str, sha256: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / asset
    if destination.exists() and digest(destination) == sha256:
        print(f"reusing cached {destination}", flush=True)
        return destination
    if destination.exists():
        destination.unlink()
    part = destination.with_name(destination.name + ".part")
    print(f"downloading {RELEASE_BASE + asset}", flush=True)
    request = urllib.request.Request(RELEASE_BASE + asset, headers={"User-Agent": "beans-runtime/1.0"})
    with urllib.request.urlopen(request, timeout=900) as response, part.open("wb") as target:
        shutil.copyfileobj(response, target, 1024 * 1024)
    if digest(part) != sha256:
        part.unlink()
        raise RuntimeError(f"SHA-256 mismatch for {asset}")
    part.replace(destination)
    return destination
def extract_archive(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        root = destination.resolve()
        with zipfile.ZipFile(archive) as bundle:
            for info in bundle.infolist():
                target = (root / info.filename).resolve()
                if target != root and root not in target.parents:
                    raise RuntimeError(f"unsafe zip entry: {info.filename}")
                bundle.extract(info, root)
    else:
        with tarfile.open(archive) as bundle:
            bundle.extractall(destination, filter="data")
def ensure_jmods(cache_dir: Path, platform: str) -> tuple[Path, Path]:
    asset, sha256 = JMOD_ASSETS[platform]
    archive = download_verified(asset, sha256, cache_dir)
    extracted = cache_dir / platform
    marker = next(extracted.rglob("java.base.jmod"), None) if extracted.exists() else None
    if marker is None:
        if extracted.exists():
            shutil.rmtree(extracted)
        extract_archive(archive, extracted)
        marker = next(extracted.rglob("java.base.jmod"), None)
    if marker is None:
        raise RuntimeError(f"java.base.jmod not found under {extracted}")
    return archive, marker.parent
class _Cursor:
    def __init__(self, data: bytes):
        self.data, self.offset = data, 0
    def u1(self) -> int:
        value = self.data[self.offset]; self.offset += 1; return value
    def u2(self) -> int:
        value = struct.unpack_from(">H", self.data, self.offset)[0]; self.offset += 2; return value
    def u4(self) -> int:
        value = struct.unpack_from(">I", self.data, self.offset)[0]; self.offset += 4; return value
def strip_module_hashes(path: Path) -> int:
    data = path.read_bytes()
    cursor = _Cursor(data)
    if cursor.u4() != 0xCAFEBABE:
        raise RuntimeError(f"not a class file: {path}")
    cursor.u2(); cursor.u2()
    count = cursor.u2()
    pool = [None] * count
    index = 1
    while index < count:
        tag = cursor.u1()
        if tag == 1:
            length = cursor.u2()
            pool[index] = data[cursor.offset:cursor.offset + length].decode("utf-8", "replace")
            cursor.offset += length
        elif tag in CONSTANT_SIZES:
            cursor.offset += CONSTANT_SIZES[tag]
            if tag in (5, 6):
                index += 1
        else:
            raise RuntimeError(f"unknown constant pool tag {tag}")
        index += 1
    cursor.offset += 6
    interfaces = cursor.u2()
    cursor.offset += 2 * interfaces
    for _ in range(2):
        members = cursor.u2()
        for _ in range(members):
            cursor.offset += 6
            attributes = cursor.u2()
            for _ in range(attributes):
                cursor.u2()
                length = cursor.u4()
                cursor.offset += length
    start = cursor.offset
    kept, removed = [], 0
    for _ in range(cursor.u2()):
        attribute = cursor.offset
        name_index = cursor.u2()
        length = cursor.u4()
        cursor.offset += length
        if pool[name_index] == "ModuleHashes":
            removed += 1
        else:
            kept.append(data[attribute:cursor.offset])
    path.write_bytes(data[:start] + struct.pack(">H", len(kept)) + b"".join(kept))
    return removed
def copy_pruned_classes(original: Path, classes: Path) -> int:
    for relative in PRUNED_PATHS:
        source, target = original / relative, classes / relative
        if source.is_dir():
            shutil.copytree(source, target)
        elif source.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        else:
            raise RuntimeError(f"missing {relative} in original java.desktop.jmod")
    return sum(1 for _ in classes.rglob("*.class"))
def recreate_java_base(jdk: Path, jmods_dir: Path, work: Path, version: str) -> tuple[Path, int]:
    extracted = work / "java_base"
    extracted.mkdir(parents=True)
    run([tool(jdk, "jmod"), "extract", "--dir", extracted, jmods_dir / "java.base.jmod"],
        log=work / "java.base-extract.log")
    removed = strip_module_hashes(extracted / "classes" / "module-info.class")
    if removed != 1:
        raise RuntimeError(f"expected one ModuleHashes attribute, removed {removed}")
    recreated = extracted / "java.base.jmod"
    command = [tool(jdk, "jmod"), "create"]
    for flag, section in (("--class-path", "classes"), ("--cmds", "bin"), ("--config", "conf"),
                          ("--header-files", "include"), ("--legal-notices", "legal"),
                          ("--libs", "lib"), ("--man-pages", "man")):
        directory = extracted / section
        if directory.is_dir() and any(directory.iterdir()):
            command += [flag, directory]
    command += ["--module-version", version, recreated]
    run(command, log=work / "java.base-create.log")
    return recreated, removed
def stage_module_path(jmods_dir: Path, pruned: Path, java_base: Path, work: Path) -> dict:
    modpath = work / "modpath"
    modpath.mkdir()
    shutil.copy2(java_base, modpath / "java.base.jmod")
    shutil.copy2(pruned, modpath / "java.desktop.jmod")
    staged = {"symlink": 0, "copy": 0}
    for source in sorted(jmods_dir.glob("*.jmod")):
        if source.name in ("java.base.jmod", "java.desktop.jmod"):
            continue
        try:
            os.symlink(source, modpath / source.name)
            staged["symlink"] += 1
        except OSError:
            shutil.copy2(source, modpath / source.name)
            staged["copy"] += 1
    return staged
def image_classes(jdk: Path, image: Path) -> list[str]:
    listing = run([tool(jdk, "jimage"), "list", image / "lib" / "modules"]).stdout
    return [line.strip() for line in listing.splitlines()
            if re.match(r"\s+(java/beans|com/sun/beans|javax/swing)/", line)]
def verify(jdk: Path, image: Path, expected_classes: int) -> dict:
    java = tool(image, "java")
    describe = run([java, "--describe-module", "java.desktop"]).stdout
    for forbidden in ("java.prefs", "java.datatransfer", "java.xml", "jdk.unsupported.desktop"):
        if re.search(rf"^\s*requires\s+{re.escape(forbidden)}\b", describe, re.M):
            raise RuntimeError(f"pruned java.desktop still requires {forbidden}")
    for required in ("requires java.base", "exports java.beans", "exports javax.swing.tree"):
        if required not in describe:
            raise RuntimeError(f"missing {required} in pruned java.desktop")
    modules = {line.split("@")[0] for line in run([java, "--list-modules"]).stdout.splitlines() if line.strip()}
    if modules != set(LINK_MODULES):
        raise RuntimeError(f"unexpected module set: {sorted(modules)}")
    if (image / "bin" / ("javac" + (".exe" if os.name == "nt" else ""))).exists():
        raise RuntimeError("packaged a JDK instead of a runtime")
    native = sorted(str(p.relative_to(image)) for p in image.rglob("*")
                    if p.is_file() and p.name.lower() in GUI_NATIVE_LIBS)
    if native:
        raise RuntimeError(f"GUI native libraries in image: {native}")
    classes = image_classes(jdk, image)
    if len(classes) != expected_classes:
        raise RuntimeError(f"java.desktop class count {len(classes)} != expected {expected_classes}")
    return {"describe_module": describe.splitlines(), "modules": sorted(modules),
            "java_desktop_class_count": len(classes), "gui_native_libs": "none", "javac_absent": True}
def smoke_test(jdk: Path, image: Path, work: Path) -> str:
    source = work / "BeansSmoke.java"
    source.write_text(SMOKE_SOURCE, encoding="utf-8")
    classes = work / "smoke-classes"
    run([tool(jdk, "javac"), "-d", classes, source])
    result = run([tool(image, "java"), "-cp", classes, "BeansSmoke"])
    if "BEANS_SMOKE_PASS" not in result.stdout:
        raise RuntimeError("JavaBeans smoke test did not pass")
    return result.stdout.strip()
def negative_test(jdk: Path, jmods_dir: Path, classes: Path, work: Path) -> str:
    staging = work / "negative"
    staging.mkdir(parents=True)
    negative_classes = staging / "classes"
    shutil.copytree(classes, negative_classes, ignore=shutil.ignore_patterns("module-info.class"))
    descriptor = staging / "module-info.java"
    descriptor.write_text(NEGATIVE_DESCRIPTOR, encoding="utf-8")
    run([tool(jdk, "javac"), "--system", "none", "--module-path", jmods_dir,
         "--patch-module", f"gdcc.beans={negative_classes}", "-d", negative_classes, descriptor])
    mods = staging / "mods"
    mods.mkdir()
    run([tool(jdk, "jmod"), "create", "--class-path", negative_classes, mods / "gdcc.beans.jmod"])
    image = staging / "jre"
    run([tool(jdk, "jlink"), "--module-path", mods, "--add-modules", "java.base,java.logging,gdcc.beans",
         "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", image])
    result = run([tool(image, "java"), "--list-modules"], check=False)
    lowered = result.stdout.lower()
    if result.returncode == 0 or "prohibited package name" not in lowered or "boot layer" not in lowered:
        raise RuntimeError("negative test failed for an unexpected reason")
    return result.stdout.strip()
def build(args) -> None:
    platform = platform_key()
    out = Path(args.out).resolve()
    if out.exists():
        raise RuntimeError(f"output already exists; choose another --out: {out}")
    jdk = Path(args.jdk).resolve()
    if not tool(jdk, "javac").is_file():
        raise RuntimeError(f"--jdk is not a JDK (no javac): {jdk}")
    java_version = pinned_java_version(jdk)
    if not MODULE_INFO.is_file():
        raise RuntimeError(f"missing module descriptor: {MODULE_INFO}")
    archive, jmods_dir = ensure_jmods(Path(args.cache_dir), platform)
    version = re.search(r"@(.+)$", run([tool(jdk, "jmod"), "describe",
                          jmods_dir / "java.desktop.jmod"]).stdout.splitlines()[0]).group(1)
    if version != java_version:
        raise RuntimeError(f"JDK {java_version} does not match JMODs {version}")
    out.mkdir(parents=True)
    work = out / ".work"
    work.mkdir()
    original = work / "original_desktop"
    original.mkdir()
    run([tool(jdk, "jmod"), "extract", "--dir", original, jmods_dir / "java.desktop.jmod"],
        log=work / "desktop-extract.log")
    classes = out / "classes"
    classes.mkdir()
    class_count = copy_pruned_classes(original / "classes", classes)
    run([tool(jdk, "javac"), "--system", "none", "--module-path", jmods_dir,
         "--patch-module", f"java.desktop={classes}", "-d", classes, MODULE_INFO])
    pruned = out / "jmods" / "java.desktop.jmod"
    pruned.parent.mkdir()
    run([tool(jdk, "jmod"), "create", "--class-path", classes, "--legal-notices", original / "legal",
         "--module-version", version, pruned])
    java_base, hashes_removed = recreate_java_base(jdk, jmods_dir, work, version)
    staged = stage_module_path(jmods_dir, pruned, java_base, work)
    image = out / "jre"
    run([tool(jdk, "jlink"), "--module-path", work / "modpath", "--add-modules", ",".join(LINK_MODULES),
         "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", image])
    audit, original_out = out / "audit", out / "original"
    audit.mkdir()
    original_out.mkdir()
    shutil.copy2(jmods_dir / "java.desktop.jmod", original_out / "java.desktop.jmod")
    shutil.copy2(original / "classes" / "module-info.class", original_out / "module-info.class")
    run([tool(jdk, "jmod"), "describe", jmods_dir / "java.desktop.jmod"],
        log=original_out / "descriptor.txt")
    run([tool(jdk, "jmod"), "describe", pruned], log=audit / "pruned-descriptor.txt")
    (audit / "java_desktop_classes.txt").write_text("\n".join(image_classes(jdk, image)) + "\n",
                                                    encoding="utf-8")
    verification = verify(jdk, image, class_count)
    results = {
        "platform": platform, "jdk": str(jdk), "java_version": java_version,
        "temurin_release": PINNED_RELEASE,
        "jmod_asset": {"url": RELEASE_BASE + JMOD_ASSETS[platform][0],
                       "sha256": JMOD_ASSETS[platform][1], "bytes": archive.stat().st_size},
        "module_version": version, "linked_modules": LINK_MODULES,
        "java_desktop": {"pruned_sha256": digest(pruned), "pruned_bytes": pruned.stat().st_size,
                         "classes": class_count, "requires": ["java.base"],
                         "exports": ["java.beans", "java.beans.beancontext", "javax.swing.tree"],
                         "native_libs": "none"},
        "java_base": {"recreated": True, "module_hashes_removed": hashes_removed},
        "module_path": staged,
        "jre": {"bytes": sum(p.stat().st_size for p in image.rglob("*") if p.is_file()),
                "java_desktop_class_count": verification["java_desktop_class_count"]},
        "verification": verification, "smoke": smoke_test(jdk, image, work),
    }
    if args.negative_test:
        negative = negative_test(jdk, jmods_dir, classes, work)
        (audit / "negative-gdcc-beans.txt").write_text(negative + "\n", encoding="utf-8")
        results["negative_gdcc_beans"] = {"descriptor": NEGATIVE_DESCRIPTOR.strip(),
                                          "evidence": negative.splitlines(),
                                          "result": "boot layer refused app-loader java.beans"}
    (out / "provenance.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    shutil.rmtree(work)
    print("BEANS_RUNTIME_PASS::" + platform, flush=True)
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"),
                        help="pinned Temurin 25.0.2+10 JDK (defaults to JAVA_HOME)")
    parser.add_argument("--out", type=Path, default=None,
                        help="output directory; must not exist (default build/beans-runtime/<platform>)")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / "tools/jmods-cache")
    parser.add_argument("--negative-test", action="store_true")
    args = parser.parse_args()
    if not args.jdk:
        parser.error("--jdk or JAVA_HOME is required")
    if args.out is None:
        args.out = ROOT / "build" / "beans-runtime" / platform_key()
    build(args)
    return 0
if __name__ == "__main__":
    sys.exit(main())
