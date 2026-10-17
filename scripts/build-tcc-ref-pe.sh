#!/usr/bin/env bash
# Cross-builds a reference tcc (x86_64, Windows PE target) from the minimal keep-set on a
# Linux host, then compiles the full fixture to probe.dll. The DLL is structurally checked
# here and load-tested on a Windows runner (CI artifact handoff).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/vendor/tinycc"
W="$ROOT/tmp/tcc-ref-pe"
rm -rf "$W"; mkdir -p "$W"

# PE defaults in tcc.h cover the rest ({B}/include + {B}/include/winapi, {B}/lib).
cat > "$W/config.h" <<'EOF'
#define TCC_VERSION "0.9.28rc-gdcc-javaprobe"
EOF

cp "$ROOT/reference-cli/stubs.c" "$W/stubs.c"
cp "$ROOT/reference-cli/tcc.c" "$W/tcc-main.c"
cp "$ROOT/reference-cli/tcctools.c" "$W/tcctools.c"

CFLAGS="-O1 -DONE_SOURCE=0 -DTCC_TARGET_PE -DTCC_TARGET_X86_64 -I$V -I$W -fno-strict-aliasing -Wno-unused-result"
SRCS="$V/libtcc.c $V/tccpp.c $V/tccgen.c $V/tccasm.c $V/tccelf.c $V/tccpe.c $V/x86_64-gen.c $V/x86_64-link.c $V/i386-asm.c $W/stubs.c $W/tcc-main.c"
cc $CFLAGS $SRCS -o "$W/tcc" -lm

# Bundle-shaped staging root: merged include tree (tcc headers + win32 headers flat) and lib/.
mkdir -p "$W/include" "$W/lib"
cp "$V"/include/*.h "$W/include/"
cp -r "$V"/win32/include/* "$W/include/"
cp "$V"/win32/lib/*.def "$W/lib/"

# Runtime support archive (PE x86_64 keep-set), built by the reference tcc itself.
mkdir -p "$W/libobj"
for f in "$V/lib/libtcc1.c" "$V/lib/stdatomic.c" "$V/lib/builtin.c" "$V/lib/alloca.S" "$V/lib/alloca-bt.S" "$V/lib/atomic.S" "$V/win32/lib/chkstk.S" "$V/win32/lib/dllcrt1.c" "$V/win32/lib/dllmain.c"; do
  b=$(basename "$f"); o="$W/libobj/${b%.*}.o"
  "$W/tcc" -B"$W" -c "$f" -o "$o"
done
"$W/tcc" -B"$W" -ar rcs "$W/lib/libtcc1.a" "$W"/libobj/*.o

# Compile the full fixture to probe.dll, per gdcc's per-TU .o + link flow.
T="$ROOT/fixture"
mkdir -p "$W/obj"
for tu in "$T/entry.c" "$T/include/godot/godot_binding.c" "$T/include/gdcc/minicoro.c" "$T/include/gdcc/gdcc_coroutine.c" "$T/include/gdcc/gdcc_hrx.c"; do
  "$W/tcc" -B"$W" -std=c11 -I"$T/include/gdcc" -I"$T/include/godot" -c "$tu" -o "$W/obj/$(basename ${tu%.c}).o"
done
"$W/tcc" -B"$W" -std=c11 -shared -o "$W/probe.dll" "$W"/obj/*.o
file "$W/probe.dll"
python3 - "$W/probe.dll" <<'PY'
import struct, sys
data = open(sys.argv[1], 'rb').read()
assert data[:2] == b'MZ', 'missing MZ magic'
peoff = struct.unpack_from('<I', data, 0x3c)[0]
assert data[peoff:peoff+4] == b'PE\0\0', 'missing PE magic'
machine = struct.unpack_from('<H', data, peoff+4)[0]
chars = struct.unpack_from('<H', data, peoff+22)[0]
assert machine == 0x8664, f'not x86-64: {machine:#x}'
assert chars & 0x2000, 'not a DLL'
print('probe.dll: PE32+ x86-64 DLL, structural check OK')
PY
echo "OK: minimal keep-set PE tcc built and probe.dll produced"
