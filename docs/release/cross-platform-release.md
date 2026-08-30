# Cross-platform release

`.github/workflows/release.yml` checks the Java project, builds and smoke-tests
Java bundles on the six target runners, cross-compiles all six Go bootstraps,
runs a native bootstrap subprocess smoke on Linux, creates checksums and a
manifest, and validates artifact consistency. A target is `BUILT` after
compilation, `SMOKE_TESTED_NATIVE` only after execution on a matching runner,
and `CROSS_COMPILED_ONLY` when the artifact was not executed. If a required
runner is unavailable, the honest status is `NOT_SUPPORTED_BY_RUNNER`; no
workflow step may upgrade that status without native execution. The workflow
prepares artifacts for branches and tags; it does not publish a public release.

Ordinary GitHub Actions runs expose each platform output individually: six
`synesis-bundle-<platform>` artifacts containing the self-contained Java bundle
archive, six `synesis-bootstrap-<platform>` native executable artifacts, and a
separate `synesis-release-metadata` artifact containing the manifest and
checksums. The workflow does not create an aggregated release-candidate
directory or require an install script. The Java bundle remains an archive
because it contains the application jars, bundled Java runtime, and native MCP
launcher; its platform launcher is `bin/synesis.cmd` on Windows and
`bin/synesis` on Unix.

Each bootstrap artifact installs its matching bundle directly into the stable
OS user-data root. Updates use sibling staging and one temporary rollback
directory; the release process does not retain prior application versions or a
mutable launcher pointer.

Protected tags require `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. Branch builds are explicitly
`developmentOnly` and unsigned.
