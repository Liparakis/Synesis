# Next Session

- Active task: SYN-014A DONE at CP-0187
- Repository branch: master
- Last checkpoint: CP-0187 (pending creation this session)
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, BUILD SUCCESSFUL)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Await user directive. SYN-014A (Post-MVP Hardening Slice 1) is complete. Next hardening slices available: SYN-014B (crash reconciliation and task cancellation), SYN-014C (doctor diagnostics and safe repair), SYN-014D (installer process drain and configuration migration), SYN-014E (ProductCli demo fixture and release acceptance matrix).
- Unresolved limitations: Speculative continuation deferred. Actual cleanup execution (`synesis cleanup` without `--dry-run`) is intentionally not implemented and exits with code 10.
- Facts that must not be forgotten: Handles format must be `req_<random_token>` with at least 96 bits entropy. MCP responses must remain concise and must not leak internal IDs, worktree paths, or event IDs. Exactly 10 MCP tools are currently registered in `tools/list`. `synesis.complete_task` schema takes ONLY `{ "summary": "..." }`. Validation and integration worktrees MUST be resolved externally (`%LOCALAPPDATA%\Synesis\workspaces\<project-id>\...`) leaving the control checkout clean for fast-forward merge. Provider configuration files are located under `%USERPROFILE%\.codex\config.json` and `%USERPROFILE%\.gemini\antigravity\mcp_config.json`. Cleanup domain models are in `org.synesis.workspace.cleanup` in `:workspace`.

