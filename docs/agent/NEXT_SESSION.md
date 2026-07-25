# Next Session

- Active task: SYN-014D ACTIVE at CP-0190
- Repository branch: master
- Last checkpoint: CP-0190
- Last passing command: `.\gradlew.bat check --no-daemon` (49 tasks, BUILD SUCCESSFUL)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Stop after Hardening Slice 4. Await user directive for the next post-MVP operational milestone. Post-MVP Hardening Slice 4 (SYN-014D) is complete and verified.
- Unresolved limitations: Speculative continuation deferred. Actual worktree cleanup uses non-forced `git worktree remove`. Unregistered orphan resources are quarantined atomically without purge. Session abandonment and task cancellation preserve worktrees non-destructively for operator review. Provider configuration files remain diagnostic-only. Event log and snapshot failures require human review.
- Facts that must not be forgotten: Handles format must be `req_<random_token>` with at least 96 bits entropy. MCP responses must remain concise and must not leak internal IDs, worktree paths, or event IDs. Exactly 11 MCP tools are currently registered in `tools/list`. `synesis doctor` is strictly read-only by construction. Repair administrative state is stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-plans\<plan-id>.json`. Repair execution lock is at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-execution.lock`. Repair execution journals are at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-executions\<execution-id>.jsonl`. Pre-mutation backups are stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-backups\<execution-id>\`.
