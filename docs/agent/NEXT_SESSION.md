# Next Session

- Active task: SYN-013D (Stage 2A minimal MCP server — Slice 1 complete)
- Repository branch: master
- Last checkpoint: CP-0166
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, all UP-TO-DATE or passing)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Implement Stage 2A Slice 2: add `synesis.read_file`, `synesis.apply_patch`, `synesis.run_command`, and `synesis.get_next_action` tools to `:mcp`.
- Unresolved limitations: Codex native `apply_patch` does not invoke `.codex/hooks.json` PreToolUse hooks (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`); workspace mutations enforced via `WorkspaceMutationBroker` (Strategy B). Provider MCP config installation deferred until Slice 2 passes.
- Facts that must not be forgotten: `:mcp` must NOT depend on `:cli`; `:cli` must NOT depend on `:mcp`. Normal MCP responses use the Stage 1 concise `AgentResponse` contract (`AgentResponse.toJson()`). Internal IDs, worktree paths, commit SHAs, and evidence must NOT appear in normal tool output.
