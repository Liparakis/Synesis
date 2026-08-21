# Next Session

- SYN-039 is ACTIVE: Autonomous Workgroup Completion.
- SYN-038 remains DONE at CP-0458; its prior App Server history, tag,
  acceptance evidence, and `turn_interrupted_command_remained_active`
  limitation remain preserved.
- Repository branch: master.
- Activation is bookkeeping-only. No SYN-039 production code has changed.
- Primary failure input: the user-supplied unattended Todo smoke test. Its raw
  run artifact is not present in this checkout and must be captured first.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; then reproduce the two-agent Todo failure and
  record the exact evidence before implementation.
- Exact next code action: none until the reproduction is captured and the
  existing reviewer, handoff, validation, integration, cleanup, and Doctor
  boundaries are inspected. Keep `SYN-014E` paused.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and SYN-039
  must not add a daemon, UI, Fleet system, central orchestrator, or launcher.
