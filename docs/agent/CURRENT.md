# Current Task

## Identity

- Task ID: SYN-013D
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0165
- Latest checkpoint: CP-0172
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029, ADR-0030

## Objective

Implement Stage 2A: minimal stdio MCP server for safe workspace operations and provider configuration integration for Codex and Antigravity.

## Immediate slice

Stage 2A complete at CP-0172: project-local .agents/mcp.json now embeds absolute project root path so ensure_session returns ready even when Antigravity launches MCP with cwd=user home and no rootUri in initialize.

## Evidence ledger

- VERIFIED: Provider MCP capability discovery audit completed for Codex and Antigravity (`MCP_CONFIG_DISCOVERED`).
- VERIFIED: `AgentSessionService` created in `org.synesis.workspace.agent` with `ensureSession` method. All `:workspace:check` tests pass (74 tests).
- VERIFIED: `:mcp` Gradle module created, `:mcp:check` passes (18 tests), `:mcp:architectureCheck` passes.
- VERIFIED: JSON-RPC 2.0 stdio protocol handler (`McpProtocolHandler`), stdio server (`McpStdioServer`), and entrypoint (`SynesisMcpServer`) created in `org.synesis.mcp`.
- VERIFIED: Stale background MCP process PID 22360 terminated; stderr diagnostic startup log added to `SynesisMcpServer` logging PID, version, build commit, connection ID, provider, and cwd.
- VERIFIED: Root-source state machine implemented in `McpProtocolHandler` with URI percent-decoding, `rootUri`, `workspaceFolders`, `roots`, `roots/list`, and `notifications/roots/list_changed` handling.
- VERIFIED: Registered tool `synesis.ensure_session` returns concise status output `{"status":"ready","result":{"workspace":"isolated","pending":0}}`.
- VERIFIED: `ProviderApplicationService` and `synesis init` automatically install user-level Antigravity MCP configuration (`~/.gemini/config/mcp_config.json` and `~/.gemini/antigravity/mcp_config.json`) and project-local Codex config (`.codex/mcp.json`) idempotently while preserving unrelated entries and migrating obsolete project-local files.
- VERIFIED: Platform bundle updated (`:cli:platformBundle`), installed launcher `synesis init` tested on `SynesisTestProject`.
- VERIFIED: project-local `.agents/mcp.json` now embeds absolute project root path (`--project C:\Users\Liparakis\Desktop\SynesisTestProject`) so MCP boots with correct root regardless of provider launch cwd.
- VERIFIED: Installed MCP stdio process tested across 5/5 fresh launches with `initialize` (no rootUri, cwd=user home), `tools/list`, and two `synesis.ensure_session` calls — all returned `status: ready` 100% reproducibly.
- VERIFIED: Full root `./gradlew.bat check --no-daemon` passes cleanly (49 tasks).

## Current limitations

- Stage 2B tools (`synesis.read_file`, `synesis.apply_patch`, `synesis.run_command`, `synesis.get_next_action`) are deferred to Stage 2B.

## Verification target

`.\gradlew.bat check --no-daemon` (49 tasks).

## Immediate next action

Proceed with Stage 2B tool additions or task lifecycle operations as directed.
