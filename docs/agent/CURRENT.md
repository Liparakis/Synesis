# Current Task

## Identity

- Task ID: SYN-014E
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0191
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0033, ADR-0034, ADR-0035, ADR-0036, ADR-0037

## Objective

Complete REAL-PROVIDER ACCEPTANCE AND EVIDENCE CORRECTION — Post-MVP Hardening Slice 5C.2 from CP-0201.

## Immediate slice

Codex TOML migration is committed at `e278ea735318c6dbf84d8bcff3435034335c2322`, with updater-path follow-up `fe8b3e3`. The managed table matches the shape emitted by `codex mcp add`; unrelated settings are preserved by bounded editing and CAS migration.

## Verification target

Bootstrap Go tests/vet, root Gradle checks, signed Version A/B lifecycle evidence, invalid-bundle rejection, project identity/key preservation, provider-config restoration, and honest real-provider outcomes.

## Immediate next action

Run a bounded MCP stdio handshake probe against the stable launcher and classify the remaining Codex provider blocker without changing user configuration.

## Work completed

Slice 5C.2 implements Codex TOML configuration correction, preservation, compare-and-set migration, backup/rollback, lifecycle integration, read-only Doctor inspection, and updater TOML path handling. `:workspace:test`, `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, `go test ./...`, and `go vet ./...` pass.

## Current failures

Codex CLI `0.140.0` is authenticated and recognizes Synesis in `config.toml`, but bounded real execution did not expose Synesis tools and timed out during MCP handshake. Antigravity is unchanged and unvalidated. Root `check` is blocked by pre-existing trailing whitespace in CP-0201.
