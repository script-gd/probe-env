# Compiled gdcc Snapshot

This directory deliberately contains no gdcc Java source, Gradle checkout, or native launcher.

- `app/gdcc-0.0.2.jar`: packaged from the existing `build/classes/java/main` and
  `build/resources/main` outputs, using the existing JAR manifest. No compilation is performed.
- `app/lib/*.jar`: unchanged runtime dependency outputs from `build/libs/lib`.
- `windows-x86_64.tar.gz`: existing `build/tinycc-bundle/windows-x86_64` output,
  including its SHA-256 manifest, headers, import definitions, runtime archive and licenses.
- `snapshot.json`: SHA-256 digests and exact embedded version provenance.
- `GDCC-LICENSE`: upstream license, copied unchanged.

The embedded build version is `457262e8`; the checkout HEAD at capture time is
`3136c45182c4c327024ec677d150ba21e0588771`. These are intentionally recorded separately.
Do not claim the copied classes were rebuilt from the capture-time HEAD. The stale
`build/libs/gdcc-0.0.2.jar` (`8e18a1ca`) was **not** used: it lacks macOS support and the
object cache already present in the newer class outputs.

To capture a replacement after building gdcc elsewhere:

```sh
python3 scripts/minimal-jre.py snapshot --gdcc ../gdcc
```

This explicit command replaces this snapshot. Ordinary CI uses only the committed snapshot
and the already-vendored TinyCC source; it does not clone or compile gdcc.

The Windows bundle was cross-built by upstream with Zig, but neither Zig nor a C compiler
is used by the packaged gdcc at runtime. Linux/macOS bundles are built from `vendor/tinycc`
on their native runners. Their system headers/CRT/SDK remain external prerequisites.
See `JRE_REPORT.md` for scope, licensing limitations and measured results.
