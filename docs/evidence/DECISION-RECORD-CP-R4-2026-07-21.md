# CP-R4 decision-record exchange evidence

Date: 2026-07-21
Task: SYN-001
Scope: configured project namespace, explicit peer allowlist, bounded one-shot
publish/sync over the verified Link application-stream seam.

## Implemented boundary

- `ProjectConfig` persists a strict UTF-8 `projectId` and bounded explicit
  `sl1-...` peer allowlist with atomic replacement.
- `RecordMessage` defines deterministic bounded `SYNC_REQUEST`, `RECORD`,
  `RESULT`, and `ERROR` messages (4,096-byte envelope; 256-byte error text).
- `ProjectRecordSync` performs one publish or one sync request only. It checks
  Link readiness and authenticated remote identity, project ID, owner/author,
  signature, bounds, revision, and predecessor digest before mutation.
- `DecisionStore.applyRemote` returns deterministic duplicate/stale/conflict/
  rejected/applied classifications and quarantines valid divergent revisions
  under `conflicts/` without replacing the head.
- Connection completion without a result is mapped to `UNKNOWN`.
- `:cli` remains unchanged; Link retains framing, limits, deadlines, liveness,
  and cleanup ownership.

## Verification

Commands:

```text
gradlew.bat :project-record:check --dependency-verification=strict
gradlew.bat clean check --dependency-verification=strict
powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-validate-deferred.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-validate-fixtures.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-doctor.ps1
```

Results: PASS. `ProjectConfigTest` and `RecordMessageTest` cover strict
configuration and deterministic framing. `ProjectRecordSyncProcessTest`
launches two isolated JVM profiles and asserts:

```text
APPLIED, DUPLICATE, APPLIED, REMOTE_STALE, CONFLICT
```

The same process flow performs a `SYNC_REQUEST` and observes `DUPLICATE` for
the shared head. The conflict profile contains a quarantined divergent
revision while its head remains revision 2. This is two-process local evidence,
not a physical two-machine claim.

## Explicit non-claims

No background synchronization, reconnect, discovery, membership, retry loop,
additional record type, autonomous behavior, federation, physical network
reachability, or CLI change is implemented or claimed. Work stops before CP-R5
and broader CAF functionality.
