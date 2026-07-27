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

Record and commit the deferred coordination-feature register update. Leave
unrelated edits untouched; no capability implementation is authorized.

## Work completed

`SYN-018` hygiene is complete. `SYN-019` is corrected in `a87d3d8`; no
production type moved and the stale allowlist was narrowed to
`ProjectApplicationService.java`. The user explicitly authorized one narrow
bootstrap portability correction for the GitHub Actions failure; `SYN-014E`
remains paused. The correction moves a validated versioned payload before
applying immutable permissions and restores/removes a partially hardened
payload if hardening fails. The Unix stable launcher is explicitly restored to
0755 after atomic replacement, and uninstall makes immutable trees removable.

## Current failures

The original GitHub Actions failure is not reproducible on this Windows host.
Bootstrap `go test -count=1 ./...`, `go vet ./...`, and `git diff --check` pass
after the follow-up launcher/removal correction. All six CI target
cross-builds pass. The unrelated README edit remains outside this task and must
not be reverted or included. Linux test cleanup now explicitly removes
immutable installation roots before `t.TempDir` cleanup. A subsequent Linux CI
failure identified incorrect stripping of leading slashes from absolute
`file:` URIs in MCP root binding; that fix is now authorized for this slice.
`McpServerTest` and `./gradlew.bat clean check --no-daemon
--dependency-verification=strict` now pass locally.
The deferred-register cleanup leaves nine active capabilities, archives all
historical `SL-D-001` through `SL-D-030` IDs, and moves network cases to the
validation matrix. The three coordination-correctness entries
(`SL-D-037`–`SL-D-039`) now explicitly define revision invalidation,
out-of-band mutation enforcement, and wait-for cycle detection without
claiming implementation.

## Current verification

The deferred validator, Gradle check with strict dependency verification,
bootstrap Go tests, and bootstrap Go vet all pass for this documentation-only
slice. No production, CLI, or MCP files changed.
