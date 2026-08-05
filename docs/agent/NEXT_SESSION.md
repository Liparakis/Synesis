# Next Session

- SYN-038 is DONE at CP-0458. The prior Codex App Server lifecycle history,
  CP-0457 evidence, and acceptance records remain preserved.
- Repository branch: master.
- Implementation commit: `ad9fdd8addc9f71e806dfb2da5b5d78f050f87ac`.
- Final closure checkpoint: CP-0458. The final annotated durable-command tag
  and remote branch must be verified before any new task is promoted.
- Final verification passed: full `check`, focused MCP and SYN-038 tests,
  validators, Go tests/vet, Javadocs/format, doctor, and `git diff --check`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; then verify the pushed closure commit and tag.
- Exact next code action: none. Do not create SYN-039, alter prior evidence,
  or begin unrelated work. Keep `SYN-014E` paused.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and the
  interrupted-turn classification remains
  `turn_interrupted_command_remained_active`.
