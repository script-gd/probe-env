# Android ARM64 TinyCC / gdcc Probe

Standalone investigation of compiling a complete gdcc-generated C project with
gdcc's pinned TinyCC and running it in the official Godot Android editor.

**Result:** Android ARM64 and x86_64 pass both desktop cross-compilation and
in-app libtcc compilation, real editor-plugin assertions, coroutine TLS, and
executable-thunk probes. ARM64 executes through Android's native bridge on an
x86_64 emulator, not on physical ARM hardware. Actual 16 KB page devices and the
production Java backend remain outside the verified scope.

- [Android Report (中文): results, required changes, pitfalls, and evidence](ANDROID_REPORT.md)
- [Successful Android CI](https://github.com/script-gd/probe-env/actions/runs/38735984841)
- [Android Workflow](.github/workflows/android.yml)
- [Toolchain and Full-Project Build](scripts/android-build.sh)
- [Official Android Editor Test](scripts/android-editor.sh)
- [Source Provenance](vendor/README.md)

The original pruned compiler, complete generated fixture, and Linux/macOS probes
are retained. No full gdcc checkout or local Android SDK installation is required
to run the workflow. The Android fixture itself is compiled by TinyCC, never by a
silent Clang fallback. NDK Clang builds only the compiler and test harness.

## Reproduce

Run the Android workflow, or use an existing Linux x86_64 NDK installation and an
adb-connected Android device. The script replaces `/sdcard/probe-env` and installs
the official Godot editor APK; do not use that directory for real projects.

```bash
ABI=arm64-v8a ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28b \
  bash scripts/android-build.sh
APK_ABI=arm64-v8a LIBPROBE_SO="$PWD/build/android/arm64-v8a/libprobe.so" \
  bash scripts/android-editor.sh
APK_ABI=arm64-v8a DEVICE_COMPILE=1 \
  LIBPROBE_SO="$PWD/build/android/arm64-v8a/libprobe.so" \
  bash scripts/android-editor.sh
```

See the report for the pinned API, bundle layout, emulator limitations, and the
distinction between this native API experiment and gdcc's Java 25/FFM integration.

## Earlier Research

- [Linux/macOS ARM64 Report](REPORT.md)
- [Native Workflow](.github/workflows/arm64.yml)
- [Original Full Suite](scripts/probe.sh)
- [Object Cache and Cross-DSO Reload Suite](scripts/cache-reload.sh)

These earlier results are historical platform-specific evidence, not substitutes
for the Android tests. On an appropriate native host they can still be run with
`GODOT_BIN=/absolute/path/to/Godot bash scripts/probe.sh`.
