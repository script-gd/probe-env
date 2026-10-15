#!/usr/bin/env bash
set -euxo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build/java25"
r8_version=9.5.23
r8_sha256=2135549ea5b9fd875dd00192a1c1b053b16a3d4ac76e78bdfcd452adfd4a89cb
mkdir -p "$out/classes" "$out/dex"
javac --release 25 -d "$out/classes" "$root/scripts/java25/src/gdcc/probe/Java25TccProbe.java"
javap -verbose "$out/classes/gdcc/probe/Java25TccProbe.class" | grep 'major version: 69'
jar --create --file "$out/java25-probe-classfile.jar" -C "$out/classes" .
if [[ ! -s "$out/r8.jar" ]]; then
    curl -fL --retry 3 "https://storage.googleapis.com/r8-releases/raw/$r8_version/r8.jar" -o "$out/r8.jar"
fi
printf '%s  %s\n' "$r8_sha256" "$out/r8.jar" | sha256sum --check --status
java -cp "$out/r8.jar" com.android.tools.r8.D8 --min-api 24 --output "$out/dex" \
    "$out/java25-probe-classfile.jar"
jar --create --file "$out/java25-probe-dex.jar" -C "$out/dex" classes.dex
jar --list --file "$out/java25-probe-dex.jar" | grep '^classes.dex$'
