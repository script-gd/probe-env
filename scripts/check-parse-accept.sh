#!/usr/bin/env bash
# Parse-acceptance gate for the M2 parser: every test in tests/tinycc/tests2-baseline.txt
# must parse cleanly with jtcc (preprocess + C tokenizer + parser, no codegen). Fails on
# regression; reports newly-passing tests (update the baseline when expanding the parser).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p tmp/classes
javac -d tmp/classes jtcc/src/jtcc/*.java jtcc/src/jtcc/*/*.java

W=tmp/parse-acc
rm -rf "$W"; mkdir -p "$W"
cp -r tests/tinycc/tests2 "$W/tests2"

BASELINE="$ROOT/tests/tinycc/tests2-baseline-parse.txt"
pass=0; fail=0; declare -a regress
while read -r name; do
  [ -z "$name" ] && continue
  # parse-acceptance only: single TU, no codegen/link; flags and second TUs not needed
  (cd "$W/tests2" && timeout 30 java -cp "$ROOT/tmp/classes" jtcc.Main cc "$name.c" -I. \
      --sys="$ROOT/tmp/tcc-ref/include" --sys=/usr/include/x86_64-linux-gnu --sys=/usr/include \
      >/dev/null 2>&1)
  if [ $? -eq 0 ]; then pass=$((pass+1)); else fail=$((fail+1)); regress+=("$name"); fi
done < "$BASELINE"
echo "baseline-parse: pass=$pass fail=$fail (baseline size $(grep -c . "$BASELINE"))"
if [ $fail -gt 0 ]; then printf 'regressed: %s\n' "${regress[@]}"; exit 1; fi
echo "OK: no regressions vs baseline"
