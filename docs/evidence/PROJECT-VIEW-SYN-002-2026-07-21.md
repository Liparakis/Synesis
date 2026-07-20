# SYN-002 searchable decision view evidence

Date: 2026-07-21
Task: SYN-002
Scope: bounded, on-demand, read-only search over locally persisted validated
decision heads inside `:project-record`.

## Implemented boundary

- `DecisionStore.verifiedHeads` scans only bounded `.head` files, validates the
  complete revision chain and current tip, and excludes temporary files,
  conflicts, stale heads, orphan revisions, and unverifiable records.
- `DecisionSearch` supports only bounded text matching over title/rationale,
  exact record ID, status, owner/author, and result limit.
- Results are deterministically ordered by record ID, revision, and digest.
- Rendering is stable, escaped, and contains no private keys, invitations,
  endpoints, absolute paths, or mutable storage details.
- Search is on demand, non-mutating, has no persistent index, and starts no
  background process.
- Corrupt or invalid local state returns a safe error code with zero results;
  it is never silently treated as a valid match.

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

Results: PASS. `DecisionSearchTest` covers matching, exact filters, empty
results, deterministic ordering/rendering, query bounds, result bounds, scan
bounds, stale revisions, conflicts, temporary files, corruption, no mutation,
and restart-equivalent output. The full strict root check also passes for
unchanged Link and CLI modules.

## Explicit non-claims

No new record type, mutation, sync change, wire message, persistent index,
background behavior, worker, lease, autonomy, federation, Obsidian integration,
CLI change, physical-machine claim, or broader CAF functionality is included.
