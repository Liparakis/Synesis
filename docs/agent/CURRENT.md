# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Complete Claude Code MCP provider support as the next implementation slice under SYN-014E.

## Immediate slice

Implement canonical provider ID `claude`, compatibility input alias `claude-code`, and project-scoped Claude Code `.mcp.json` management. Claude Desktop is explicitly out of scope.

## Verification target

Bootstrap Go tests/vet, root Gradle checks, direct/stable/`.cmd` 11-tool handshakes, opt-in Codex wire tracing, real Codex initialize/tools-list evidence, configuration restoration, and honest real-provider outcomes.

## Immediate next action

Create the next checkpoint documenting Claude Code MCP provider support and the passing focused/full verification.

## Work completed

Slice 5C.2 implements Codex TOML configuration correction, preservation, compare-and-set migration, backup/rollback, lifecycle integration, read-only Doctor inspection, and updater TOML path handling. Slice 5C.3 preserves integral JSON numbers, negotiates supported protocol versions including Codex `2025-06-18`, and adds disabled-by-default event-only MCP trace diagnostics. Slice 5C.4 reconstructed the dedicated fixture and preserved the safety backup. Slice 5C.5 reproduced the immediate missing-precondition `workspace_stale` result, proved the worker itself was ready, fixed exact connection binding selection and shared readiness, added `contentHash`, preserved genuine stale-hash rejection, added exact generation checks and clean/dirty stale recovery, added approved `run-tests.cmd` execution, built and activated `0.1.0-dev-local-5c9`, and completed the serialized MCP sequence with 11 tools, isolated mutation, passing tests, and a ready next action. The Claude Code slice now exposes canonical `claude`, accepts `claude-code` as an input alias, manages project `.mcp.json` without overwriting unrelated entries, and preserves the existing `.claude/settings.json` hook lifecycle.

## Current failures

Signed `0.1.0-dev-local-5c13` remains active with `5c12` retained as previous. Antigravity 2.3.1 is installed and its GUI tool enablement remains a separate acceptance boundary. Claude Desktop is intentionally unsupported. Five dirty legacy worktrees remain preserved; no force removal or process termination was used.
