# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Complete REAL-PROVIDER ACCEPTANCE — Post-MVP Hardening Slice 5C from CP-0199, preserving the five pre-existing CLI edits and adding only acceptance-proven fixes.

## Immediate slice

Slice 5B.2 is complete. Slice 5C validated signed Version A/B installation, coexistence, rollback, reactivation, and the unchanged 11-tool MCP surface in an isolated project. A bootstrap defect was fixed: Windows stable launchers now hash manifests through .NET SHA-256 instead of relying on `Get-FileHash` under `-NoProfile`. Real Codex execution reached the installed CLI but was rejected by its ChatGPT-account model configuration; Antigravity is installed as a GUI but no usable real MCP session was established.

## Verification target

Bootstrap Go tests/vet, root Gradle checks, signed Version A/B lifecycle evidence, invalid-bundle rejection, project identity/key preservation, provider-config restoration, and honest real-provider outcomes.

## Immediate next action

Review CP-0200 and obtain a supported real Codex/Antigravity session path before claiming Slice 5C complete.

## Work completed

Slice 5B provider/project migration and 5B.1 transaction integration are complete. Slice 5B.2 adds content-hashed external backup manifests, exact atomic metadata restoration, target-race protection, restart/idempotency journaling, injected partial/replay/malformed/restore-failure tests, and restoration Doctor findings. `go test -count=1 ./...`, `go vet ./...`, `:coordination:check`, `:workspace:check`, `:cli:check`, `:mcp:check`, and root `check` all pass.

## Current failures

Codex real-session validation failed because the installed CLI rejected both its configured `gpt-5.6-luna` model and a `gpt-5` override for the ChatGPT account; Antigravity has an installed GUI but no verified MCP/session launch path was available. No older production project schema transition exists by design; restoration remains proven through the test-only injected migration seam.
