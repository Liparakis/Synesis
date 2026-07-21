# SYN-003 CP-W1 workspace bootstrap evidence

Date: 2026-07-21
Task: SYN-003
Scope: JDK-only `:workspace` launcher for isolated local profile bootstrap,
one-peer project creation, and revision-1 signed decision creation.

## Implemented boundary

- `:workspace` owns only bounded command parsing, profile layout, orchestration,
  and safe stable output.
- Profiles use `<profile>/link`, `<profile>/project.conf`, and
  `<profile>/records`.
- `identity show` loads or creates one Link identity and prints only `NODE_ID`.
- `project create --peer` writes one atomic existing `ProjectConfig` and
  refuses existing or mismatched configuration.
- `decision create` creates exactly one signed `decision` revision 1 with one
  bounded evidence reference and prints `NODE_ID`, `PROJECT_ID`, `RECORD_ID`,
  and `DIGEST`.
- Link, `:cli`, and `:project-record` production sources are unchanged.
- No host/join, networking, sync, new record type, background process, or
  physical-machine claim is included.

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

Results: PASS. Focused tests cover isolated identity reuse, project atomic
creation, overwrite/mismatch refusal, decision signature and restart
readability, bounds, stable output, and sensitive-output redaction. The full
strict root build runs Link, CLI, project-record, and workspace checks.

## Explicit non-claims

This evidence does not claim onboarding, host/join, authenticated sync,
physical two-machine operation, multiple projects per profile, membership,
reconnect, retries, background behavior, workers, leases, autonomy,
federation, Obsidian, or production packaging.
