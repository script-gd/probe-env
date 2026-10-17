#!/usr/bin/env bash
# Builds a reference tcc CLI from the MINIMAL keep-set in vendor/tinycc (ONE_SOURCE=0,
# debug/JIT replaced by stubs). Success proves the keep-set is self-contained.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/vendor/tinycc"
W="$ROOT/tmp/tcc-ref"
rm -rf "$W"; mkdir -p "$W"

cat > "$W/config.h" <<'EOF'
#define TCC_VERSION "0.9.28rc-gdcc-javaprobe"
#define CONFIG_TCC_CRTPREFIX "/usr/lib/x86_64-linux-gnu"
#define CONFIG_TCC_SYSINCLUDEPATHS "{B}/include:/usr/local/include:/usr/include/x86_64-linux-gnu:/usr/include"
#define CONFIG_TCC_LIBPATHS "{B}:/usr/lib/x86_64-linux-gnu:/usr/lib:/lib/x86_64-linux-gnu:/lib"
EOF

# Debug/eh-frame/coverage/JIT hooks stubbed out (tccdbg.c/tccrun.c deleted, as the Java port).
cp "$ROOT/reference-cli/stubs.c" "$W/stubs.c"

cp "$ROOT/reference-cli/tcc.c" "$W/tcc-main.c"
cp "$ROOT/reference-cli/tcctools.c" "$W/tcctools.c"

CFLAGS="-O1 -DONE_SOURCE=0 -DTCC_TARGET_X86_64 -I$V -I$W -fno-strict-aliasing -Wno-unused-result"
SRCS="$V/libtcc.c $V/tccpp.c $V/tccgen.c $V/tccasm.c $V/tccelf.c $V/x86_64-gen.c $V/x86_64-link.c $V/i386-asm.c $W/stubs.c $W/tcc-main.c"
cc $CFLAGS $SRCS -o "$W/tcc" -lm -ldl
mkdir -p "$W/include" && cp "$V"/include/*.h "$W/include/"

# Runtime support archive: keep-set lib sources only, compiled by the reference tcc itself.
mkdir -p "$W/libobj"
for f in libtcc1.c stdatomic.c builtin.c va_list.c dsohandle.c; do
  "$W/tcc" -B"$W" -c "$V/lib/$f" -o "$W/libobj/${f%.c}.o"
done
for f in alloca.S alloca-bt.S atomic.S; do
  "$W/tcc" -B"$W" -c "$V/lib/$f" -o "$W/libobj/${f%.S}.o"
done
"$W/tcc" -B"$W" -ar rcs "$W/libtcc1.a" "$W"/libobj/*.o

# u8 patch probe (same as gdcc's build script)
echo 'const char *s = u8"patch-probe"; int main(void) { return s[0] != 112 || s[5] != 45; }' > "$W/.u8-probe.c"
"$W/tcc" -B"$W" -std=c11 -o "$W/.u8-probe" "$W/.u8-probe.c"
"$W/.u8-probe"
rm -f "$W/.u8-probe" "$W/.u8-probe.c"
echo "OK: minimal keep-set tcc built at $W/tcc"
