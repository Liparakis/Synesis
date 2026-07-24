# Next Session

- Active task: SYN-013D (Stage 2A minimal stdio MCP server & provider integrations complete)
- Repository branch: master
- Last checkpoint: CP-0167
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, all UP-TO-DATE or passing)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Implement Stage 2B provider-neutral MCP tools (synesis.read_file, synesis.apply_patch, synesis.run_command, synesis.get_next_action).
- Unresolved limitations: Codex native `apply_patch` does not invoke `.codex/hooks.json` PreToolUse hooks (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`); workspace mutations enforced via `WorkspaceMutationBroker` (Strategy B).
- Facts that must not be forgotten: `:mcp` must NOT depend on `:cli`; `:cli` must NOT depend on `:mcp`. Normal MCP responses use the Stage 1 concise `AgentResponse` contract (`AgentResponse.toJson()`). Internal IDs, worktree paths, commit SHAs, and evidence must NOT appear in normal tool output.
