# Provider collaboration acceptance — 2026-07-28

## Evidence boundary

This report distinguishes MCP configuration, MCP connection, MCP mutation,
and native-hook evidence. A provider CLI being installed is not proof that its
MCP session completed.

| Provider | Configuration discovered | Real MCP connection | MCP mutation | Native hook |
|---|---|---|---|---|
| Codex 0.140.0 | `codex mcp list` found `synesis`; entry was updated to the current local install | Real `codex exec --ephemeral --json` launched Synesis and emitted a real `ensure_session` call; the harness cancelled the call before a result | Not claimed (no mutation) | Not claimed |
| Claude Code 2.1.59 | Project `.mcp.json` now points at the current local install | Not completed: `claude auth status` reports `loggedIn: false` | Not claimed | Not claimed |
| Antigravity | No executable or configured provider found | Blocked by unavailable provider/quota | Not claimed | Not claimed |

The local Synesis MCP server itself passes the two-process, 11-tool, revision,
claim, handoff, release, and recovery test suites. Those are Synesis evidence,
not universal provider enforcement evidence. The task-tracker fixture remains
dirty by design and was not reset or modified by this validation.

## Exact commands

- `claude auth status` → `loggedIn: false`
- `claude mcp add --scope project ...` → task-tracker `.mcp.json` updated to the current local install
- `claude auth status` → `loggedIn: false`; no authenticated provider run
- `codex mcp remove synesis` followed by `codex mcp add synesis -- ...\synesis.bat mcp --provider codex` → current local install
- `codex exec --ephemeral --json ...` → real `ensure_session` call started; harness cancellation prevented a result; no mutation
- direct installed launcher probe → valid MCP `initialize` response with server name `synesis`
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → PASS
