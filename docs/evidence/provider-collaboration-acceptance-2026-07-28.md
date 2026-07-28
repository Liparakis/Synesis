# Provider collaboration acceptance — 2026-07-28

## Evidence boundary

This report distinguishes MCP configuration, MCP connection, MCP mutation,
and native-hook evidence. A provider CLI being installed is not proof that its
MCP session completed.

| Provider | Configuration discovered | Real MCP connection | MCP mutation | Native hook |
|---|---|---|---|---|
| Codex 0.140.0 | `codex mcp list` found `synesis` | Attempted with `codex exec --ephemeral --json`; the configured installed path was stale and the bounded override did not expose Synesis tools | Not claimed | Not claimed |
| Claude Code 2.1.59 | CLI installed; `claude mcp list` reports no configured servers | Not attempted successfully: `claude auth status` reports `loggedIn: false` | Not claimed | Not claimed |
| Antigravity | No executable or configured provider found | Blocked by unavailable provider/quota | Not claimed | Not claimed |

The local Synesis MCP server itself passes the two-process, 11-tool, revision,
claim, handoff, release, and recovery test suites. Those are Synesis evidence,
not universal provider enforcement evidence. The task-tracker fixture remains
dirty by design and was not reset or modified by this validation.

## Exact commands

- `claude auth status` → `loggedIn: false`
- `claude mcp list` → no MCP servers configured
- `codex mcp list` → `synesis` configured but unsupported/stale executable path
- `codex exec --ephemeral --json ...` → MCP startup/handshake failure; no mutation
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → PASS
