# ADR-0025: Cross-platform release and signing model

## Status

Accepted for SYN-009C.3.

## Decision

Use one CI workflow with explicit native platform rows for windows/linux/macOS
x64/arm64. Build and static-check all rows; execute smoke tests only on rows
whose runner architecture matches the artifact. Aggregate the manifest only
after all required artifacts and checksums exist. Sign only protected tag
preparations using a CI secret.

## Context and alternatives

There is no existing CI workflow. A single matrix is simpler than six copied
workflows and makes native versus cross-compiled evidence visible. Automatic
public release would add credentials and irreversible external state without
user authorization, so publication remains manual.

## Consequences

ARM runner availability is an explicit operational prerequisite. Unsigned
development artifacts are useful for tests but are not public-trust releases.
OS vendor signing and notarization remain separate release-hardening work.
