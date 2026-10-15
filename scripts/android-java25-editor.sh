#!/usr/bin/env bash
set -euxo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
abi=x86_64
pkg=org.godotengine.editor.v4
project=/sdcard/probe-env
out="$root/build/android/$abi"
stage="$root/build/android/java25-project"
jar="$root/build/java25/java25-probe-dex.jar"
mkdir -p "$out"
test -s "$jar"
test -s "$out/libprobe.so"
test -s "$out/libgdcc_java25_bridge.so"
rm -rf "$stage"
mkdir -p "$stage"
cp -R "$root/android/project/." "$stage/"
rm -rf "$stage/bin" "$stage/bundle" "$stage/fixture" "$stage/java25"
mkdir -p "$stage/bin" "$stage/.godot" "$stage/java25"
cp "$root/android/project/extension_list.cfg" "$stage/.godot/extension_list.cfg"
cp "$out/libprobe.so" "$stage/bin/libprobe.so"
cp "$out/libgdcc_java25_bridge.so" "$stage/java25/"
cp "$jar" "$stage/java25/java25-probe-dex.jar"
cp -R "$out/bundle" "$stage/bundle"
mkdir -p "$stage/fixture/include"
cp "$root/fixture/entry.c" "$root/fixture/entry.h" "$root/fixture/object_fat_ptr_types.h" \
    "$root/fixture/engine_method_binds.h" "$stage/fixture/"
cp -R "$root/fixture/include/gdcc" "$root/fixture/include/godot" "$stage/fixture/include/"
adb shell appops set --uid "$pkg" MANAGE_EXTERNAL_STORAGE allow
adb shell rm -rf "$project"
adb push "$stage" "$project"
adb logcat -c
adb shell am start -S -n "$pkg/org.godotengine.editor.GodotEditor" \
    --esa command_line_params "--editor,--path,$project,--rendering-method,gl_compatibility,--verbose"
result=1
for ((i=0; i<180; i++)); do
    adb logcat -d > "$out/java25-logcat.txt"
    if grep -q 'JAVA25_ART_RELOAD_PASS' "$out/java25-logcat.txt"; then result=0; break; fi
    if grep -Eq 'ANDROID_EDITOR_RESULT::FAIL|JAVA25_GDEXTENSION_RELOAD_STATUS::[1-9]|Fatal signal|FATAL EXCEPTION|SCRIPT ERROR|Failed to load script' \
            "$out/java25-logcat.txt"; then break; fi
    sleep 2
done
adb shell dumpsys activity activities > "$out/java25-activity.txt"
adb shell run-as "$pkg" ls -laR cache > "$out/java25-cache.txt" 2>&1 || true
grep -E 'JAVA25_|ANDROID_EDITOR_RESULT::|ANDROID_LIBTCC_PROJECT_PASS|TU |GDExtension' \
    "$out/java25-logcat.txt" || true
adb shell am force-stop "$pkg"
test "$result" = 0
grep -q 'JAVA25_GDEXTENSION_RELOAD_STATUS::0' "$out/java25-logcat.txt"
grep -q 'JAVA25_GDCC_CASE_PASS::java25=42' "$out/java25-logcat.txt"
printf 'JAVA25_ANDROID_EDITOR_HOT_RELOAD_PASS\n'
