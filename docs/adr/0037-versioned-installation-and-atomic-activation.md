# ADR-0037: Versioned immutable installation with atomic pointer activation

- Status: Accepted
- Date: 2026-07-25
- Task: SYN-014E

## Context

The existing Go bootstrapper activates a bundle by replacing the flat active
installation directory. That is unsafe for already-running MCP processes and
does not provide a durable prepared update plan or a stable version selector.
The repository already owns the bootstrapper, archive verification, atomic-file
patterns, and native Windows smoke coverage. A second installer or a service
would add release and recovery coupling without evidence that it is needed.

## Decision

Evolve the existing bootstrapper into a versioned local installer. The stable
root owns `bin`, `versions`, `current.json`, `previous.json`, and `admin` state.
Each staged payload is fully verified before it is renamed into a unique
version directory and is never modified after activation. A small pointer,
stored outside payloads, selects the active version. Pointer writes use a
same-volume temporary file, source-fingerprint compare-and-set, and atomic
replacement. The stable launcher validates the pointer, path token, payload,
and manifest before launching the selected entry point.

Update execution remains local and operator-driven: a bundle is verified,
staged, self-tested, prepared into an immutable plan, then activated under one
installation lock and journal. The prior pointer and payload remain available
for exact rollback. No updater operation terminates processes or deletes old
payloads.

Provider and project migration are separate bounded steps. They may update
only reviewed Synesis-owned metadata after parsing, backup, and compare-and-set
verification. Signed event history, identities, snapshots, and unrelated
provider settings remain outside their write ownership.

## Consequences

- Existing processes keep using their loaded immutable payload.
- New processes resolve the new pointer through the stable launcher.
- Interrupted activation is recoverable from the previous pointer and journal.
- Old versions consume disk until a separately authorized cleanup capability is
  designed; this slice intentionally retains them.
- Remote download, background updates, process shutdown, and broad project
  scanning remain out of scope.

## Rejected alternatives

- Replacing the flat active directory: races with running processes and loses a
  clean version boundary.
- Junction/symlink activation: pointer semantics and Windows reparse behavior
  complicate trust and compare-and-set verification.
- A separate installer service: no evidence justifies another deployable or
  ownership boundary.
