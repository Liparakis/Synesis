# Cross-platform release

`.github/workflows/release.yml` checks the Java project, caches Gradle's
dependency and compilation state, and builds and smoke-tests self-contained
bundles on the six target runners. Each bundle contains the Java runtime, CLI,
native MCP launcher, and native installer for its target platform. On `v*`
tags, or when started manually with a release tag, the workflow publishes the
six runnable files directly as GitHub Release assets, without an Actions-
artifact ZIP wrapper.

Tagged releases expose exactly six raw platform assets:
`synesis-windows-x64.exe`, `synesis-windows-arm64.exe`,
`synesis-linux-x64`, `synesis-linux-arm64`, `synesis-macos-x64`, and
`synesis-macos-arm64`. Manual runs use the supplied release tag and create the
release if it does not exist. Branch and pull-request runs validate the files
without publishing them. The workflow does not create an aggregated
release-candidate directory, separate bootstrap artifact, metadata artifact,
or install-script directory. Each runnable file contains the platform bundle as an appended,
verified payload, including the application jars, bundled Java runtime, native
MCP launcher, and installer.

Double-click the Windows `.exe`, or run the Unix file from a terminal. The
installer menu offers Install, Repair, and Uninstall without requiring an
archive extraction step. Install and Repair use the embedded local bundle and
do not install Claude, Codex, or any other provider. Repair replaces the
verified application payload while retaining project workspaces and project
`.synesis` data. Uninstall removes the application payload by default and
offers an explicit metadata wipe for the installer-owned identity and state;
project workspaces remain outside the installer's deletion scope.
