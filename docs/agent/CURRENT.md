# Current Task

## Identity

- Task ID: SYN-019
- Status: ACTIVE
- Priority: P1
- Started checkpoint: CP-0230
- Responsible agent: primary architecture-closure engineer
- Related decisions: ADR-0001, ADR-0008; no architecture change

## Objective

Close the workspace application package architecture test without changing runtime behavior or public surfaces.

## Immediate slice

Inspection found one root production type, `ProjectApplicationService`, and the
architecture test had a stale five-type allowlist after the completed package
refactor. The allowlist was narrowed to the one deliberate stable facade.

## Verification target

Only deliberate stable application facades remain at the root; internal
responsibilities remain in subpackages; no runtime, module, CLI, MCP, provider,
schema, or event-format changes occurred.

## Immediate next action

Record the verified bootstrap portability correction and leave the unrelated
README edit untouched. No further implementation is authorized in this slice.

## Work completed

`SYN-018` hygiene is complete. `SYN-019` is corrected in `a87d3d8`; no
production type moved and the stale allowlist was narrowed to
`ProjectApplicationService.java`. The user explicitly authorized one narrow
bootstrap portability correction for the GitHub Actions failure; `SYN-014E`
remains paused. The correction moves a validated versioned payload before
applying immutable permissions and restores/removes a partially hardened
payload if hardening fails.

## Current failures

The original GitHub Actions failure is not reproducible on this Windows host.
Bootstrap `go test -count=1 ./...`, `go vet ./...`, `git diff --check`, and all
six CI target cross-builds pass after the portability correction. The unrelated
README edit remains outside this task and must not be reverted or included.
