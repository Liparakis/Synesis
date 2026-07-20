# Synesis Link State Machines

The session states are `CONNECTING`, `LIVE`, `SUSPECT`, `EXPIRED`,
`CLOSED_GRACEFULLY`, `CLOSED_BY_PEER`, `CLOSED_BY_PROTOCOL`, and `FAILED`.

Before exposure, the path is:
`TRANSPORT_ESTABLISHED -> NEGOTIATING -> AUTHENTICATING -> SESSION_ESTABLISHING -> ESTABLISHED`.
Unsupported version, identity mismatch, malformed envelope, invalid proof, or
replay closes the handshake. No liveness scheduler starts before control ready.

## Control stream

`NOT_OPEN -> OPENING -> NEGOTIATING -> ACTIVE -> LOCAL_GOODBYE_SENT -> CLOSED`.
`ACTIVE` may receive `REMOTE_GOODBYE_RECEIVED` or enter `FAILED` on malformed,
oversized, wrong-session, duplicate-stream, illegal-flag, or illegal-order
input. `CONTROL_READY` is required on both sides before the session future
completes. A second stream is never promoted to control.

## Application liveness

Reciprocal `CONTROL_READY` selects `CONNECTING -> LIVE` once and starts one
one-shot schedule. Defaults are heartbeat 1s, suspicion 3s, expiry 5s; callers
may provide strictly ordered `Duration` values. Only a newest valid
current-session HEARTBEAT or HEARTBEAT_ACK refreshes monotonic activity in v1.
Local writes, malformed/stale messages, transport keepalive, path changes, and
arbitrary application traffic do not.

```text
silence < suspicion             LIVE
suspicion <= silence < expiry   SUSPECT
silence >= expiry               EXPIRED
```

Fresh activity before expiry emits `SUSPECT -> LIVE`. A delayed callback that
already observes expiry emits `LIVE -> SUSPECT -> EXPIRED` in that order.
EXPIRED is irreversible. Graceful close, peer close, protocol failure, and
transport failure select their own terminal states and cancel scheduling. The
first selected terminal reason wins; late messages and callbacks cannot replace
it. Completion and each transition are exactly once.

## Candidate gathering and racing

Provider work starts concurrently up to the configured provider bound. Each
provider is independently classified as success, invalid result, timeout,
cancelled, failure, or resource-limited; one failure does not suppress other
providers. A total deadline completes the operation with partial diagnostics,
and cancellation stops owned worker tasks.

The candidate path is:
`PROVIDER_INPUT -> NORMALIZED -> COMPATIBLE_PAIRS -> RACING -> CONTROL_READY_WINNER`
or `NO_WINNER`. Normalization rejects unsafe addresses and relay candidates;
pair generation rejects address-family mismatches. The race has independent
attempt, concurrency, stagger, and total bounds. Only an authenticated session
with the expected identity and reciprocal control readiness can select the
winner. All other attempts are cancelled, and late successful sessions are
closed. Provider and race diagnostics are bounded and contain redacted pair
identifiers rather than endpoint addresses.

## Demo application stream

`AUTHENTICATED_CONTROL_READY -> APPLICATION_STREAM_OPEN -> REQUEST_RECEIVED ->
RESULT_SENT -> APPLICATION_STREAM_CLOSED` is the only demo application path.
The stream is rejected before authentication or control readiness, accepts one
fixed operation, rejects malformed/oversized/invalid-UTF-8 frames, correlates
the result by UUID, and closes deterministically. It cannot reopen after close
begins and does not retry work across sessions.

## Opaque application stream

The SL-014 path is:
`CONTROL_READY -> APPLICATION_STREAM_OPEN -> OPAQUE_PAYLOAD_RECEIVED ->
OPAQUE_RESPONSE_SENT -> APPLICATION_STREAM_CLOSED`.

Opening before control readiness, a payload over the bound, an invalid `SLA1`
frame, a missing receiver, a timeout, or a terminal session selects a bounded
stream failure and closes that stream. The session's liveness and terminal
state remain authoritative; the stream cannot reconnect or revive the session.
