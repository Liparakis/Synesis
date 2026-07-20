# CP-R2 decision record evidence

## Status

`VERIFIED` — 2026-07-21. This evidence covers local canonical decision
records only. It does not claim networking, publish/sync, peer allowlists,
background behavior, or additional record types.

## Verified behavior

- Deterministic `SDR1` encoding, SHA-256 digest, Ed25519 signature, strict
  UTF-8 and field/complete-record bounds.
- Stable project/record identity, revision, predecessor digest, owner/author,
  status, timestamps, title, rationale, evidence digests, and embedded public
  key.
- Immutable per-profile revision files and atomic head replacement.
- Duplicate no-op, stale-base rejection, divergent successor conflict, invalid
  signature rejection, corrupt-file rejection, and missing-head restart
  reconstruction.
- JDK-only `:project-record` inspection launcher with safe readable output.

## Verification commands

Focused:

```text
gradlew.bat :project-record:check --dependency-verification=strict
```

Result: `BUILD SUCCESSFUL`.

Full:

```text
gradlew.bat clean check --dependency-verification=strict
```

Result: `BUILD SUCCESSFUL`; `:link`, `:cli`, and `:project-record` checks,
strict Javadocs, launcher smoke, and tests passed.

## Explicitly deferred

CP-R4 owns authenticated publish/sync over the existing Link seam. No network,
sync protocol, extra record type, background process, or `:cli` change was
implemented here.
