# Supervisor coordination protocol

## Command path

1. A local supervisor loads its node identity and verified project context.
2. A provider action crosses the existing guardrail. Policy `BLOCKED` remains a
   hard denial; an owned foreign scope yields structured `REQUEST_OWNER`.
3. The requester submits a bounded, signed prediction contract to the local
   coordinator endpoint.
4. The coordinator authenticates the node, validates ownership/intent/version
   and state transition, appends one event, and publishes it to SSE clients.
5. The owner supervisor accepts exactly, accepts an equivalent contract, or
   revises/rejects it. All outcomes are events, never mutable status fields.

## Live stream and replay

The stream is ordered by the coordinator's project sequence. A reconnecting
supervisor supplies its last applied sequence; the coordinator first sends all
later durable events and then waits for new events. If a subscriber queue is
bounded or a process restarts, replay is the recovery mechanism. Clients apply
events idempotently by event ID and verify the chain/signature before projection.

## Failure semantics

Unavailable owner supervisor produces a retryable `STALE_CONTEXT`/pending
outcome, not an ownership transfer. Expired leases produce `PREDICTION_EXPIRED`
only when the contract expiry is reached; semantic owner records remain intact.
Invalid signatures, malformed contracts, sequence gaps, or scope/base drift
fail closed and are recorded as rejection evidence where an authenticated actor
is available.

## Trust boundary

The first server is loopback-only. Node identity is the authentication root;
logical supervisor and worker IDs are checked against the signed payload and
project context. Remote operation requires a later activation with HTTPS,
enrollment, replay authorization, and certificate/key rotation evidence.
