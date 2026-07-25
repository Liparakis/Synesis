# Next Session

- Active task: SYN-014E ACTIVE at Slice 5C.2 following CP-0202
- Repository branch: master
- Last checkpoint: CP-0202
- Last passing command: root `check`, `:workspace:check`, `:cli:check`, `:mcp:check`, `go test ./...`, and `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Run one bounded MCP stdio handshake probe against the stable launcher; do not start an unbounded Codex process or mutate unrelated Codex settings.
- Unresolved limitations: Speculative continuation deferred. Actual worktree cleanup uses non-forced `git worktree remove`. Unregistered orphan resources are quarantined atomically without purge. Session abandonment and task cancellation preserve worktrees non-destructively for operator review. Provider configuration files remain diagnostic-only. Event log and snapshot failures require human review.
- Facts that must not be forgotten: Handles format must be `req_<random_token>` with at least 96 bits entropy. MCP responses must remain concise and must not leak internal IDs, worktree paths, or event IDs. Exactly 11 MCP tools are currently registered in `tools/list`. `synesis doctor` is strictly read-only by construction. Repair administrative state is stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-plans\<plan-id>.json`. Repair execution lock is at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-execution.lock`. Repair execution journals are at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-executions\<execution-id>.jsonl`. Pre-mutation backups are stored under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-backups\<execution-id>\`.
- Slice 5 boundary: installed payloads are immutable and retained; activation is a validated atomic pointer replacement; update execution requires a prepared plan; provider/project migration is compare-and-set and identity-preserving; no process termination, old-version deletion, remote polling, or MCP tool changes.
- Slice 5C.2 evidence: Codex uses `%USERPROFILE%\\.codex\\config.toml`; `codex mcp get/list` recognizes the Synesis command and args; unrelated text is preserved by the migration; exact 11-tool MCP surface remains unchanged; real Codex tool discovery remains unproven due handshake timeout.
