# SL-D-040 overlay foundation verification

- Date: 2026-09-08
- Scope: SL-D-040 implementation from `d9f7696`, checkpointed at
  `3bb5e28`, plus the bounded topology-propagation, runtime-acceptance, and
  configuration-cache verification follow-up in this slice
- Runtime: Java 25; configured Gradle execution was verified with the
  process-local loopback workaround documented below

## Passing evidence

| Check | Result |
|---|---|
| Link and relay main/test compilation, strict `-Xlint:all -Werror` | PASS |
| Link and relay public/protected Javadocs, `-Xdoclint:all -Werror` | PASS |
| `OverlayProtocolTest` | PASS; 11 found, 11 succeeded, 0 failed |
| `RelayWireCodecTest` | PASS; 1 found, 1 succeeded, 0 failed |
| `OverlayRelayServerTest` | PASS; 3 found, 3 succeeded, 0 failed |
| Full `:link:check :relay:check` | PASS; 78 Link tests and 4 relay tests, 0 failures/errors |
| Java 25 primitive compatibility probe | PASS; see `sl-d-040-java25-crypto-probe-2026-09-08.md` |
| `git diff --check` | PASS |

The focused Link suite covers signed bounded membership, directional X25519/
HKDF and ChaCha20-Poly1305 session records for both logical directions, replay
and tamper rejection, hop limits, deterministic eight-member topology
degree/connectivity, signed topology propagation through a direct peer, peer
transit, opaque in-memory relay forwarding, duplicate rejection, relay
fallback, connection caps, queue backpressure, and retry after queue admission
becomes available.

The real relay suite covers signed hello/ack binding, unauthorized-client
rejection, opaque bidirectional forwarding, concurrent frame delivery without
recipient confusion, and a disposable child process running the actual
`RelayMain` entry point with two authenticated clients. The process test uses
the real generated test classpath and a temporary relay identity directory;
the directory and child process are cleaned up by the test.

The relay wire test caught that the acknowledgement decoder omitted the client
ID and nonce from the signed record; the decoder now reads and verifies both
fields before accepting the relay identity.

## Process-local loopback workaround

The inherited workstation environment still fails before Gradle task
execution with `java.io.IOException: Unable to establish loopback connection`.
The passing configured checks used only this child-process environment:

```powershell
$env:TEMP = 'C:\t'
$env:TMP = 'C:\t'
$env:GRADLE_OPTS = '-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
.\gradlew :link:check :relay:check --no-daemon
```

The same environment passed the real localhost relay tests and the standalone
`RelayMain` process acceptance. No global Java, Gradle, Windows, repository,
or user settings were changed.

## Remaining evidence boundary

The configured checks and local relay/runtime acceptance are now proven, but
this slice does not claim physical controlled-NAT or Internet traversal. That
remains the separately scoped `SL-D-035` evidence gate. `SL-D-036` reconnect
and transport path migration remain excluded. The membership snapshot is a
bounded signed authority record; authority succession/revocation lifecycle
remains an explicit project-management boundary. Direct-peer establishment is
also still owned by the existing Link/onboarding path: the overlay consumes
authenticated `PeerSession`s, publishes deterministic desired neighbors, and
propagates signed topology, but does not become a reconnect manager.

The default inherited environment remains a host limitation, not a test
failure of overlay behavior:

```text
.\gradlew :link:check :relay:check --no-daemon
-> java.io.IOException: Unable to establish loopback connection
```
