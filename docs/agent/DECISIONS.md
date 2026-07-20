# Decisions / ADR Index

Product decisions are indexed in ADRs under `docs/adr/`.

## Persistence-system decisions

1. Repository files, not conversation history, are authoritative.
2. Exactly one primary task may be active.
3. Checkpoints are event-based: create them after meaningful verified slices and before stopping, not on a timer.

## Product ADRs

- ADR-0008: Synesis root with Link transport module — accepted; the existing Link implementation moves to `link/` while the root remains a small modular monolith.

- ADR-0007: Demo-only bounded work exchange — accepted for one authenticated `synesis-demo-work/1` request/result operation; no RPC, project semantics, authority, or reconnect behavior.

- ADR-0006: Bounded direct candidate gathering and racing — accepted for manual/local candidates, deterministic normalization and ranking, and authenticated control-ready winner selection; router discovery, relays, and reconnection remain out of scope.

- ADR-0001: One Gradle project with package boundaries — accepted.
- ADR-0002: Netty 4.2 native QUIC adapter — accepted for dependency and native-runtime validation.
