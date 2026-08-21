# Next Session

- SYN-039 is ACTIVE: Autonomous Workgroup Completion.
- SYN-038 remains DONE at CP-0458; its prior App Server history, tag,
  acceptance evidence, and `turn_interrupted_command_remained_active`
  limitation remain preserved.
- Repository branch: master.
- Activation is bookkeeping-only. No SYN-039 production code has changed.
- Primary failure input: the user-supplied unattended Todo smoke test. The
  reproduced baseline is recorded in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; then inspect the existing LaneGrant, snapshot
  projection, validation, completion, and integration transitions.
- Exact next code action: add the smallest deterministic regression fixture
  for the missing reviewer grant and the contradictory `pytest`-passing /
  `TESTS_FAILED` completion result. Keep `SYN-014E` paused.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and SYN-039
  must not add a daemon, UI, Fleet system, central orchestrator, or launcher.
