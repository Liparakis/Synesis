# SYN-003 CP-W2 workspace sync evidence

Date: 2026-07-21
Task: SYN-003
Scope: one-shot authenticated workspace host/join and exactly one CP-R4
decision-record sync.

## Implemented boundary

- `sync host` loads one existing project configuration and uses its single
  configured peer as the expected authenticated identity.
- `sync join --project <uuid> --record <uuid> --expect-host <node-id>
  <invitation>` verifies the signed invitation through the existing Link
  onboarding seam, checks the authenticated remote node, and only then creates
  a missing local project configuration.
- The workspace composes the existing `ProjectRecordSync` endpoint and makes
  exactly one request. `APPLIED` and `DUPLICATE` are successful; all other
  outcomes and configuration, authentication, invitation, identity, or
  transport failures return a nonzero exit with a bounded safe error label.
- Host output is the only place where an invitation is emitted. Invitation
  values are intentionally absent from tests, logs, and this evidence.
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

Results: PASS. Generated-launcher process tests cover initial `APPLIED`,
second-session `DUPLICATE`, wrong-host rejection without configuration
mutation, malformed invitation, project mismatch without overwrite, missing
record (`REJECTED`), and connection close before a result (`UNKNOWN`).

## Explicit non-claims

This evidence does not claim retries, reconnect, discovery, membership,
background sync, search or inspection commands, additional record types,
workers, leases, autonomy, federation, Obsidian, physical two-machine
operation, or production packaging.
