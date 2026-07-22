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

Ordinary GitHub Actions runs expose one final artifact named
`synesis-release-candidate`. Matrix outputs are short-lived internal artifacts
used by the final aggregation job and are deleted after successful upload.
The final artifact keeps platform-specific bundles and bootstrappers under
`bundles/` and `bootstrap/`; they are not combined into a universal executable.
Future tagged GitHub Releases may expose those platform files individually for
bootstrap downloads.

Protected tags require `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. Branch builds are explicitly
`developmentOnly` and unsigned.
