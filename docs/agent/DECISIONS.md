# Decisions / ADR Index

Product decisions are indexed in ADRs under `docs/adr/`.

## Persistence-system decisions

1. Repository files, not conversation history, are authoritative.
2. Exactly one primary task may be active.
3. Checkpoints are event-based: create them after meaningful verified slices and before stopping, not on a timer.

## Product ADRs

- ADR-0009: Zero-configuration terminal onboarding — accepted; listener-first
  signed invitations with one-use capability admission, authentication-time
  consumption, bounded pre-auth reservation release, ephemeral transport TLS,
  automatic identity reuse/creation, and `DemoCli` retained as fallback.
- ADR-0010: Standalone CLI and development distribution — accepted; `cli` owns
  Picocli, terminal output, exit mapping, QR rendering, and Gradle Application
  distributions while Link owns onboarding through typed events and failures.

- ADR-0011: First signed shared decision record — approved for its required
  Link prerequisite only; SYN-001 remains blocked until SL-014 verifies the
  seam. Record storage and sync are not authorized.
- ADR-0012: Bounded authenticated Link application-stream seam — accepted for
  SL-014; transport-neutral bytes only, with Link retaining identity,
  readiness, framing, limits, deadlines, liveness, and cleanup.

- ADR-0008: Synesis root with Link transport module — accepted; the existing Link implementation moves to `link/` while the root remains a small modular monolith.

- ADR-0007: Demo-only bounded work exchange — accepted for one authenticated `synesis-demo-work/1` request/result operation; no RPC, project semantics, authority, or reconnect behavior.

- ADR-0006: Bounded direct candidate gathering and racing — accepted for manual/local candidates, deterministic normalization and ranking, and authenticated control-ready winner selection; router discovery, relays, and reconnection remain out of scope.

- ADR-0001: One Gradle project with package boundaries — accepted.
- ADR-0002: Netty 4.2 native QUIC adapter — accepted for dependency and native-runtime validation.
- ADR-0022: Codex PreToolUse adapter and trust boundary — accepted for
  SYN-009B.1; bounded `apply_patch` parsing, shared guardrail evaluation,
  project-local lifecycle, and no trust-database mutation.
- ADR-0026: Stable flat installation layout — accepted for SYN-009D; one
  OS-conventional root is canonical, sibling staging and temporary rollback
  make activation recoverable, user PATH ownership is explicit, and providers
  never reference version directories.
