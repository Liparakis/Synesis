# SYN-003 CP-W3 workspace search and inspect evidence

Date: 2026-07-21
Task: SYN-003
Scope: decision search and inspect in `:workspace` composing the existing DecisionSearch and head-pointer/revision-chain validation.

## Implemented boundary

- `decision search [--text <text>] [--record <uuid>] [--status <status>] [--owner <node-id>] [--limit <int>]` searches only fully validated current decision heads using the existing `DecisionSearch` API. It excludes conflicts, stale revisions, temporary files, corrupt records, and historical revisions.
- `decision inspect --record <uuid>` retrieves and validates the entire revision chain and head pointer for the requested record ID only, ignoring potential corruption in unrelated records in the store.
- Stable, safe, byte-stable output for project ID, record ID, version, digest, owner, author, status, evidence digest, and signature validity; no absolute paths, private keys, invitations, endpoints, or stack traces are printed.
- Existing Workspace CLI exit-code mappings are preserved; successful operations return exit code `0`, while malformed inputs (invalid UUID/status/limit), missing records, and store corruption return exit code `10`.
- Link, `:cli`, and `:project-record` production sources are unchanged.

## Verification

Commands:

```text
gradlew.bat :workspace:test --dependency-verification=strict
gradlew.bat clean check --dependency-verification=strict
powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-validate-deferred.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-validate-fixtures.ps1
powershell -ExecutionPolicy Bypass -File scripts/agent-doctor.ps1
git diff --check
```

Results: PASS. Focused tests in `WorkspaceCliTest` and process-level integration tests in `WorkspaceSyncProcessTest` cover matching results after sync, restart stability, duplicate sync preservation, empty searches, malformed filters, missing records, corruption, conflicts, and stale revisions.

## Explicit non-claims

This evidence does not claim retries, reconnect, discovery, membership, background sync, additional record types, workers, leases, autonomy, federation, Obsidian integration, physical two-machine operation, or production packaging.
