# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Complete IMPLEMENTATION AND REAL ACCEPTANCE — Codex workspace freshness consistency, Hardening Slice 5C.5 from CP-0205.

## Immediate slice

The source base for this slice was `24751a60aaf4836a51835fe6388830b6a9719cec`; the fix commits are `1a97373` and `0df0ff7`. The active Codex entry remains the version-independent `.cmd` wrapper; the stale PATH-preferred `.bat` is not used. Slice 5C.5 adds exact connection binding resolution, one shared workspace-readiness predicate, read content hashes for mutation preconditions, exact worker/control generation checks, clean stale-worker reallocation, and bounded `run-tests.cmd` execution through `run_command`.

## Verification target

Bootstrap Go tests/vet, root Gradle checks, direct/stable/`.cmd` 11-tool handshakes, opt-in Codex wire tracing, real Codex initialize/tools-list evidence, configuration restoration, and honest real-provider outcomes.

## Immediate next action

Finalize the Slice 5C.5 acceptance report from the recorded source, updater, and MCP evidence; do not mutate global configuration.

## Work completed

Slice 5C.2 implements Codex TOML configuration correction, preservation, compare-and-set migration, backup/rollback, lifecycle integration, read-only Doctor inspection, and updater TOML path handling. Slice 5C.3 preserves integral JSON numbers, negotiates supported protocol versions including Codex `2025-06-18`, and adds disabled-by-default event-only MCP trace diagnostics. Slice 5C.4 reconstructed the dedicated fixture and preserved the safety backup. Slice 5C.5 reproduced the immediate missing-precondition `workspace_stale` result, proved the worker itself was ready, fixed exact connection binding selection and shared readiness, added `contentHash`, preserved genuine stale-hash rejection, added exact generation checks and clean/dirty stale recovery, added approved `run-tests.cmd` execution, built and activated `0.1.0-dev-local-5c9`, and completed the serialized MCP sequence with 11 tools, isolated mutation, passing tests, and a ready next action.

## Current failures

The API still has no approval-capable interactive Codex prompt driver, so the post-fix wire acceptance is not claimed as a desktop interactive run. The updater accepted the development-only local manifest without a release signature; signed-update activation remains unclaimed. Five dirty legacy worktrees remain preserved for operator review; no force removal or process termination was used, and global Codex configuration was not changed.
