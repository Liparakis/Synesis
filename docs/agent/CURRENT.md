# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Implement Post-MVP Hardening Slice 5B.2: verified project-metadata restoration after injected migration and replay failure, without adding a production schema transition.

## Immediate slice

Slice 5B and 5B.1 are complete. Slice 5B.2 is limited to exact metadata backup/restoration, restart-safe restoration journaling, injected failure tests, and restoration-aware Doctor findings; the five pre-existing CLI edits are preserved and excluded from this slice.

## Verification target

Focused replay/transaction tests, workspace/CLI/MCP/root checks, and bootstrap Go tests/vet.

## Immediate next action

Create CP-0199 with `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`, then stop before Slice 5C real-provider validation.

## Work completed

Slice 5B provider/project migration and 5B.1 transaction integration are complete. Slice 5B.2 adds content-hashed external backup manifests, exact atomic metadata restoration, target-race protection, restart/idempotency journaling, injected partial/replay/malformed/restore-failure tests, and restoration Doctor findings. `go test -count=1 ./...`, `go vet ./...`, `:coordination:check`, `:workspace:check`, `:cli:check`, `:mcp:check`, and root `check` all pass.

## Current failures

Real Codex and Antigravity acceptance remains intentionally deferred to Slice 5C. No older production project schema transition exists by design; restoration is proven through the test-only injected migration seam.
