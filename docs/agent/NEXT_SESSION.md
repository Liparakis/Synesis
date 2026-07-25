# Next Session

- Active task: SYN-014C DONE at CP-0189
- Repository branch: master
- Last checkpoint: CP-0189
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, BUILD SUCCESSFUL)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Await user directive for the next post-MVP slice or operational task. Post-MVP Hardening Slice 3 (SYN-014C) is complete and verified.
- Unresolved limitations: Speculative continuation deferred. Actual worktree cleanup uses non-forced `git worktree remove`. Unregistered orphan resources are quarantined atomically without purge. Session abandonment and task cancellation preserve worktrees non-destructively for operator review.
- Facts that must not be forgotten: Handles format must be `req_<random_token>` with at least 96 bits entropy. MCP responses must remain concise and must not leak internal IDs, worktree paths, or event IDs. Exactly 11 MCP tools are currently registered in `tools/list`. `synesis.cancel_task` takes `{ "reason": "..." }` bounded 1-1000 characters and requires ambient worker identity. Session leases are stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\session-leases\<connection>.json`. Reconciliation plans are stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-plans\<plan-id>.json`. Reconciliation execution lock is at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-execution.lock`. Reconciliation execution journals are at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-executions\<execution-id>.jsonl`.
