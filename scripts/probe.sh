#!/usr/bin/env bash
set -euxo pipefail
root="$(pwd)"
mkdir -p build/tcc godot/bin godot/.godot
exec > >(tee build/probe.log) 2>&1
cp -R vendor/tinycc/. build/tcc/
os="$(uname -s)"
suffix=so
if [[ "$os" == Darwin ]]; then suffix=dylib; fi
pushd build/tcc
./configure --disable-static --libdir="$PWD"
make -j2 tcc "libtcc.$suffix" libtcc1.a
popd
file "build/tcc/libtcc.$suffix"
cc -Ivendor/tinycc probes/compile.c -Lbuild/tcc -ltcc \
    -Wl,-rpath,"$root/build/tcc" -o build/compile
build/compile "$root/build/tcc" "$root/fixture/include/gdcc" \
    "$root/fixture/include/godot" "$root/godot/bin/libprobe.$suffix" \
    "$root/fixture/entry.c" "$root/fixture/include/godot/godot_binding.c" \
    "$root/fixture/include/gdcc/minicoro.c" \
    "$root/fixture/include/gdcc/gdcc_coroutine.c" \
    "$root/fixture/include/gdcc/gdcc_hrx.c"
file "godot/bin/libprobe.$suffix"
if [[ "$os" == Darwin ]]; then codesign --verify --verbose godot/bin/libprobe.dylib; fi
cp godot/extension_list.cfg godot/.godot/extension_list.cfg
"$GODOT_BIN" --headless --path godot --script run.gd > build/godot.log 2>&1
cat build/godot.log
grep -q '^GDCC_PROJECT_PASS$' build/godot.log
compile_ffm() {
    java --enable-native-access=ALL-UNNAMED probes/Compile.java \
    "$root/build/tcc" "$root/fixture/include/gdcc" "$root/fixture/include/godot" \
    "$root/godot/bin/libprobe.$suffix" "$root/fixture/entry.c" \
    "$root/fixture/include/godot/godot_binding.c" "$root/fixture/include/gdcc/minicoro.c" \
    "$root/fixture/include/gdcc/gdcc_coroutine.c" "$root/fixture/include/gdcc/gdcc_hrx.c"
}
if [[ "$os" == Darwin ]]; then
    otool -hv build/tcc/libtcc.dylib > build/namespace-flat.log
    set +e
    DYLD_PRINT_BINDINGS=1 compile_ffm > build/ffm-flat.log 2>&1
    flat_status=$?
    set -e
    echo "FLAT_NAMESPACE_FFM_STATUS::$flat_status"
    test "$flat_status" -ne 0
    # Command-line LDFLAGS overrides upstream's += -flat_namespace for the host dylib.
    make -C build/tcc -B -j2 LDFLAGS=-Wl,-twolevel_namespace tcc libtcc.dylib
    otool -hv build/tcc/libtcc.dylib > build/namespace-twolevel.log
fi
compile_ffm
"$GODOT_BIN" --headless --path godot --script run.gd > build/godot-ffm.log 2>&1
cat build/godot-ffm.log
grep -q '^GDCC_PROJECT_PASS$' build/godot-ffm.log
build/tcc/tcc -Bbuild/tcc -std=c11 -Ifixture/include/gdcc \
    probes/coroutine.c fixture/include/gdcc/minicoro.c -lpthread -o build/coroutine
build/coroutine
cc probes/load.c -ldl -o build/load
build/tcc/tcc -Bbuild/tcc -std=c11 -shared -Ifixture/include/gdcc \
    probes/coroutine.c fixture/include/gdcc/minicoro.c -lpthread -o "build/libcoroutine.$suffix"
if [[ "$os" == Linux ]]; then
    readelf -lW "build/libcoroutine.$suffix" > build/coroutine-segments.log
    if grep -q ' TLS ' build/coroutine-segments.log; then exit 1; fi
fi
build/load "$root/build/libcoroutine.$suffix" 1100 > build/reload.log 2>&1
grep '^DLOPEN_ROUNDS_PASS' build/reload.log
build/tcc/tcc -Bbuild/tcc -std=c11 -shared probes/tls_negative.c -o "build/libtls_negative.$suffix"
set +e
build/load "$root/build/libtls_negative.$suffix" > build/tls-negative.log 2>&1
tls_status=$?
set -e
echo "UNPATCHED_TLS_STATUS::$tls_status"
test "$tls_status" -ne 0
if [[ "$os" == Darwin ]]; then
    build/tcc/tcc -Bbuild/tcc -std=c11 probes/cache_negative.c -o build/cache_negative
    set +e
    build/cache_negative > build/cache-negative.log 2>&1
    cache_status=$?
    set -e
    echo "RAW_CACHE_STATUS::$cache_status"
    test "$cache_status" -ne 0
fi
build/tcc/tcc -Bbuild/tcc -std=c11 -DGDCC_HRX_TEST_HOOKS \
    -Ifixture/include/gdcc -Ifixture/include/godot probes/hrx.c \
    fixture/include/gdcc/gdcc_hrx.c fixture/include/godot/godot_binding.c \
    -o build/hrx
build/hrx
bash scripts/cache-reload.sh
