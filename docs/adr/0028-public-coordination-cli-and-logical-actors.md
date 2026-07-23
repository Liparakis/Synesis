# ADR-0028: Public coordination CLI and logical actor binding

## Status

Accepted for SYN-012.

## Decision

Expose the coordination vertical slice through the standalone `synesis` CLI:
`coordination`, `task`, `ownership`, `supervisor`, `events`, `prediction`,
`speculation`, and `integration` command trees. Commands operate against an
initialized project and a loopback coordinator; no source checkout of Synesis
is required by the target project.

Keep the existing bounded signed command/event envelope and add a version-two
optional logical actor binding containing supervisor and worker identifiers.
Version-one commands remain decodable for the existing in-process/demo paths.
Coordinator authorization matches the logical actor to the immutable
prediction contract or task/ownership claim, so a node identity alone cannot
turn a requester profile into an owner profile.

## Context and alternatives

The internal coordination service was verified, but the product surface still
exposed only a demo command. A second vocabulary or a separate coordinator
service would have duplicated the protocol and weakened the acceptance claim.
The CLI is therefore a thin adapter over the existing service, event store,
projection, and speculation gate. A database, broker, or remote enrollment
remains out of scope until measured evidence activates that work.

## Security and failure behavior

All writes remain bounded, signed, hash-chained, idempotent, and loopback-only.
Invalid actor/node/project combinations fail with stable authorization errors;
event replay remains cursor-based and at-least-once. Public commands return
stable machine-readable markers and nonzero exit codes on rejected transitions.
The public acceptance harness uses an external initialized Git project and
two independent profile directories.

## Fitness functions and invalidation

CLI help must expose every required command tree; targeted tests must cover
version-two command round trips and requester/owner negative authorization;
the external two-process harness must reach `RETIRED` with live replay and a
clean integration gate. Reconsider this decision if remote enrollment,
multi-host transport, or measured scale requires a different adapter boundary.
