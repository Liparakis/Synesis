# Cross-platform release

`.github/workflows/release.yml` checks the Java project, builds and smoke-tests
Java bundles on the six target runners, cross-compiles all six Go bootstraps,
runs a native bootstrap subprocess smoke on Linux, aggregates checksums and a
manifest, and validates artifact consistency. A target is `BUILT` after
compilation, `SMOKE_TESTED_NATIVE` only after execution on a matching runner,
and `CROSS_COMPILED_ONLY` when the artifact was not executed. If a required
runner is unavailable, the honest status is `NOT_SUPPORTED_BY_RUNNER`; no
workflow step may upgrade that status without native execution. The workflow
prepares artifacts for branches and tags; it does not publish a public release.

Protected tags require `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. Branch builds are explicitly
`developmentOnly` and unsigned.
