# Current Task

## Identity

- Task ID: SYN-015
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0212
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0001, ADR-0008

## Objective

Complete the staged package-structure refactor task in independently validated slices without changing runtime behavior.

## Immediate slice

Complete `STRUCT-1C — MCP packages` only, then checkpoint and stop before `STRUCT-1D`.

## Verification target

Reorganize only the `mcp` module into stable entrypoint, application, protocol, and stdio transport packages while preserving the exact 11-tool MCP surface, wire schemas, provider behavior, and zero stale production references.

## Immediate next action

Inventory current MCP package FQNs, move one coherent package group, run `:mcp:check`, and continue only within STRUCT-1C until its validation and checkpoint are complete.

## Work completed

`SYN-014E` is paused pending structural work. `STRUCT-1A` completed in `:project-record`, `:coordination`, and `:link` at `376f2d2ce6003b32d28994b19b6728926ab0af6e`. `STRUCT-1B` completed in `:workspace` across commits `b67ac1c` and corrective ownership commit `248889a`, including project, provider, lifecycle, and infrastructure package moves, test-package alignment, and architecture tests. Required module, root Gradle, Go, and focused MCP checks passed.

## Current failures

None in verified behavior. Remaining structural issue: `workspace.application` still directly coordinates some provider-specific hook/configuration implementations through existing orchestration code; no new abstraction was introduced in this package-only slice.
