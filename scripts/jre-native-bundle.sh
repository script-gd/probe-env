#!/usr/bin/env bash
# Builds a minimal cross-platform tinycc bundle for the gdcc JRE native (libtcc/FFM) backend
# from the pinned vendored sources in vendor/tinycc/ (same libtcc.c as ../gdcc/src/main/c/tinycc).
#
# Usage:
#   scripts/jre-native-bundle.sh <output-dir-absolute-path>
#   OUTPUT=<output-dir-absolute-path> scripts/jre-native-bundle.sh
#
# The bundle layout matches gdcc's TinyCcBundle contract (see
# ../gdcc/src/main/java/gd/script/gdcc/backend/c/build/TinyCcBundle.java):
#   bin/libtcc.so | bin/libtcc.dylib   FFM loading target (must export tcc_new)
#   libtcc1.a                          runtime support archive at the bundle ROOT (the ELF and
#                                      Mach-O backends search {B} itself, not {B}/lib)
#   include/*.h, include/tcclib.h      tinycc's own headers (tccdefs.h, stddef.h, stdarg.h, ...)
#   VERSION                            bundleFormat / tinyccCommit / platform
#   MANIFEST                           sorted sha256sum lines over every other bundle file
#   COPYING, RELICENSING               upstream licenses
#
# Supported hosts: Linux x86_64, Linux aarch64, macOS arm64. Windows is not handled here (its
# bundle ships as a prebuilt snapshot). Builds happen in build/jre-native-<platform>/ ; the
# vendored tree is never configured or built in place, and the tcc CLI is never copied into the
# bundle (only created in the work tree to bootstrap libtcc1.a and run the smoke test).
set -euo pipefail

OUTPUT="${1:-${OUTPUT:-}}"
if [[ -z "$OUTPUT" ]]; then
    echo "usage: $0 <output-dir-absolute-path>" >&2
    echo "   or: OUTPUT=<output-dir-absolute-path> $0" >&2
    exit 2
