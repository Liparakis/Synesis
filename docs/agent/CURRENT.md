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

Complete `QUALITY-DEDUP — Evidence-based deduplication` only, then checkpoint and stop before warning cleanup.

## Verification target

Audit and remove only genuinely identical duplicated infrastructure or lifecycle logic with narrow ownership, direct tests, and no behavior changes.

## Immediate next action

Inventory and rank duplicated infrastructure/lifecycle logic, record the audit, and extract only one proven identical group at a time with affected-test validation.

## Work completed

`SYN-014E` is paused pending structural work. `STRUCT-1A` completed in `:project-record`, `:coordination`, and `:link` at `376f2d2ce6003b32d28994b19b6728926ab0af6e`. `STRUCT-1B` completed in `:workspace` across commits `b67ac1c` and corrective ownership commit `248889a`. `STRUCT-1C` completed in `mcp` at `5cb0656`; `STRUCT-1D` completed in `cli` at `958a039`. Required module and root validation passed; the disposable CLI MCP launcher still reports its pre-existing missing MCP runtime classpath.

## Current failures

None in verified behavior. Remaining structural issue: `workspace.application` still directly coordinates some provider-specific hook/configuration implementations through existing orchestration code; no new abstraction was introduced in this package-only slice.
