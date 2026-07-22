# Cross-platform release

`.github/workflows/release.yml` builds Java bundles and Go bootstrap binaries
for windows/linux/macOS x64/arm64. A target is `BUILT` after compilation,
`SMOKE_TESTED_NATIVE` only after execution on a matching runner, and
`CROSS_COMPILED_ONLY` when the artifact was not executed. The workflow prepares
artifacts for branches and tags; it does not publish a public release.

Protected tags require `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. Branch builds are explicitly
`developmentOnly` and unsigned.
