# Source Provenance

TinyCC and the Godot 4.5.1 C helpers are exported from gdcc commit
`13035f94751dbb1b54205040ff4681cea0d2167d` (branch `feat/backend/tinycc`).
TinyCC upstream pin: `43c7708b85681a2fd4451c8a541af4494a8919b2`.
The gdcc `u8` patch is preserved. Upstream licenses are preserved in the export.

The complete generated C fixture comes from gdcc's
`src/test/test_suite/unit_test/script/string_format/variant_operand.gd`.
See `REPORT.md` for local experimental modifications and validation scope.

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
were freshly generated from a local gdcc jar rather than copied from the pinned
gdcc commit, with one comment-only divergence in `gdcc_call.h` relative to the
current gdcc source. The gdcc-specific patches to the vendored minicoro are
kept inline in `fixture/include/gdcc/minicoro.h` (search for `gdcc patch`).
