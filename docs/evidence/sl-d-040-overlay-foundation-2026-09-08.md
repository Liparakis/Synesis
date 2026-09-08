# SL-D-040 overlay foundation verification

- Date: 2026-09-08
- Scope: uncommitted SL-D-040 implementation on top of `ca681ca`
- Runtime: Java 25; strict `-Xlint:all -Werror` direct compilation where
  configured Gradle execution was unavailable

## Passing evidence

| Check | Result |
|---|---|
| Link main sources, strict Java 25 compilation | PASS |
| Relay main sources, strict Java 25 compilation | PASS |
| Relay test sources, strict Java 25 compilation | PASS |
| Link and relay public/protected Javadocs, `-Xdoclint:all -Werror` | PASS |
| `OverlayProtocolTest` through the cached JUnit Platform launcher | PASS; 9 found, 9 succeeded, 0 failed |
| `RelayWireCodecTest` through the cached JUnit Platform launcher | PASS; 1 found, 1 succeeded, 0 failed |
| Java 25 primitive compatibility probe | PASS; see `sl-d-040-java25-crypto-probe-2026-09-08.md` |

The focused Link suite covers signed bounded membership, directional X25519/
HKDF and ChaCha20-Poly1305 session records for both logical directions, replay
and tamper rejection, hop limits, deterministic eight-member topology
degree/connectivity, peer transit, opaque in-memory relay forwarding,
duplicate rejection, relay fallback, connection caps, queue backpressure, and
retry after queue admission becomes available.

The relay wire test covers signed hello/ack binding, opaque `SLF1` frame
round-trip, and frame acknowledgements. During verification it caught that
the acknowledgement decoder omitted the client ID and nonce from the signed
record; the decoder now reads and verifies both fields before accepting the
relay identity.

## Blocked evidence

| Check | Result and boundary |
|---|---|
| `./gradlew :link:check --no-daemon` | BLOCKED before task execution: `java.io.IOException: Unable to establish loopback connection` |
| `./gradlew :relay:check --no-daemon` | BLOCKED before task execution: `java.io.IOException: Unable to establish loopback connection` |
| `OverlayRelayServerTest` through the cached JUnit Platform launcher | BLOCKED before Netty event-loop creation: both tests fail with `IllegalStateException: failed to create a child event loop`; root `java.net.SocketException: Invalid argument: connect` |

The loopback failure is a host/runtime limitation, not a passing test or a
proof of relay behavior. Consequently this slice does not claim a full Link
regression, a real socket relay/concurrency pass, or physical controlled-NAT
traversal. A compatible host must run those checks, plus the independent
six-to-eight-node runtime acceptance, before SL-D-040 can be closed.
