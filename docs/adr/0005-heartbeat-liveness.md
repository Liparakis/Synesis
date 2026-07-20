# ADR-0005: Bounded application heartbeat and monotonic liveness

- Status: ACCEPTED
- Date: 2026-07-20

## Decision

Use the authenticated SLH1 control stream for fixed-size HEARTBEAT and
HEARTBEAT_ACK messages. A payload contains version, current session UUID,
non-negative sender sequence, related sequence (`-1` sentinel), and an opaque
sender-local monotonic marker. Sequence zero is initial; duplicate heartbeats
are ACKed without refreshing; future sequences/ACKs fail; the maximum signed
non-negative sequence is valid and the next send fails closed.

Start liveness only after reciprocal CONTROL_READY. Use one session-owned
one-shot schedule with `System.nanoTime()` and validated `Duration` bounds.
Defaults are 1s heartbeat, 3s suspicion, and 5s expiry. Derive state from
elapsed silence, emit `LIVE -> SUSPECT -> EXPIRED` history after a delayed
callback, and make EXPIRED irreversible. The first terminal close reason wins.
Only newest valid current-session HEARTBEAT/ACK activity refreshes liveness.

Dispatch public transitions through a per-session bounded daemon worker. Dropped
callbacks are diagnostic and cannot delay protocol processing. The worker and
schedule are cancelled during terminal cleanup.

## Rationale and tradeoffs

Application activity proves that the authenticated peer runtime processed a
message; QUIC packet activity alone does not. A one-shot schedule avoids
catch-up bursts after process pauses and makes delayed expiry deterministic.
Keeping liveness on the existing control stream avoids another ownership race.

This slice does not reconnect, select paths, cancel higher-level work, or expose
scheduler handles. Transport closure before application expiry remains
`TRANSPORT_CLOSED`, not `LIVENESS_EXPIRED`.

## Invalidation conditions

Revisit this ADR if control backpressure starves heartbeats, the selected QUIC
idle timeout closes connections before the documented expiry bound, or a future
protocol needs application-stream activity to refresh liveness. Wire or timing
changes require compatibility review.
