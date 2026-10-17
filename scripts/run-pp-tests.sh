#!/usr/bin/env bash
# Runs the vendored tinycc pp (preprocessor) tests against either the reference tcc
# (`tcc -E -P`) or the Java jtcc preprocessor (`jtcc pp-text`).
#
# usage: run-pp-tests.sh tcc <tcc-binary> <B-root> | run-pp-tests.sh jtcc <classes-dir> <B-root> [out-dir]
set -uo pipefail

MODE="$1"; BIN="$2"; BROOT="$3"; OUT="${4:-tmp/pp-out}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tests/tinycc/pp"
rm -rf "$OUT"; mkdir -p "$OUT"
# absolute paths: both modes run with cwd=$SRC so diagnostics carry bare basenames
BIN="$(cd "$(dirname "$BIN")" && pwd)/$(basename "$BIN")"
BROOT="$(cd "$BROOT" && pwd)"

pass=0; fail=0; failed=()
for c in "$SRC"/*.c "$SRC"/*.S; do
  [ -f "$c" ] || continue
  name="$(basename "$c")"; name="${name%.*}"
  exp="$SRC/$name.expect"
  [ -f "$exp" ] || continue
  # .S tests exercise the assembler-mode preprocessor (PARSE_FLAG_ASM_FILE) — a later
  # phase concern for jtcc; the reference tcc runs them in the baseline.
  if [ "$MODE" = jtcc ] && [ "${c##*.}" = "S" ]; then echo "SKIP $name (asm-mode)"; continue; fi
  if [ "$MODE" = tcc ]; then
    (cd "$SRC" && "$BIN" -B"$BROOT" -E -P "$name.${c##*.}") > "$OUT/$name.output" 2>&1 || true
  else
    (cd "$SRC" && java -cp "$BIN" jtcc.Main pptext "$name.${c##*.}" --sys="$BROOT/include" --sys=/usr/include) > "$OUT/$name.output" 2>&1 || true
  fi
  # upstream diff opts: -b default, -w for 02, -B for 16
  opts=(-bB)
  [ "$name" = "02" ] && opts=(-wB)
  if diff "${opts[@]}" "$exp" "$OUT/$name.output" >/dev/null 2>&1; then
    echo "PASS $name"; pass=$((pass+1))
  else
    echo "FAIL $name"; fail=$((fail+1)); failed+=("$name")
  fi
done
echo "---"
echo "PASS=$pass FAIL=$fail"
[ $fail -eq 0 ] || printf 'failed: %s\n' "${failed[@]}"
exit $((fail > 0))
