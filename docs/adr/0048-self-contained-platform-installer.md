# ADR-0048: Self-contained platform bundle installer

## Status

Accepted — August 30, 2026

## Context

The release workflow previously exposed separate Java bundles, native bootstrap
executables, and release metadata. That split made the downloaded result harder
to use and made the install path depend on a separately hosted manifest. The
required user workflow is simpler: download one platform-specific bundle, run
its installer, and choose install, repair, or uninstall.

## Decision

Each platform bundle contains:

- the Synesis CLI and bundled Java runtime;
- the native MCP launcher; and
- a native `synesis-installer` executable built from the bootstrapper.

The installer accepts a local bundle directory or archive. With no command it
opens a terminal menu. Install and repair use only that local bundle and do not
install or configure a provider. Repair replaces the verified application
payload while retaining project workspaces, project `.synesis` data, and
provider metadata. Uninstall removes application files and PATH state by
default; an explicit metadata option also removes the installer-owned Link
identity and administrative state. Project directories are never recursively
discovered or deleted by the installer.

Branch pushes, tagged GitHub releases, and manual workflow runs publish exactly
six raw release assets named for the runnable files: `synesis-windows-x64.exe`,
`synesis-windows-arm64.exe`, `synesis-linux-x64`, `synesis-linux-arm64`,
`synesis-macos-x64`, and `synesis-macos-arm64`. Manual runs require a release
tag only when a custom release name is desired; without one, it creates a
numbered manual prerelease automatically. Branch pushes create numbered
`build-<run-number>` prereleases. The workflow does not use Actions
artifact uploads for these files, because that service always wraps downloads
in a ZIP container. Pull-request runs build and smoke-test the files without
publishing them. The separate bootstrap and manifest artifact uploads,
aggregate release candidate, and install-script distribution are removed from
the workflow.
Gradle caching is enabled on the Java setup steps.

## Consequences

The downloaded artifact is directly actionable after extraction and does not
need a network service for initial installation. The installer remains a
terminal application rather than a platform-native GUI. Remote signed manifest
update code remains in the bootstrap source for controlled operator workflows,
but it is not part of the user-facing release artifact set. Project metadata is
deliberately outside the uninstall wipe scope because Synesis cannot safely
identify every user-owned workspace on a machine.
