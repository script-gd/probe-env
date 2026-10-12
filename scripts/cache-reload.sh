#!/usr/bin/env bash
set -euxo pipefail
root="$(pwd)"
os="$(uname -s)"
suffix=so
if [[ "$os" == Darwin ]]; then suffix=dylib; fi
work="$(mktemp -d "$root/build/cache-suite.XXXXXX")"
java --enable-native-access=ALL-UNNAMED probes/CacheCompile.java \
    "$root/build/tcc" "$root/fixture" "$work" \
    "$root/probes/hrx/reload_generation.c" "$root/probes/hrx/reload_api.h" > build/object-cache.log 2>&1
cat build/object-cache.log
grep -q '^CACHE_SUITE_PASS$' build/object-cache.log
file "$work"/tcc-obj/*.o > build/object-formats.log

# Each output was linked from OBJ files in a fresh state, including self-heal retries.
for round in cold warm changed adjacent include repaired recovered after-error; do
    cp "$work/outputs/$round.$suffix" "godot/bin/libprobe.$suffix"
    "$GODOT_BIN" --headless --path godot --script run.gd > "build/godot-cache-$round.log" 2>&1
    cat "build/godot-cache-$round.log"
    grep -q '^GDCC_PROJECT_PASS$' "build/godot-cache-$round.log"
    echo "CACHE_GODOT_PASS::$round"
done

cp "$work/outputs/reload-gen1.$suffix" "build/libreload_gen1.$suffix"
cp "$work/outputs/reload-gen2.$suffix" "build/libreload_gen2.$suffix"
cp "$work/outputs/reload-no-rebind.$suffix" "build/libreload_no_rebind.$suffix"
cc -O2 -Ifixture/include/gdcc -Ifixture/include/godot \
    probes/hrx/reload_host.c -ldl -o build/hrx_reload
build/hrx_reload "$root/build/libreload_gen1.$suffix" "$root/build/libreload_gen2.$suffix" \
    > build/hrx-reload.log 2>&1
cat build/hrx-reload.log
grep -q '^RELOAD_PASS$' build/hrx-reload.log
set +e
build/hrx_reload "$root/build/libreload_gen1.$suffix" "$root/build/libreload_no_rebind.$suffix" \
    > build/hrx-reload-negative.log 2>&1
status=$?
set -e
test "$status" -eq 1
grep -q '^FAIL matching identity must rebind compatibly$' build/hrx-reload-negative.log
echo 'RELOAD_NEGATIVE_CONTROL_PASS'
