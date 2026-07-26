# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Complete IMPLEMENTATION AND REAL ACCEPTANCE — Codex MCP handshake isolation, Hardening Slice 5C.4 retry from CP-0204.

## Immediate slice

The source base remains `3c0019e976ec5b3e54e45fc6088976ce6d9c0fbf`. The active Codex entry remains the version-independent `.cmd` wrapper; the stale PATH-preferred `.bat` is not used. Slice 5C.4 reconstructed the dedicated fixture, exercised supported reconciliation/cleanup plans, and verified the active payload's direct MCP handshake.

## Verification target

Bootstrap Go tests/vet, root Gradle checks, direct/stable/`.cmd` 11-tool handshakes, opt-in Codex wire tracing, real Codex initialize/tools-list evidence, configuration restoration, and honest real-provider outcomes.

## Immediate next action

Obtain an approval-capable official interactive Codex surface, run the real fixture prompt through Synesis tools, then record the tool execution and post-test invariants; do not mutate global configuration.

## Work completed

Slice 5C.2 implements Codex TOML configuration correction, preservation, compare-and-set migration, backup/rollback, lifecycle integration, read-only Doctor inspection, and updater TOML path handling. Slice 5C.3 preserves integral JSON numbers (so JSON-RPC `id: 0` remains `0`), negotiates supported protocol versions including Codex `2025-06-18`, and adds disabled-by-default event-only MCP trace diagnostics. Slice 5C.4 created safety backup `C:\Users\Liparakis\Desktop\SynesisTestProject-Safety-20260726-034531`, classified legacy MCP files as obsolete Synesis-generated entries, ran reconcile/cleanup dry-run→prepare→show→execute without forced operations, rebuilt the fixture on branch `synesis-codex-acceptance`, passed the deterministic Java baseline, initialized twice idempotently, and verified the active Version B bundle `0.1.0-dev-local-5c7` returns exactly 11 tools through the `.cmd` MCP entry.

## Current failures

The current API exposes no approval-capable interactive Codex prompt driver. Official CLI help confirms interactive and approval flags, but `codex exec` is noninteractive and the available desktop tools cannot inject the required prompt into an approval-capable session. Successful real tool execution and mutation are therefore not claimed. Five dirty legacy worktrees remain preserved for operator review; no force removal or process termination was used, and global Codex configuration was not changed.
