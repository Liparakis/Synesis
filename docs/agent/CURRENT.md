# Current Task

## Identity

- Task ID: SYN-013D
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0165
- Latest checkpoint: CP-0166
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029, ADR-0030

## Objective

Implement Stage 2A: a minimal `stdio` MCP server exposing exactly 5 safe
workspace tools (`synesis.ensure_session`, `synesis.read_file`,
`synesis.apply_patch`, `synesis.run_command`, `synesis.get_next_action`).

## Immediate slice

Stage 2A Slice 1 (foundation: provider audit, `AgentSessionService`, `:mcp`
module, stdio JSON-RPC 2.0 handler, `synesis.ensure_session` tool, bundle
integration and stdio process test) is DONE. Slice 2 (`synesis.read_file`,
`synesis.apply_patch`, `synesis.run_command`, `synesis.get_next_action`) is next.

## Evidence ledger

- VERIFIED: Provider MCP capability discovery audit completed for Codex and
  Antigravity (`MCP_CONFIG_DISCOVERED`).
- VERIFIED: `AgentSessionService` created in `org.synesis.workspace.agent` with
  `SessionResolutionRequest`, `AgentTaskIntent`, `AgentSessionContext`, and
  `ensureSession` method. All 70 `:workspace:check` tests pass.
- VERIFIED: `:mcp` Gradle module created, `:mcp:check` passes (8 tests),
  `:mcp:architectureCheck` passes (no `:cli` import).
- VERIFIED: JSON-RPC 2.0 stdio protocol handler (`McpProtocolHandler`), stdio server
  (`McpStdioServer`), and entrypoint (`SynesisMcpServer`) created in `org.synesis.mcp`.
- VERIFIED: Exactly one tool (`synesis.ensure_session`) registered.
- VERIFIED: `platformBundle` in `:cli` bundles `:mcp` jar; `bundleSmokeTest` runs
  stdio process test (`initialize`, `tools/list`, `tools/call synesis.ensure_session`).
- VERIFIED: Full root `./gradlew.bat check --no-daemon` passes cleanly (49 tasks).
- DEFERRED: Provider configuration installation (`PROVIDER_CONFIG_INSTALLED=false`).
- DEFERRED: Real Codex and Antigravity MCP validation (`REAL_CODEX_MCP_VALIDATED=false`).
- NOT YET IMPLEMENTED: `synesis.read_file`, `synesis.apply_patch`,
  `synesis.run_command`, `synesis.get_next_action`.

## Work completed (Stage 2A Slice 1)

- Provider MCP audit documented in `docs/architecture/zero-touch-provider-maturity.md`.
- Implemented `AgentSessionService` in `:workspace`.
- Created `:mcp` subproject with `build.gradle.kts`.
- Implemented `McpProtocolHandler`, `McpStdioServer`, and `SynesisMcpServer`.
- Added `McpServerTest` (8 tests).
- Added `McpCommand` in `:cli` delegating via reflection to `SynesisMcpServer`.
- Bundled `:mcp` jar in `platformBundle` and added stdio process test to `bundleSmokeTest`.
- Updated `docs/architecture/package-boundaries.md`.
- Root `check` (49 tasks) passes cleanly.

## Current limitations

- Provider configuration installation is not performed automatically.
- Real Codex / Antigravity MCP harness acceptance is deferred until Slice 2 completes.

## Verification target

`.\gradlew.bat check --no-daemon` (49 tasks).

## Immediate next action

Implement Stage 2A Slice 2: add `synesis.read_file`, `synesis.apply_patch`,
`synesis.run_command`, and `synesis.get_next_action` tools to `:mcp`.
