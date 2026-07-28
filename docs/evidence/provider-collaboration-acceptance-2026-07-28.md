# Provider collaboration acceptance — 2026-07-28

## Evidence boundary

This report distinguishes MCP configuration, MCP connection, MCP mutation,
and native-hook evidence. A provider CLI being installed is not proof that its
MCP session completed.

| Provider | Configuration discovered | Real MCP connection | MCP mutation | Native hook |
|---|---|---|---|---|
| Codex 0.140.0 | `codex mcp list` found `synesis`; entry points to the current local install | Real `codex exec --ephemeral --json --dangerously-bypass-approvals-and-sandbox` initialized Synesis and completed `ensure_session` | Confirmed: exact `src/task_tracker.py` claim returned `status=ready`; a separate claimed probe file was created with `apply_patch` and verified by `read_file` with matching revision hash | Not claimed |
| Claude Code 2.1.59 | Project `.mcp.json` now points at the current local install | Not completed: `claude auth status` reports `loggedIn: false` | Not claimed | Not claimed |
| Antigravity | No executable or configured provider found | Blocked by unavailable provider/quota | Not claimed | Not claimed |

The local Synesis MCP server itself passes the two-process, 11-tool, revision,
claim, handoff, release, and recovery test suites. Those are Synesis evidence,
not universal provider enforcement evidence. The task-tracker fixture remains
dirty by design and was not reset or modified by this validation.

## Process-level MCP acceptance

Two independent installed Synesis MCP processes were launched against the
task-tracker fixture with provider identities `codex` and `claude`. The Codex
process acquired `src/task_tracker.py`; the Claude process received a
structured `overlapping_claim` response before mutation. After the Codex
process closed, a new Claude MCP process reacquired the same path successfully,
demonstrating clean-EOF release and reacquisition. This proves process-level
MCP collaboration, not that the Claude/Codex model CLIs themselves completed
an authenticated run.

The fixture initially contained historical event records whose shifted wire
codes were decoded as collaboration events. Stable decoding now distinguishes
legacy dependency-invalidated payloads, and `synesis collaboration status`
replays the existing dirty fixture successfully without deleting events.

## Exact commands

- `claude auth status` → `loggedIn: false`
- `claude mcp add --scope project ...` → task-tracker `.mcp.json` updated to the current local install
- `claude auth status` → `loggedIn: false`; no authenticated provider run
- `codex mcp remove synesis` followed by `codex mcp add synesis -- ...\synesis.bat mcp --provider codex` → current local install
- `codex exec --ephemeral --json ...` → first attempt was cancelled by the harness before a result
- `codex exec --ephemeral --json --dangerously-bypass-approvals-and-sandbox ...` → real `ensure_session` completed with `status=ready` and an isolated worktree; no source file was edited
- real Codex MCP mutation probe → `apply_patch` created `provider_acceptance_probe.txt` in the isolated worktree with revision `1fb78c34cf37b61394f119294c12ccc71333f571bcc8d2a4e9ed58916433be72`; `read_file` returned the same content hash
- `claude -p ... --output-format json --permission-mode bypassPermissions` → `Not logged in · Please run /login`
- direct installed launcher probe → valid MCP `initialize` response with server name `synesis`
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → PASS
