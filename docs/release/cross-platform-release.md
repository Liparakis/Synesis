# Cross-platform release

`.github/workflows/release.yml` checks the Java project, caches Gradle's
dependency and compilation state, and builds and smoke-tests self-contained
bundles on the six target runners. Each bundle contains the Java runtime, CLI,
native MCP launcher, and native installer for its target platform. The workflow
prepares artifacts for branches and tags; it does not publish a public release.

Ordinary GitHub Actions runs expose exactly six platform outputs individually:
`synesis-<platform>` artifacts containing the self-contained Java bundle
archive. The workflow does not create an aggregated release-candidate
directory, separate bootstrap artifact, metadata artifact, or install-script
directory. The archive remains necessary because it contains the application
jars, bundled Java runtime, native MCP launcher, and installer; its platform
launcher is `bin/synesis.cmd` on Windows and `bin/synesis` on Unix.

After extraction, run `bin/synesis-installer.exe` on Windows or
`bin/synesis-installer` on Linux/macOS. The menu offers Install, Repair, and
Uninstall. Install and Repair use the local bundle and do not install Claude,
Codex, or any other provider. Repair replaces the verified application payload
while retaining project workspaces and project `.synesis` data. Uninstall
removes the application payload by default and offers an explicit metadata wipe
for the installer-owned identity and state; project workspaces remain outside
the installer's deletion scope.
