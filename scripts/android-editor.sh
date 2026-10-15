#!/usr/bin/env bash
# GodotActivity.kt (4.5.2): getStringArrayExtra("command_line_params").
# OS_Android::open_dynamic_library copies external-storage libraries to internal cache.
set -euxo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build/android"
abi="${APK_ABI:-arm64-v8a}"
lib="${LIBPROBE_SO:?Provide the TinyCC-produced libprobe.so}"
pkg=org.godotengine.editor.v4
project=/sdcard/probe-env
mkdir -p "$out" "$root/android/project/.godot"
exec > >(tee "$out/editor.log") 2>&1
adb shell getprop > "$out/device.txt"
adb shell getconf PAGE_SIZE >> "$out/device.txt"
apk="$out/Godot_v4.5.2-stable_android_editor.apk"
if [[ ! -s "$apk" ]]; then
    curl -fL --retry 3 https://github.com/godotengine/godot-builds/releases/download/4.5.2-stable/Godot_v4.5.2-stable_android_editor.apk -o "$apk"
fi
adb install -r -g --abi "$abi" "$apk"
adb shell appops set --uid "$pkg" MANAGE_EXTERNAL_STORAGE allow
adb shell dumpsys package "$pkg" > "$out/dumpsys-package.txt"
cp "$lib" "$root/android/project/bin/libprobe.so"
if [[ "${DEVICE_COMPILE:-0}" = 1 ]]; then
    cp "$(dirname "$lib")/libbootstrap.so" "$root/android/project/bin/libprobe.so"
    cp "$(dirname "$lib")/libcoroutine.so" "$(dirname "$lib")/libhrx.so" "$root/android/project/bin/"
    cp -R "$(dirname "$lib")/bundle" "$root/android/project/"
    cp -R "$root/fixture" "$root/android/project/"
fi
cp "$root/android/project/extension_list.cfg" "$root/android/project/.godot/extension_list.cfg"
adb shell rm -rf "$project"
adb push "$root/android/project" /sdcard/probe-env
adb logcat -c
adb shell am start -S -n "$pkg/org.godotengine.editor.GodotEditor" \
    --esa command_line_params "--editor,--path,$project,--rendering-method,gl_compatibility,--verbose" \
    | tee "$out/am-start.txt"
result=1
for ((i=0; i<120; i++)); do
    adb logcat -d > "$out/logcat.txt"
    if grep -q 'ANDROID_EDITOR_RESULT::PASS' "$out/logcat.txt"; then result=0; break; fi
    if grep -Eq 'ANDROID_EDITOR_RESULT::FAIL|Fatal signal|FATAL EXCEPTION' "$out/logcat.txt"; then break; fi
    sleep 3
done
adb shell dumpsys activity activities > "$out/dumpsys-activity.txt"
adb shell run-as "$pkg" ls -laR cache > "$out/cache-paths.txt" 2>&1 || true
grep -E 'GDCC_|ANDROID_EDITOR_|Copying library|Opening library|dlopen|Fatal signal|ERROR:' "$out/logcat.txt" || true
adb shell am force-stop "$pkg"
test "$result" = 0
if [[ "${DEVICE_COMPILE:-0}" = 1 ]]; then
    grep 'ANDROID_DEVICE_COMPILE_PASS' "$out/logcat.txt"
    grep 'ANDROID_HELPER_PASS::coroutine::32' "$out/logcat.txt"
    grep 'ANDROID_HELPER_PASS::hrx::1' "$out/logcat.txt"
fi
printf 'ANDROID_EDITOR_PASS::%s\n' "$abi"
