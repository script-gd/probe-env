#!/usr/bin/env bash
set -euxo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
abi="${ABI:-arm64-v8a}"
api="${ANDROID_API:-28}"
ndk="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.1.13356709}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
sysroot="$toolchain/sysroot"
case "$abi" in
    arm64-v8a) cpu=arm64; triple=aarch64-linux-android ;;
    x86_64) cpu=x86_64; triple=x86_64-linux-android ;;
    *) exit 2 ;;
esac
out="$root/build/android/$abi"
mkdir -p "$out/host" "$out/device" "$out/bundle/include" "$out/bundle/lib"
exec > >(tee "$out/build.log") 2>&1
crt="$sysroot/usr/lib/$triple/$api"
common=(--cpu="$cpu" --targetos=Android --config-backtrace=no --config-bcheck=no
    --sysroot="$sysroot/usr" --sysincludepaths="{B}/include:$sysroot/usr/include/$triple:$sysroot/usr/include"
    --libpaths="{B}:$crt:$sysroot/usr/lib/$triple" --crtprefix="$crt"
    --tcc-switches=-Wl,-section-alignment=0x4000)
pushd "$out/host"
"$root/vendor/tinycc/configure" "${common[@]}" --cc=cc --enable-static
make -j2 tcc libtcc.a libtcc1.a
mkdir -p "$out/host/include"
cp -R "$root/vendor/tinycc/include/." "$out/host/include/"
popd

# Exercise the same libtcc API as gdcc, with an Android target fixed at build time.
cc -I"$root/vendor/tinycc" "$root/probes/android_compile.c" "$out/host/libtcc.a" -lm -ldl -lpthread -o "$out/compile"
"$out/compile" "$out/host" "$root/fixture" "$out/libprobe.so"

# c2str runs on the build host; libtcc1 above is produced by host-runnable cross TCC.
pushd "$out/device"
"$root/vendor/tinycc/configure" "${common[@]}" --cc="$toolchain/bin/${triple}${api}-clang" \
    --sysroot= --sysincludepaths="{B}/include:{B}/include/$triple" \
    --libpaths='{B}/lib:{B}' --crtprefix='{B}/lib' \
    --disable-static --extra-ldflags=-Wl,-z,max-page-size=16384
cc -DC2STR "$root/vendor/tinycc/conftest.c" -o c2str.exe
make -j2 tcc libtcc.so libtcc.a LIBS='-lm -ldl'
popd
"$toolchain/bin/${triple}${api}-clang" -I"$root/vendor/tinycc" \
    "$root/probes/android_compile.c" -L"$out/device" -ltcc -llog -Wl,-rpath,'$ORIGIN' \
    -Wl,-z,max-page-size=16384 -o "$out/device/compile"
"$toolchain/bin/${triple}${api}-clang" -shared -fPIC -DANDROID_BOOTSTRAP \
    -I"$root/vendor/tinycc" -I"$root/fixture/include/godot" \
    "$root/probes/android_bootstrap.c" "$root/probes/android_compile.c" "$out/device/libtcc.a" \
    -lm -ldl -llog -Wl,-z,max-page-size=16384 -o "$out/libbootstrap.so"
"$toolchain/bin/${triple}${api}-clang" -shared -fPIC -DANDROID_BOOTSTRAP \
    -I"$root/vendor/tinycc" -I"$root/fixture/include/godot" \
    "$root/probes/java25_jni_bridge.c" "$root/probes/android_compile.c" "$out/device/libtcc.a" \
    -lm -ldl -llog -Wl,-z,max-page-size=16384 -Wl,-soname,libgdcc_java25_bridge.so \
    -o "$out/libgdcc_java25_bridge.so"
cp "$out/host/libtcc1.a" "$out/bundle/"
cp -R "$sysroot/usr/include/." "$out/bundle/include/"
cp -R "$root/vendor/tinycc/include/." "$out/bundle/include/"
cp "$crt/"{crtbegin_so.o,crtend_so.o,libc.so,libm.so,libdl.so} "$out/bundle/lib/"

# TinyCC, not Clang, compiles the coroutine and executable-thunk regression probes.
tcc=("$out/host/tcc" -B"$out/host" -std=c11 -D__ANDROID_API__="$api" -Wl,-section-alignment=0x4000)
"${tcc[@]}" -shared -I"$root/fixture/include/gdcc" "$root/probes/coroutine.c" \
    "$root/fixture/include/gdcc/minicoro.c" -o "$out/libcoroutine.so"
"${tcc[@]}" -shared -DGDCC_HRX_TEST_HOOKS -I"$root/fixture/include/gdcc" \
    -I"$root/fixture/include/godot" "$root/probes/hrx.c" \
    "$root/fixture/include/gdcc/gdcc_hrx.c" "$root/fixture/include/godot/godot_binding.c" -o "$out/libhrx.so"
"$toolchain/bin/${triple}${api}-clang" "$root/probes/load.c" -ldl \
    -Wl,-z,max-page-size=16384 -o "$out/device/load"
"$toolchain/bin/llvm-readelf" -h -l -d -r -s "$out/libprobe.so" > "$out/elf.txt"
"$toolchain/bin/llvm-readelf" -l "$out/libcoroutine.so" > "$out/coroutine-segments.txt"
if grep -Eq 'TEXTREL|NEEDED.*(libc\.so\.6|libpthread|ld-linux)' "$out/elf.txt"; then exit 1; fi
if grep -q ' TLS ' "$out/coroutine-segments.txt"; then exit 1; fi
"$toolchain/bin/llvm-readelf" -l "$out/libprobe.so" | grep LOAD
while IFS= read -r line; do
    if [[ "$line" == *LOAD* ]]; then
        align="${line##* }"
        test "$((align))" -ge 16384
    fi
done < <("$toolchain/bin/llvm-readelf" -l "$out/libprobe.so")
grep 'gdextension_entry' "$out/elf.txt"
sha256sum "$out/libprobe.so" > "$out/libprobe.sha256"
printf 'ANDROID_TCC_BUILD_PASS::%s\n' "$abi"
