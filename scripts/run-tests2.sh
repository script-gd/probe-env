#!/usr/bin/env bash
# Runs the vendored tinycc tests2 suite in NORUN mode (compile to exe, execute, diff output)
# against a given tcc binary, replicating the upstream tests2/Makefile semantics closely:
# per-test FLAGS/ARGS, multi-TU tests, -dt multi-snippet replication via -D, TLS/linker
# interop tests via the system cc. Prints one line per test: PASS/FAIL/SKIP <name>.
#
# usage: run-tests2.sh <tcc-binary> <B-root> [out-dir]
set -uo pipefail

TCC="$1"; BROOT="$2"; OUT="${3:-tmp/tests2-out}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tests/tinycc/tests2"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -r "$SRC" "$OUT/tests2"   # tree copy: ../tests2/... self-includes resolve inside OUT
TCC="$(cd "$(dirname "$TCC")" && pwd)/$(basename "$TCC")"
BROOT="$(cd "$BROOT" && pwd)"
W="$OUT/tests2"

# Out-of-scope skips for a compile+link-only x86_64 ELF port
# (other-arch asm, bounds/backtrace/bcheck, ms-bitfields, upstream-known-broken).
SKIP=(
  98_al_ax_extend 99_fastcall            # i386 only
  138_arm64_encoding 139_arm64_errors 140_arm64_extasm 141_riscv_asm 145_winarm64_interlocked
  112_backtrace 113_btdll 114_bound_signal 115_bound_setjmp 116_bound_setjmp2 117_builtins 126_bound_global 132_bound_test
  95_bitfields_ms                        # needs MSVC
  34_array_assignment                    # upstream skips: not in C standard
  128_run_atexit                         # tests -run's own atexit/on_exit emulation (tccrun.c), not real exe semantics
)
is_skip() { for s in "${SKIP[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

# -dt replication support: distinct test_* macro names in textual order of "defined test_..."
dt_macros() { grep -o 'defined[ ]*( *[A-Za-z0-9_]*\|defined  *[A-Za-z0-9_]*' "$1" | grep -o 'test_[A-Za-z0-9_]*' | awk '!seen[$0]++'; }
is_dt() { grep -q 'defined  *(\? *test_' "$1"; }

pass=0; fail=0; failed=()
for c in "$W"/??_*.c "$W"/???_*.c; do
  name="$(basename "${c%.c}")"
  exp="$SRC/$name.expect"
  [ -f "$exp" ] || continue
  if is_skip "$name"; then echo "SKIP $name"; continue; fi
  rm -f "$W/$name.exe" "$W/$name.o"
  out="$OUT/$name.out"; : > "$out"

  case "$name" in
    146_tls_extern)
      # two TUs from the same file, linked by the system cc (TLS interop gate)
      if ( cd "$W" && \
           "$TCC" -B"$BROOT" -std=c11 -c "$name.c" -o main-$name.o && \
           "$TCC" -B"$BROOT" -std=c11 -c "$name.c" -DDEFS -o defs-$name.o && \
           cc -no-pie -Wl,-z,noexecstack main-$name.o defs-$name.o -o $name.exe && ./$name.exe ) >"$out" 2>&1 \
         && diff -bBq "$exp" "$out" >/dev/null 2>&1; then
        echo "PASS $name"; pass=$((pass+1)); continue
      fi
      echo "FAIL $name"; fail=$((fail+1)); failed+=("$name"); continue
      ;;
    148_linker_symbols)
      # ELF linker feature matrix: 6 TEST variants, .o + .so + exe per variant
      ( cd "$W" && ok=1 && \
        for T in 1 2 3 4 5 6; do
          echo; echo "--- TEST $T ---"
          "$TCC" -B"$BROOT" "$name.c" -DTEST=$((T+100)) -c -fcommon -o t$T.o && \
          "$TCC" -B"$BROOT" "$name.c" -DTEST=$((T+200)) -shared -o t$T.so -fPIC && \
          "$TCC" -B"$BROOT" "$name.c" -DTEST=$T -Wl,-rpath=. -o t$T.exe t$T.o t$T.so -no-pie && \
          ./t$T.exe || { ok=0; break; }
        done; [ $ok = 1 ] ) >"$out" 2>&1
      ;;
    *)
      if is_dt "$c"; then
        # -dt replication (tccpp.c:1294-1307, tccrun.c:231-233+270):
        # one compile per test_* macro with [macro] markers; rounds without main() are
        # silently skipped; a nonzero program exit prints [returns N].
        mapfile -t macs < <(dt_macros "$c")
        ( cd "$W" && first=1 && for m in "${macs[@]}"; do
            [ $first = 1 ] && first=0 || echo
            echo "[$m]"
            cmsg=$("$TCC" -B"$BROOT" -std=c11 -D"$m=1" "$name.c" -o "$name.exe" 2>&1)
            crc=$?
            # a round whose only problem is the missing main link error is silent
            cmsg=$(printf '%s\n' "$cmsg" | grep -v "unresolved reference to 'main'")
            [ -n "$cmsg" ] && printf '%s\n' "$cmsg"
            if [ $crc -eq 0 ] && [ -x "$name.exe" ]; then
              ./$name.exe 2>&1
              rrc=$?
              [ $rrc -ne 0 ] && echo "[returns $rrc]"
            fi
          done ) > "$out" 2>&1
      else
        args=(); extra=()
        case "$name" in
          31_args) args=(arg1 arg2 arg3 arg4 arg5);;
          46_grep) args=('[^* ]*[:a:d: ]+\:\*-/: $' "$name.c");;
          76_dollars_in_identifiers) extra+=(-fdollars-in-identifiers);;
          22_floating_point|24_math_library) extra+=(-lm);;
          104_inline) extra+=("104+_inline.c");;
          120_alias) extra+=("120+_alias.c");;
          144_tls) extra+=(-pthread);;
        esac
        ( cd "$W" && "$TCC" -B"$BROOT" -std=c11 "${extra[@]}" "$name.c" -o "$name.exe" 2>&1 && \
          ./"$name.exe" "${args[@]}" 2>&1 ) > "$out" 2>&1
      fi
      ;;
  esac
  if diff -bBq "$exp" "$out" >/dev/null 2>&1; then
    echo "PASS $name"; pass=$((pass+1))
  else
    echo "FAIL $name"; fail=$((fail+1)); failed+=("$name")
  fi
done
echo "---"
echo "PASS=$pass FAIL=$fail"
[ $fail -eq 0 ] || printf 'failed: %s\n' "${failed[@]}"
exit $((fail > 0))
