# ADR-0041: Ten-tool MCP lifecycle surface

## Status

Accepted for SYN-035.

## Decision

Synesis advertises exactly ten raw MCP tool names:

`ensure_session`, `read_file`, `apply_patch`, `run_command`, `get_next_action`,
`request_coordination`, `respond_coordination`,
`publish_capability_implementation`, `finish_lane`, and `cancel_lane`.

Capability publication and ordinary lane completion are distinct operations.
`publish_capability_implementation` requires the exact server-issued capability
request handle supplied by the durable next-action envelope. `finish_lane` is
the normal completion operation for an isolated mutation lane.

Coordination requests and responses use strict discriminated `{kind, payload}`
schemas. Unknown fields, variants, or caller-supplied identifiers are rejected
deterministically. Implementation validation is a strict response variant;
there is no separate validation tool.

No legacy aliases or decorated `synesis.*` wire names are advertised or
accepted. The first release has not established a compatibility contract, so
ambiguous historical names are removed instead of preserved.

## Consequences

Providers must follow the executable action envelope returned by
`get_next_action` and the managed Synesis Manual. This avoids guessing whether
an identifier is an inbox item, intent, capability request, or snapshot.
The MCP tool count is smaller and the lifecycle semantics are explicit, while
the underlying application services remain shared by CLI and MCP adapters.

## Supersedes

ADR-0037, which described an eleven-tool surface and a temporary decorated-name
compatibility period.
