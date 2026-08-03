# SYN-037 root verification repair — 2026-08-03

This narrow follow-up repaired the two reproducible failures from the first
complete root Gradle run. No production command-execution or snapshot-policy
behavior was changed.

## Failure 1: CRLF/LF assertion

`Syn037CompletionValidationTest` asserted the raw string `implemented\n` after
the snapshot was integrated. On Windows, Git checkout semantics produced
`implemented\r\n`. The command evidence contract does not require newline
normalization, so `ProjectProcessExecutor` was left unchanged. The test now
uses `Files.readAllLines` and compares the exact logical line list
`[implemented]`, which still detects missing, duplicated, reordered, or wrong
content on Windows and Linux.

## Failure 2: managed baseline mistaken for a feature delta

`IntegrationOrchestrationServiceTest` committed `README.md`, initialized
Synesis, and then passed the control root as the worker root to
`TaskSnapshotService`. Initialization correctly created and committed
`.synesis/project.json` and `AGENTS.md`; the stale fixture then derived its
base as `HEAD^`, making that managed-baseline commit appear in the changed
paths. `SnapshotArtifactPolicy` correctly rejected both managed paths.

The fixture now follows the real lane sequence:

1. initialize Synesis and capture the post-init `git rev-parse HEAD` as the
   canonical managed baseline commit;
2. create a detached lane worktree from that exact commit;
3. write only the claimed source change, `src/claimed.py`;
4. assert the lane `HEAD` and snapshot `baseCommit` equal the baseline; and
5. assert the prepared changed-path manifest is exactly `[src/claimed.py]`.

The managed files remain committed in the parent baseline and are neither
allowed into a feature delta nor removed from `SnapshotArtifactPolicy`.

## Verification

- Focused repaired tests: PASS in 21.6 seconds.
- `:workspace:check`: PASS in 7m39s.
- Complete profiled root `check`: PASS in 15 seconds after workspace outputs
  were current; 57 actionable tasks.
- Profile: `build/reports/profile/profile-2026-08-03-09-53-49.html`.
- Deferred validator: PASS (9 entries).
- Bootstrap `go test -count=1 ./...`: PASS.
- Bootstrap `go vet ./...`: PASS.
- `git diff --check`: PASS.
- `agent-doctor.ps1`: PASS with its existing personal-absolute-path warning.

SYN-037 final verification can now be marked complete.
