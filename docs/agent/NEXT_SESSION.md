# Next Session

- SYN-039 is ACTIVE: Autonomous Workgroup Completion.
- SYN-038 remains DONE at CP-0458; its prior App Server history, tag,
  acceptance evidence, and `turn_interrupted_command_remained_active`
  limitation remain preserved.
- Repository branch: master.
- The reviewer-validation and producer-publication SYN-039 slices are
  implemented and verified by focused deterministic tests; the full root check
  is incomplete because the recurring Git subprocess stall reproduces in
  `McpServerTest`.
- Primary failure input: the user-supplied unattended Todo smoke test. The
  reproduced baseline is recorded in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Evidence: `docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`
  and `docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; then run the exact fresh two-agent Todo acceptance
  with both MCP processes independently verified against the current bundle
  and initialized project root.
- Exact next code action: preserve the first lifecycle result after the owner
  executes the corrected projected `finish_lane` action. Keep `SYN-014E`
  paused; do not create SYN-040.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and SYN-039
  must not add a daemon, UI, Fleet system, central orchestrator, or launcher.

## CP-0471 continuation

The owner REVIEW-acceptance projection is implemented and covered by
`McpSyn039SliceTest`: the exact request, WorkGroup, intent, epoch, and strict
`respond_coordination` arguments are now exposed. Focused verification is
green. The root check remains incomplete at the Git subprocess stall in
`WorkspaceCliTest.setUp:74`; Doctor remains DEGRADED. The fresh unattended
rerun stopped before this slice at an existing `overlapping_claim` admission
blocker, so it must be rerun with an isolated initial owner before drawing a
SYN-039 lifecycle conclusion. Do not push yet; do not create SYN-040.

Immediate next action: run
`powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
launch the exact fresh two-agent Todo acceptance with isolated initial
ownership and no manual relay. Preserve the next concrete lifecycle failure.

## CP-0472 continuation

The fresh two-agent run stopped before WorkGroup creation: both agents received
`workspace_not_ready` and a projected `ensure_session` recovery action. The
fixture remained at coordination sequence zero, with no claims, requests,
grants, snapshots, validation, integration, or closure. Evidence:
`docs/evidence/syn039-unattended-todo-workspace-not-ready-2026-08-23.md`.
Do not change production code yet. First reproduce the same state using a
deterministic per-project MCP/session fixture and inspect readiness binding.
Keep the recurring Git stall separate, do not push, and do not create SYN-040.

## CP-0473 continuation

The Codex provider configuration now pins the initialized project root with
`--project`; deterministic provider/session tests pass and direct MCP returns
`ready/isolated`. Evidence:
`docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.

The fresh unattended rerun still stopped before lifecycle creation because the
agent harness used an incompatible/stale MCP distribution and reported schema
v2 as unsupported. Immediate next action: install/use the current bundled
Synesis MCP distribution for both agents, rerun the exact unattended Todo test
with no relay or manual transitions, and preserve the first lifecycle blocker.
Keep the Git stall and bootstrap migration-test failures separate. Do not
push, create SYN-040, or broaden the task.

## CP-0474 continuation

The current-bundle rerun is recorded in
`docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`. Both independent
Luna High agents used the rebuilt project-pinned MCP executable and reached
`ready / isolated`. The reviewer discovered the WorkGroup, consumed REVIEW
grant `496f1893-ca32-3939-82a1-24f860dea86a`, and the implementer passed four
pytest tests. The first real lifecycle blocker is the implementer's projected
`PUBLISH` action remaining at `snapshot_publication_required`; no immutable
snapshot or validation was reached.

Exact next action: reproduce this owner-side `PUBLISH` stop deterministically,
trace why the implementer does not execute the already-projected snapshot
publication path, and implement only that narrow protocol fix if confirmed.
Then rerun the exact unattended Todo test. Keep cleanup, Doctor, ownership,
integration redesign, the Git stall, and bootstrap migration failures
separate. Do not push or create SYN-040.

## CP-0475 continuation

The executable `finish_lane` projection now carries the existing summary from
`nextProtocolPayload`. The deterministic MCP fixture published a real immutable
snapshot and exposed its ID in reviewer coordination status. Evidence:
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

The fresh agent-harness attempts did not produce a valid shared WorkGroup: one
stopped at `workspace_not_ready`, one saw no peer WorkGroup, and a second retry
remained non-terminal until bounded shutdown. Immediate next action: run the
exact two-agent Todo acceptance with both independent MCP processes verified
against the current bundled executable and project root, then preserve the
first post-publication lifecycle blocker. Do not modify production behavior
speculatively, push, or create SYN-040.
