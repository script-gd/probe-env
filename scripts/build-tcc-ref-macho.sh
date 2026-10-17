#!/usr/bin/env bash
# Builds a reference tcc (arm64, Mach-O) from the minimal keep-set in vendor/tinycc on macOS,
# then compiles the full fixture to a .dylib and dlopens it. Proves the keep-set + tccmacho
# subset covers gdcc's macOS dylib use case. Runs on Apple Silicon GitHub runners.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/vendor/tinycc"
W="$ROOT/tmp/tcc-ref-macho"
rm -rf "$W"; mkdir -p "$W"

SDK="$(xcrun --show-sdk-path)"

cat > "$W/config.h" <<EOF
#define TCC_VERSION "0.9.28rc-gdcc-javaprobe"
#define CONFIG_TCC_SYSINCLUDEPATHS "{B}/include:$SDK/usr/include"
#define CONFIG_TCC_LIBPATHS "{B}:/usr/lib:/usr/local/lib"
#define CONFIG_TCC_ELFINTERP "/usr/lib/dyld"
#define CONFIG_CODESIGN 1
EOF

# Same debug/eh-frame/tcov/JIT stubs as the ELF build.
cp "$ROOT/reference-cli/stubs.c" "$W/stubs.c"

cp "$ROOT/reference-cli/tcc.c" "$W/tcc-main.c"
cp "$ROOT/reference-cli/tcctools.c" "$W/tcctools.c"

CFLAGS="-O1 -DONE_SOURCE=0 -DTCC_TARGET_ARM64 -DTCC_TARGET_MACHO -I$V -I$W -fno-strict-aliasing -Wno-unused-result -Wno-deprecated-declarations"
SRCS="$V/libtcc.c $V/tccpp.c $V/tccgen.c $V/tccasm.c $V/tccelf.c $V/tccmacho.c $V/arm64-gen.c $V/arm64-link.c $V/arm64-asm.c $W/stubs.c $W/tcc-main.c"
cc $CFLAGS $SRCS -o "$W/tcc"
mkdir -p "$W/include" && cp "$V"/include/*.h "$W/include/"

# Runtime support archive (arm64 keep-set), built by the reference tcc itself.
mkdir -p "$W/libobj"
for f in lib-arm64.c stdatomic.c builtin.c dsohandle.c; do
  "$W/tcc" -B"$W" -c "$V/lib/$f" -o "$W/libobj/$(basename ${f%.c}).o"
done
for f in atomic.S; do
  "$W/tcc" -B"$W" -c "$V/lib/$f" -o "$W/libobj/$(basename ${f%.S}).o"
done
"$W/tcc" -B"$W" -ar rcs "$W/libtcc1.a" "$W"/libobj/*.o

# Compile the full fixture to a dylib (tcc ad-hoc codesigns via CONFIG_CODESIGN).
T="$ROOT/fixture"
mkdir -p "$W/obj"
for tu in "$T/entry.c" "$T/include/godot/godot_binding.c" "$T/include/gdcc/minicoro.c" "$T/include/gdcc/gdcc_coroutine.c" "$T/include/gdcc/gdcc_hrx.c"; do
  "$W/tcc" -B"$W" -std=c11 -I"$T/include/gdcc" -I"$T/include/godot" -c "$tu" -o "$W/obj/$(basename ${tu%.c}).o"
done
"$W/tcc" -B"$W" -std=c11 -shared -o "$W/libprobe.dylib" "$W"/obj/*.o
file "$W/libprobe.dylib"
codesign -v "$W/libprobe.dylib"
python3 -c "import ctypes; ctypes.CDLL('$W/libprobe.dylib'); print('dlopen OK')"
echo "OK: minimal keep-set arm64 Mach-O tcc built and fixture dylib verified"
