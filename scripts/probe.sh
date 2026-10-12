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
build/tcc/tcc -Bbuild/tcc -std=c11 -Ifixture/include/gdcc \
    probes/coroutine.c fixture/include/gdcc/minicoro.c -lpthread -o build/coroutine
build/coroutine
