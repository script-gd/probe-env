#!/usr/bin/env bash
# End-to-end validation for the java-tinycc research branch:
#  1. reference tcc (built from the minimal vendor keep-set) compiles the full fixture to a .so
#  2. token-level parity of jtcc (Java preprocessor) vs reference `tcc -E` on every fixture TU
# Must run after scripts/build-tcc-ref.sh. Java sources are compiled on the fly into tmp/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p tmp/classes
javac -d tmp/classes jtcc/src/jtcc/*.java

W="$ROOT/tmp/tcc-ref"
T="$ROOT/fixture"
INC=(-I"$T/include/gdcc" -I"$T/include/godot")

# ---- 1. fixture -> .so with the minimal keep-set compiler ------------------
echo "== compile fixture with minimal-keep-set tcc =="
mkdir -p "$W/obj"
TUS=("$T/entry.c" "$T/include/godot/godot_binding.c" "$T/include/gdcc/minicoro.c" "$T/include/gdcc/gdcc_coroutine.c" "$T/include/gdcc/gdcc_hrx.c")
OBJS=()
for tu in "${TUS[@]}"; do
  o="$W/obj/$(basename "${tu%.c}").o"
  (cd "$W" && ./tcc -B. -std=c11 -I"$T/include/gdcc" -I"$T/include/godot" -c "${tu/#$ROOT/../..}" -o "obj/$(basename "${tu%.c}").o")
  OBJS+=("$o")
done
(cd "$W" && ./tcc -B. -std=c11 -shared -o libprobe.so obj/entry.o obj/godot_binding.o obj/minicoro.o obj/gdcc_coroutine.o obj/gdcc_hrx.o)
file "$W/libprobe.so" | grep -q "ELF 64-bit LSB shared object" && echo "fixture -> libprobe.so OK"

# ---- 2. goldens + token parity ----------------------------------------------
echo "== token parity: jtcc vs tcc -E =="
cd "$W"
REL_T=../../fixture
REL_INC=(-I"$REL_T/include/gdcc" -I"$REL_T/include/godot")
SYS=(--sys=include --sys=/usr/local/include --sys=/usr/include/x86_64-linux-gnu --sys=/usr/include)
declare -A SRC=( [entry]="$REL_T/entry.c" [godot_binding]="$REL_T/include/godot/godot_binding.c" [minicoro]="$REL_T/include/gdcc/minicoro.c" [gdcc_coroutine]="$REL_T/include/gdcc/gdcc_coroutine.c" [gdcc_hrx]="$REL_T/include/gdcc/gdcc_hrx.c" )
mkdir -p gold
for tu in entry godot_binding minicoro gdcc_coroutine gdcc_hrx; do
  ./tcc -B. -std=c11 "${REL_INC[@]}" -E "${SRC[$tu]}" > "gold/$tu.i"
done
for tu in entry godot_binding minicoro gdcc_coroutine gdcc_hrx; do
  java -cp ../classes jtcc.Main pp "${SRC[$tu]}" "${REL_INC[@]}" "${SYS[@]}" > "../jtcc-$tu.tok"
  java -cp ../classes jtcc.Main lex "gold/$tu.i" > "../gold-$tu.tok"
  echo -n "$tu: "
  java -cp ../classes jtcc.Main cmp "../jtcc-$tu.tok" "../gold-$tu.tok"
done
echo "ALL CHECKS PASSED"
