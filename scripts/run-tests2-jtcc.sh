#!/usr/bin/env bash
# M3 gate: compile tests2 cases with jtcc to .o, link with system cc, run, diff vs .expect.
# usage: run-tests2-jtcc.sh [name ...]  (no args = run the M3 gate subset)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tests/tinycc/tests2"
W=tmp/tests2-jtcc
rm -rf "$W"; mkdir -p "$W"
cd "$W"

JAVAC_CP="$ROOT/tmp/classes"
INC=(-I"$SRC" --sys="$ROOT/tmp/tcc-ref/include --sys=/usr/include/x86_64-linux-gnu --sys=/usr/include")

if [ $# -gt 0 ]; then TESTS=("$@"); else
  TESTS=(00_assignment 01_comment 04_for 05_array 06_case 07_function 08_while 09_do_while
         10_pointer 11_precedence 12_hashdefine 13_integer_literals 14_if 15_recursion
         16_nesting 17_enum 19_pointer_arithmetic 20_pointer_comparison 21_char_const
         25_quicksort 26_int_convert 27_sizeof 30_hanoi 31_args 32_led 33_ternary_op
         35_sizeof 36_array_init 37_sprintf 38_multiple_array_index 39_typedef 41_callday
         42_function_pointer 43_struct_init 44_enum 45_for_notstruct 46_grep)
fi

pass=0; fail=0; failed=()
for name in "${TESTS[@]}"; do
  c="$SRC/$name.c"
  [ -f "$c" ] || { echo "MISSING $name"; fail=$((fail+1)); continue; }
  extra=()
  case "$name" in
    31_args) extra=(arg1 arg2 arg3 arg4 arg5);;
    104_inline) extra=();; # multi-TU later
  esac
  if ! timeout 60 java -cp "$JAVAC_CP" jtcc.Main cc "$c" -I"$SRC" --sys="$ROOT/tmp/tcc-ref/include" --sys=/usr/include/x86_64-linux-gnu --sys=/usr/include -o "$name.o" >/dev/null 2>&1; then
    echo "FAIL $name (jtcc compile)"; fail=$((fail+1)); failed+=("$name"); continue
  fi
  if ! cc -no-pie "$name.o" -o "$name.exe" 2>"$name.lderr"; then
    echo "FAIL $name (link: $(head -1 "$name.lderr"))"; fail=$((fail+1)); failed+=("$name"); continue
  fi
  timeout 10 "./$name.exe" "${extra[@]}" > "$name.out" 2>&1
  if diff -bBq "$SRC/$name.expect" "$name.out" >/dev/null 2>&1; then
    echo "PASS $name"; pass=$((pass+1))
  else
    echo "FAIL $name (output)"; fail=$((fail+1)); failed+=("$name")
  fi
done
echo "---"
echo "PASS=$pass FAIL=$fail"
[ $fail -eq 0 ] || printf 'failed: %s\n' "${failed[@]}"
exit $((fail > 0))
