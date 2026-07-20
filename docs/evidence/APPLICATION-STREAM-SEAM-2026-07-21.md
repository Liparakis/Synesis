# SL-014 application-stream seam evidence

## Status

`VERIFIED` - 2026-07-21. This evidence covers only the bounded Link seam. It
does not implement or claim project records, storage, synchronization, or any
higher-level Synesis semantics.

## Scope proved

- `PeerSession` exposes authenticated `remoteNodeId()` and control readiness
  through `isUsable()`.
- One transport-neutral opaque byte exchange is available only after reciprocal
  control readiness.
- Link owns the `SLA1` marker, explicit outer length framing, 4,096-byte
  payload bound, five-second operation deadline, stream limit, and cleanup.
- Pre-ready, malformed/version/oversized, terminal-session, and missing-handler
  paths reject safely.
- Eight sequential exchanges reuse and release streams; no stream-limit leak
  was observed.
- Two independently generated JVM profiles exchanged opaque bytes over an
  authenticated QUIC session; the response was checked byte-for-byte and the
  remote identity was already verified by the Link handshake.

## Verification commands

Focused:

```text
gradlew.bat :link:test --tests org.synesis.link.session.ApplicationStreamBindingTest --tests org.synesis.link.transport.ApplicationStreamCodecTest --tests org.synesis.link.transport.NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream --tests org.synesis.link.transport.NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses --dependency-verification=strict
```

Result: `BUILD SUCCESSFUL`.

Full:

```text
gradlew.bat clean check --dependency-verification=strict
```

Result: `BUILD SUCCESSFUL`; Link and CLI checks passed. Existing CLI sources
and build files were unchanged.

## Claim boundary

This is two-process evidence, not a physical two-machine claim. The seam does
not provide retries, reconnect, persistence, ordering across streams,
authorization above Link, or payload interpretation. `SYN-001` remains
blocked until a later task implements and verifies those higher-level rules.
