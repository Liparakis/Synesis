# ADR-0004: One authenticated control stream per session

- Status: ACCEPTED
- Date: 2026-07-20

## Decision

Reuse the SL-005 bidirectional stream as the session control stream. The
authenticated initiator opens exactly one stream; the responder accepts the
first authenticated handshake stream and rejects any later stream as a
duplicate control stream. `CONTROL_READY` must be exchanged before the public
`PeerSession` future completes. No heartbeat or application message is
processed in this slice.

Control frames use bounded `SLH1` framing with a four-byte big-endian transport
length, fixed frame header, and a maximum 4,096-byte payload. A graceful close
sends one `GOODBYE`, accepts one `GOODBYE_ACK`, and force-closes after a bounded
two-second wait. Terminal completion is selected once and is never rewritten
by later messages.

## Rationale and tradeoff

Reusing the authenticated stream avoids a second ownership race and binds the
control path to the already-authenticated session. The tradeoff is that future
application streams need a later stream-allocation policy; that is deliberately
outside SL-006.
