# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Implement Post-MVP Hardening Slice 5B: safe provider configuration migration and identity-preserving project schema migration on top of the verified Slice 5A installation/update foundation.

## Immediate slice

Slice 5B provider/project migration is implemented. The five pre-existing CLI edits are preserved and excluded from this slice.

## Verification target

Migration tests PASS; `:workspace:check`, `:cli:check`, `:mcp:check`, root `check` PASS (49 tasks); bootstrap `go test -count=1 ./...` and `go vet ./...` PASS.

## Immediate next action

Run `scripts/agent-checkpoint.ps1` after committing the verified Slice 5B changes; preserve the five pre-existing CLI edits and do not begin Slice 5C real-provider validation.

## Work completed

Provider compare-and-set migration, external backup/journal evidence, stable launcher rendering, project schema detection, immutable prepared workflows, identity-preserving no-op verification, Doctor read-only migration findings, CLI workflows, focused tests, and all requested repository checks.

## Current failures

No verification failures. Real Codex and Antigravity acceptance remains intentionally deferred to Slice 5C; update-transaction activation integration remains outside this Java migration slice.
