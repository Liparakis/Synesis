# Next Session

- Active task: SYN-013D (Stage 2A minimal MCP server — not yet started)
- Repository branch: master
- Last checkpoint: CP-0165
- Last passing command: `.\gradlew.bat check --no-daemon` (42 tasks, all UP-TO-DATE or passing)
- Last commit: `198f3e9` ("Simplify Synesis agent-facing responses")
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Create `:mcp` Gradle subproject and `AgentSessionService` ambient session resolver in `:workspace`.
- Unresolved limitations: Codex native `apply_patch` does not invoke
  `.codex/hooks.json` PreToolUse hooks (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`);
  workspace mutations enforced via `WorkspaceMutationBroker` (Strategy B).
- Facts that must not be forgotten: `:mcp` must NOT depend on `:cli`; `:cli`
  must NOT depend on `:mcp`. Ambient session resolution belongs to Stage 2A
  (SYN-013D), not Stage 1. No MCP provider configuration may be written before
  Stage 2A passes real harness acceptance tests.
