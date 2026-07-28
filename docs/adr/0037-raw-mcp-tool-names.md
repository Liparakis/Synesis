# ADR-0037: Raw MCP Tool Names with Legacy Call Compatibility

- Status: Accepted
- Date: 2026-07-28
- Decision: SYN-024

## Context

Provider clients may concatenate a server name, configuration key, and wire
tool name, producing confusing names such as `synesis_synesis_mcp_read_file`.
Synesis controls the wire surface but cannot control provider decoration.

## Decision

The server/configuration namespace is `synesis` and `tools/list` advertises
exactly 11 raw names (`read_file`, `apply_patch`, and so on). The handler
normalizes legacy `synesis.*` names for one compatibility period without
advertising aliases. Existing capability names remain unchanged; only the
wire namespace is simplified.

## Consequences

New clients receive a stable, undecorated contract. Older clients continue to
work during migration. Provider-rendered names must be tested separately and
are not promised by Synesis.
