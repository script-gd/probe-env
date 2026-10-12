# Native ARM64 TinyCC Probe

Standalone reproduction using gdcc's pinned TinyCC and a complete gdcc-generated
C project. Native Linux ARM64 and macOS ARM64 runners compile the project via both
the C API and Java 25 FFM, then load and execute it in Godot.

**Result:** both platforms pass with the experimental helper/build fixes described
in [REPORT.md](REPORT.md). This is not yet ARM64 support in gdcc's production Java
backend, and does not validate its object-cache pipeline.

- [Validated native run](https://github.com/script-gd/probe-env/actions/runs/38277297631)
- [Report, fixes, evidence, and integration checklist](REPORT.md)
- [Source provenance and pruning](vendor/README.md)
- [Native runner workflow](.github/workflows/arm64.yml)
- [Build and runtime probes](scripts/probe.sh)

## Reproduce

Use the workflow above, or run from the repository root on a native ARM64 host
with a C compiler, make, Java 25, and the appropriate Godot 4.5.2 executable:

```bash
GODOT_BIN=/absolute/path/to/Godot bash scripts/probe.sh
```

On macOS, install Xcode Command Line Tools / an accessible macOS SDK. The script
intentionally runs failing TLS, flat-namespace, and cache-control negative tests;
their nonzero exit statuses are expected. Final successful checks include
`FFM_COMPILE_PASS`, `GDCC_PROJECT_PASS`, `COROUTINE_TLS_PASS`,
`DLOPEN_ROUNDS_PASS::1100`, and `OK thunkpage`.