fi
[[ "$OUTPUT" == /* ]] || { echo "error: OUTPUT must be an absolute path, got: $OUTPUT" >&2; exit 2; }
case "$OUTPUT" in
    / | /usr | /etc | /bin | /lib | /home | "$HOME")
        echo "error: refusing unsafe OUTPUT: $OUTPUT" >&2
        exit 2
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$REPO_ROOT/vendor/tinycc"

TINYCC_COMMIT="43c7708b85681a2fd4451c8a541af4494a8919b2"
BUNDLE_FORMAT_VERSION="1"

# ---- host / platform detection ---------------------------------------------
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS:$ARCH" in
    Linux:x86_64)
        PLATFORM_KEY="linux-x86_64"
        NPROC="$(nproc 2>/dev/null || echo 4)"
        ;;
    Linux:aarch64)
        PLATFORM_KEY="linux-aarch64"
        NPROC="$(nproc 2>/dev/null || echo 4)"
        ;;
    Darwin:arm64)
        PLATFORM_KEY="macos-aarch64"
        NPROC="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
        ;;
    *)
        echo "error: unsupported host $OS/$ARCH; supported: Linux x86_64, Linux aarch64, macOS arm64" >&2
        exit 1
        ;;
esac

if [[ "$OS" == Darwin ]]; then
    LIB_NAME="libtcc.dylib"
    SMOKE_EXT="dylib"
else
    LIB_NAME="libtcc.so"
    SMOKE_EXT="so"
fi
LIB_REL="bin/$LIB_NAME"

WORK_DIR="$REPO_ROOT/build/jre-native-$PLATFORM_KEY"

# ---- preconditions ---------------------------------------------------------
[ -f "$SRC_DIR/libtcc.c" ] || { echo "error: vendored sources not found at $SRC_DIR" >&2; exit 1; }
[ -f "$SRC_DIR/VERSION" ] || { echo "error: $SRC_DIR/VERSION is missing" >&2; exit 1; }
grep -q "$TINYCC_COMMIT" "$SRC_DIR/GDCC_PIN.md" || {
    echo "error: $SRC_DIR/GDCC_PIN.md does not record the pinned commit $TINYCC_COMMIT — vendored tree and script disagree" >&2
    exit 1
}
command -v make >/dev/null || { echo "error: make is required" >&2; exit 1; }
command -v nm >/dev/null || { echo "error: nm is required" >&2; exit 1; }
if [[ "$OS" == Darwin ]]; then
    command -v clang >/dev/null || { echo "error: clang is required (Xcode command line tools)" >&2; exit 1; }
    command -v otool >/dev/null || { echo "error: otool is required for the Mach-O assertions" >&2; exit 1; }
    command -v codesign >/dev/null || { echo "error: codesign is required for the signature assertion" >&2; exit 1; }
    command -v shasum >/dev/null || { echo "error: shasum is required for the MANIFEST" >&2; exit 1; }
    HASH_CMD=(shasum -a 256)
else
    command -v cc >/dev/null || { echo "error: a native C compiler (gcc/clang) is required" >&2; exit 1; }
    command -v sha256sum >/dev/null || { echo "error: sha256sum is required for the MANIFEST" >&2; exit 1; }
    HASH_CMD=(sha256sum)
fi

# ---- clean staging ---------------------------------------------------------
# Fresh work tree and output. Never configure or build inside the vendored source tree.
[[ ! -e "$OUTPUT" ]] || { echo "error: OUTPUT already exists: $OUTPUT" >&2; exit 2; }
rm -rf -- "$WORK_DIR"
mkdir -p "$WORK_DIR" "$OUTPUT"
cp -a "$SRC_DIR/." "$WORK_DIR/"
rm -f "$WORK_DIR/GDCC_PIN.md"

# Deterministic toolchain environment: ambient search paths and flag overrides would leak into
# both the libtcc build and the smoke's link search.
export LC_ALL=C
unset LIBRARY_PATH LD_LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH CPATH || true
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS LDLIBS MAKEFLAGS GNUMAKEFLAGS || true

cd "$WORK_DIR"

# ---- build -----------------------------------------------------------------
# --disable-static skips the unused static libtcc; --libdir keeps the shared library in the work
# root. No --cc override: the host gcc/clang is used.
./configure --disable-static --libdir="$PWD"
# Order matters: the native tcc CLI bootstraps libtcc1.a via lib/Makefile.
make -j"$NPROC" tcc "$LIB_NAME" libtcc1.a

if [[ "$OS" == Darwin ]]; then
    # Two-level namespace rebuild is MANDATORY. Upstream's Makefile links the host dylib with
    # -flat_namespace, and a flat-namespace libtcc mis-binds its global symbols (e.g.
    # parse_flags) to same-named symbols of other libraries inside a JVM process, crashing the
    # first FFM compile. Passing LDFLAGS on the command line overrides the += assignment and -B
    # forces the relink.
    make -B -j"$NPROC" LDFLAGS=-Wl,-twolevel_namespace tcc "$LIB_NAME"
fi

# ---- assemble the bundle ---------------------------------------------------
mkdir -p "$OUTPUT/bin" "$OUTPUT/include"
cp "$LIB_NAME" "$OUTPUT/bin/"
cp libtcc1.a "$OUTPUT/"
cp include/*.h tcclib.h "$OUTPUT/include/"
cp COPYING RELICENSING "$OUTPUT/"

cat > "$OUTPUT/VERSION" <<EOF
bundleFormat=$BUNDLE_FORMAT_VERSION
tinyccCommit=$TINYCC_COMMIT
platform=$PLATFORM_KEY
EOF

# Sorted sha256sum-format manifest over every other bundle file; consumed by the installer to
# verify a staged tree before it is published. BSD/GNU find both print the leading ./ which is
# stripped for portable relative paths.
( cd "$OUTPUT" && find . -type f ! -name MANIFEST | LC_ALL=C sort | sed 's|^\./||' | xargs "${HASH_CMD[@]}" > MANIFEST )

# ---- verify: layout --------------------------------------------------------
for entry in VERSION MANIFEST COPYING RELICENSING libtcc1.a \
             include/tccdefs.h include/stddef.h include/stdarg.h include/tcclib.h "$LIB_REL"; do
    [ -f "$OUTPUT/$entry" ] || { echo "error: bundle is missing $entry" >&2; exit 1; }
done
[ ! -e "$OUTPUT/tcc" ] && [ ! -e "$OUTPUT/bin/tcc" ] || {
    echo "error: the tcc CLI must not be copied into the bundle" >&2
    exit 1
}

# VERSION contract (gdcc TinyCcBundle.VERSION_FILE_NAME).
grep -qx "bundleFormat=$BUNDLE_FORMAT_VERSION" "$OUTPUT/VERSION" || { echo "error: bad VERSION bundleFormat" >&2; exit 1; }
grep -qx "tinyccCommit=$TINYCC_COMMIT" "$OUTPUT/VERSION" || { echo "error: bad VERSION tinyccCommit" >&2; exit 1; }
grep -qx "platform=$PLATFORM_KEY" "$OUTPUT/VERSION" || { echo "error: bad VERSION platform" >&2; exit 1; }

# MANIFEST must cover every file (fail closed, matching TinyCcBundle.manifestIntegrityProblems).
expected="$(cd "$OUTPUT" && find . -type f ! -name MANIFEST | sed 's|^\./||' | LC_ALL=C sort)"
listed="$(awk '{print $2}' "$OUTPUT/MANIFEST" | LC_ALL=C sort)"
[ -n "$listed" ] || { echo "error: MANIFEST covers no files" >&2; exit 1; }
[ "$expected" = "$listed" ] || { echo "error: MANIFEST does not cover exactly the bundle files" >&2; exit 1; }

# Recompute every hash against the manifest.
( cd "$OUTPUT" && "${HASH_CMD[@]}" -c MANIFEST >/dev/null ) || { echo "error: MANIFEST integrity check failed" >&2; exit 1; }

# ---- verify: structure / exports -------------------------------------------
# Note: under `set -o pipefail`, `tool ... | grep -q` is a SIGPIPE race (grep exits on the first
# match while the tool is still writing); consume the whole stream instead.
if [[ "$OS" == Darwin ]]; then
    otool -hv "$OUTPUT/$LIB_REL" | grep 'ARM64' >/dev/null || { echo "error: $LIB_NAME is not a Mach-O arm64 image" >&2; exit 1; }
    otool -hv "$OUTPUT/$LIB_REL" | grep 'TWOLEVEL' >/dev/null || { echo "error: $LIB_NAME lacks the two-level namespace flag" >&2; exit 1; }
    codesign -v "$OUTPUT/$LIB_REL" || { echo "error: $LIB_NAME fails codesign verification" >&2; exit 1; }
    nm -gU "$OUTPUT/$LIB_REL" | grep ' _tcc_new$' >/dev/null || { echo "error: $LIB_NAME does not export tcc_new" >&2; exit 1; }
else
    file "$OUTPUT/$LIB_REL" | grep -q 'shared object' || { echo "error: $LIB_NAME is not a shared object" >&2; exit 1; }
    nm -D "$OUTPUT/$LIB_REL" | grep ' T tcc_new$' >/dev/null || { echo "error: $LIB_NAME does not export tcc_new" >&2; exit 1; }
fi

# ---- verify: relocation smoke ----------------------------------------------
# The bundle is copied to a fresh directory and used only through -B (the CLI equivalent of
# tcc_set_lib_path): tinycc's own headers and libtcc1.a come from the relocated bundle, while
# crt/libc (or crt/libSystem) resolve through the host paths baked in by configure. A real
# shared-library link is required so the crt chain is exercised.
SMOKE_DIR="$(mktemp -d)"
trap 'rm -rf "$SMOKE_DIR"' EXIT
cp -a "$OUTPUT" "$SMOKE_DIR/bundle"
cat > "$SMOKE_DIR/smoke.c" <<'EOF'
#include <stdio.h>
#include <sys/mman.h>

__attribute__((visibility("default")))
int gdcc_tinycc_smoke(void) {
    void *page = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) {
        return 1;
    }
    munmap(page, 4096);
    printf("tinycc relocation smoke ok\n");
    return 0;
}
EOF
"$WORK_DIR/tcc" -B "$SMOKE_DIR/bundle" -shared -o "$SMOKE_DIR/libsmoke.$SMOKE_EXT" "$SMOKE_DIR/smoke.c"
if [[ "$OS" == Darwin ]]; then
    nm -gU "$SMOKE_DIR/libsmoke.$SMOKE_EXT" | grep ' _gdcc_tinycc_smoke$' >/dev/null || { echo "error: smoke library does not export gdcc_tinycc_smoke" >&2; exit 1; }
    otool -L "$SMOKE_DIR/libsmoke.$SMOKE_EXT" | grep -q 'libSystem' || { echo "error: smoke library does not link libSystem" >&2; exit 1; }
else
    nm -D "$SMOKE_DIR/libsmoke.$SMOKE_EXT" | grep ' T gdcc_tinycc_smoke$' >/dev/null || { echo "error: smoke library does not export gdcc_tinycc_smoke" >&2; exit 1; }
    ldd "$SMOKE_DIR/libsmoke.$SMOKE_EXT" | grep -q 'libc\.so\.' || { echo "error: smoke library does not link the host libc" >&2; exit 1; }
fi

echo
echo "bundle assembled and verified at: $OUTPUT"
