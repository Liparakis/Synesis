# Next Session

- Active task: SYN-014B DONE at CP-0188
- Repository branch: master
- Last checkpoint: CP-0188
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, BUILD SUCCESSFUL)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Await user directive. SYN-014B (Post-MVP Hardening Slice 2) is complete. Next hardening slices available: SYN-014C (crash reconciliation, task cancellation, doctor diagnostics, safe repair), SYN-014D (installer process drain and configuration migration), SYN-014E (ProductCli demo fixture and release acceptance matrix).
- Unresolved limitations: Speculative continuation deferred. Actual worktree cleanup uses non-forced `git worktree remove`. Unregistered orphan resources are quarantined atomically without purge.
- Facts that must not be forgotten: Handles format must be `req_<random_token>` with at least 96 bits entropy. MCP responses must remain concise and must not leak internal IDs, worktree paths, or event IDs. Exactly 10 MCP tools are currently registered in `tools/list`. `synesis.complete_task` schema takes ONLY `{ "summary": "..." }`. Validation and integration worktrees MUST be resolved externally (`%LOCALAPPDATA%\Synesis\workspaces\<project-id>\...`) leaving the control checkout clean for fast-forward merge. Provider configuration files are located under `%USERPROFILE%\.codex\config.json` and `%USERPROFILE%\.gemini\antigravity\mcp_config.json`. Persisted plans are stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-plans\<plan-id>.json`. Execution locks are at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-execution.lock`. Execution journals are at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-executions\<execution-id>.jsonl`.

