# Source Provenance

TinyCC is exported from gdcc commit
`13035f94751dbb1b54205040ff4681cea0d2167d` (branch `feat/backend/tinycc`).
TinyCC upstream pin: `43c7708b85681a2fd4451c8a541af4494a8919b2`.
The gdcc `u8` patch is preserved. Upstream licenses are preserved in the export.

The Android branch reuses this export without compiler-source changes. Android
NDK r28b headers, API-28 CRT/stubs, and target `libtcc1.a` are staged by
`scripts/android-build.sh`; they are not vendored. See [ANDROID_REPORT.md](../ANDROID_REPORT.md)
for the verified Android scope and the fixture's broader pthread-TLS fallback
compared with current gdcc on x86_64. The ARM64 fallback has the same semantics.

The complete generated C fixture comes from gdcc's
`src/test/test_suite/unit_test/script/string_format/variant_operand.gd`.
See [REPORT.md](../REPORT.md) for local experimental modifications and validation scope.

## Source pruning

`vendor/tinycc` is pruned to the sources needed for the local sanity builds
only: native Linux x86_64, and native Linux / macOS ARM64 (`TCC_TARGET_X86_64`
and `TCC_TARGET_ARM64`). No compiler source was changed. Every remaining
reference to a removed file stays behind a `#ifdef TCC_TARGET_*` guard
(`libtcc.c`, `tcc.h`, `tcctok.h`) or in cross/win32-only Makefile variables, so
the native `tcc`, `libtcc` and `libtcc1` targets resolve only to guarded,
retained root files. `i386-asm.c` / `i386-asm.h` / `i386-tok.h` are kept
because the x86_64 backend shares them; the x86_64 and arm64 sources are kept
in full.

Removed: the entire `win32/` subtree; the 32-bit i386, ARM, C67 and RISC-V64
backends; the COFF/PE writers (`tcccoff.c`, `coff.h`, `tccpe.c`); and the
runtime objects only those targets use (`lib/armeabi.c`, `lib/pic86.S`,
`lib/bt-dll.c`).

Because those targets are gone, `make cross`, building `all` beyond the native
target, and `make test` are not supported from this tree. Only the native
compiler/library path (`make tcc libtcc.a|libtcc.so|libtcc.dylib libtcc1.a`) is
exercised.

## Godot / gdcc helpers

The generated Godot C helpers (`fixture/include/gdcc`, `fixture/include/godot`)
were generated in the earlier investigation from a local gdcc jar rather than
copied from the pinned gdcc commit. Compared with gdcc `3136c451`, `gdcc_call.h`
and `gdcc_hrx.c` differ only in comments; `minicoro.h` also applies the pthread-key
TLS fallback to all TinyCC POSIX targets, whereas current gdcc limits it to ARM64.
The ARM64 behavior is equivalent. See the Android report for why the broader
x86_64 fallback matters on Android. The gdcc-specific minicoro patches remain
inline in `fixture/include/gdcc/minicoro.h`.
