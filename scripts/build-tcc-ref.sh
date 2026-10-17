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

# Debug/eh-frame/coverage hooks stubbed out (tccdbg.c deleted, as the Java port will do).
cat > "$W/stubs.c" <<'EOF'
#include "tcc.h"
ST_FUNC void tcc_debug_new(TCCState *s) {}
ST_FUNC void tcc_debug_start(TCCState *s1) {}
ST_FUNC void tcc_debug_end(TCCState *s1) {}
ST_FUNC void tcc_debug_bincl(TCCState *s1) {}
ST_FUNC void tcc_debug_eincl(TCCState *s1) {}
ST_FUNC void tcc_debug_newfile(TCCState *s1) {}
ST_FUNC void tcc_debug_line(TCCState *s1) {}
ST_FUNC void tcc_add_debug_info(TCCState *s1, Sym *s, Sym *e) {}
ST_FUNC void tcc_debug_funcstart(TCCState *s1, Sym *sym) {}
ST_FUNC void tcc_debug_prolog_epilog(TCCState *s1, int value) {}
ST_FUNC void tcc_debug_funcend(TCCState *s1, int size) {}
ST_FUNC void tcc_debug_extern_sym(TCCState *s1, Sym *sym, int sh_num, int sym_bind, int sym_type) {}
ST_FUNC void tcc_debug_typedef(TCCState *s1, Sym *sym) {}
ST_FUNC void tcc_debug_stabn(TCCState *s1, int type, int value) {}
ST_FUNC void tcc_debug_fix_forw(TCCState *s1, CType *t) {}
ST_FUNC void tcc_eh_frame_start(TCCState *s1) {}
ST_FUNC void tcc_eh_frame_end(TCCState *s1) {}
ST_FUNC void tcc_eh_frame_hdr(TCCState *s1, int final) {}
ST_FUNC void tcc_tcov_start(TCCState *s1) {}
ST_FUNC void tcc_tcov_end(TCCState *s1) {}
ST_FUNC void tcc_tcov_check_line(TCCState *s1, int start) {}
ST_FUNC void tcc_tcov_block_end(TCCState *s1, int line) {}
ST_FUNC void tcc_tcov_block_begin(TCCState *s1) {}
ST_FUNC void tcc_tcov_reset_ind(TCCState *s1) {}
ST_FUNC void tcc_run_free(TCCState *s1) {}
LIBTCCAPI int tcc_run(TCCState *s, int argc, char **argv) { return -1; }
EOF

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
