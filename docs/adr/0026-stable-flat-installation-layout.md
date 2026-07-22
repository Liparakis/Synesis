# ADR-0026: Stable flat installation layout

## Status

Accepted for SYN-009D.

## Decision

The canonical installation is one OS-conventional `Synesis` root containing
the active bundle directly: `bin/`, `runtime/`, application files, and
`VERSION`. Install and update extract into a sibling `Synesis.staging-*`
directory, validate the staged launcher, move the prior root to one temporary
`Synesis.rollback` sibling, activate the staged root at the stable path, and
remove rollback only after the stable launcher passes `version` and `doctor`.

The bootstrap may detect the legacy `versions/<version>/` plus `current` layout
only during one safe migration. It never creates or retains that layout after a
successful activation. Provider integrations resolve `synesis` from PATH first,
with the OS stable-root launcher as fallback; they never encode a version path.

Windows owns only the user PATH entry `%LOCALAPPDATA%\Synesis\bin`, compared
case-insensitively after trimming separators. Unix installs the bundle launcher
under the stable root and manages a user-local PATH bridge without copying the
application.

## Context and alternatives

The previous layout kept multiple application copies below `versions/` and
used a mutable `current` pointer plus a proxy launcher. That added persistent
state, made provider commands version-specific, and made uninstall/doctor
reason about two sources of truth. A stable root removes that ambiguity while
staging and rollback preserve the existing security and recovery guarantees.

Keeping the old layout indefinitely was rejected because it preserves the
source of the ambiguity. In-place extraction was rejected because a partial
archive or failed launcher could corrupt the active installation. A new
installer service or dependency was rejected because the Go standard library
already owns download, verification, extraction, and process orchestration.

## Security and failure behavior

Detached Ed25519 manifest signatures, SHA-256 artifact checks, archive-size
limits, traversal rejection, symlink rejection, and version validation remain
mandatory. User projects and project-local `.synesis` directories are outside
the installation root and are never deletion targets. Any staging, activation,
stable-launcher, or doctor failure restores the previous root when one exists;
rollback is deleted only after successful post-activation validation.

## Fitness functions and invalidation

Go tests assert that successful installs and updates have no `versions/` or
`current`, that failed activation restores the previous bundle, that legacy
migration removes old state only after validation, and that PATH edits preserve
unrelated entries. Native Windows smoke verifies the real user PATH and stable
launcher. Reconsider this decision only if the product requires concurrent
side-by-side application versions or an update mechanism that cannot be
represented by one staged bundle and one rollback root.
