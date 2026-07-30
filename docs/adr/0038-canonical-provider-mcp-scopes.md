# ADR-0038: Canonical provider MCP scopes

- Status: Accepted
- Date: 2026-07-29

## Decision

Each provider has one canonical Synesis MCP configuration scope. Codex uses
the user TOML configuration, Claude uses the project `.mcp.json`, and
Antigravity uses its provider-specific user configuration with an explicit
project argument. Provider installation migrates stale Synesis entries from
legacy scopes while preserving unrelated servers and settings.

## Rationale

Writing the same server to global and project/provider-specific files creates
duplicate processes, ambiguous roots, stale session generations, and repeated
provider onboarding. A single effective entry makes the active MCP process and
project binding auditable.

## Boundaries

This decision does not change the 11-tool MCP contract, provider credentials,
or claims about native filesystem-hook enforcement. Migration must be
idempotent and fail closed on malformed configuration.
