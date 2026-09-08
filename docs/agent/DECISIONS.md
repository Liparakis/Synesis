# Decisions / ADR Index

Product decisions are indexed in ADRs under `docs/adr/`.

## Persistence-system decisions

1. Repository files, not conversation history, are authoritative.
2. Exactly one primary task may be active.
3. Checkpoints are event-based: create them after meaningful verified slices and before stopping, not on a timer.

## Product ADRs

- ADR-0067: Local browser-facing control plane — accepted for SYN-052;
  one in-process versioned loopback HTTP/SSE adapter reuses authoritative
  projections and onboarding services, with a thin Link/overlay projection,
  one-time local bootstrap, session/CSRF protection, semantic explicit DTOs,
  and bounded subscriber handling.

- ADR-0065: Coordinated direct UDP/QUIC traversal — accepted for
  implementation; preserves the existing authenticated Netty QUIC and Link
  identity/session boundaries while adding optional configured STUN discovery,
  signed bilateral offer/answer signaling, bounded simultaneous attempts, and
  explicit unsupported-topology diagnostics. Relays, reconnect, and
  distributed state remain excluded.

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

- ADR-0008: Synesis root with Link transport module — accepted; the existing Link implementation moves to `link/` while
  the root remains a small modular monolith.

- ADR-0007: Demo-only bounded work exchange — accepted for one authenticated `synesis-demo-work/1` request/result
  operation; no RPC, project semantics, authority, or reconnect behavior.

- ADR-0006: Bounded direct candidate gathering and racing — accepted for manual/local candidates, deterministic
  normalization and ranking, and authenticated control-ready winner selection; router discovery, relays, and
  reconnection remain out of scope.

- ADR-0001: One Gradle project with package boundaries — accepted.
- ADR-0002: Netty 4.2 native QUIC adapter — accepted for dependency and native-runtime validation.
- ADR-0022: Codex PreToolUse adapter and trust boundary — accepted for
  SYN-009B.1; bounded `apply_patch` parsing, shared guardrail evaluation,
  project-local lifecycle, and no trust-database mutation.
- ADR-0026: Stable flat installation layout — accepted for SYN-009D; one
  OS-conventional root is canonical, sibling staging and temporary rollback
  make activation recoverable, user PATH ownership is explicit, and providers
  never reference version directories.
- ADR-0029: Provider-session lease evidence and liveness grace periods — accepted for SYN-014C.
- ADR-0030: Immutable reconciliation plans, locks, and journals — accepted for SYN-014C.
- ADR-0031: Ambient task cancellation via synesis.cancel_task — accepted for SYN-014C.
- ADR-0032: Strict non-destructive worktree preservation on session abandonment and task cancellation — accepted for
  SYN-014C.
- ADR-0033: Unified read-only DoctorService diagnostics — accepted for SYN-014D.
- ADR-0034: Immutable repair plans and external administration storage — accepted for SYN-014D.
- ADR-0035: Pre-mutation administrative file backups and exact atomic rollback — accepted for SYN-014D.
- ADR-0036: Strict repair ownership boundary preserving signed event log, control checkout, and provider config —
  accepted for SYN-014D.
- ADR-0037: Versioned immutable installation with atomic pointer activation — accepted for SYN-014E.
- ADR-0051: Explicit MCP project authority across linked Git worktrees — accepted for SYN-045; an initialized
  launcher-pinned control checkout remains authoritative, equivalent provider roots are verified by project ID and Git
  common directory, and mismatched or Synesis-assigned roots fail closed.
- ADR-0052: Transactional first project initialization — accepted for SYN-046;
  the existing managed baseline safety gate runs before new project-local
  `.synesis` state is created, preserving clean retry behavior after a rejected
  checkout.
- ADR-0053: Explicit call-local completion requests — accepted for SYN-049;
  remove caller-selected completion policies and require explicit,
  revalidated completion projection.
- ADR-0055: Provider-neutral runtime authentication and session continuity —
  Result A for the existing core plus a generic authentication seam. The
  provider capability model is approved: ordinary Codex/Claude stdio remain
  `SESSION_BOUND`, a future provider-native assertion is
  `NATIVE_CONTINUITY`, and Synesis-supervised Codex App Server is the first
  `MANAGED_CONTINUITY` target. The isolated inherited-pipe prototype passed.
  The version-matched `codex-cli 0.145.0` child-launch investigation passed
  topology and exact-thread restart/resume but classified dynamic private
  proof ingress as `FAIL`. A follow-up stock isolated-runtime experiment
  classified conservative dedicated-home delivery as `PASS-A` at feasibility
  level, with proof rotation and exact same-home resume. Production code was
  not changed. See the protected-carrier, managed-attachment,
  provider-boundary, child-launch, and isolated-runtime evidence.
- ADR-0054: Preserve structured capability dependencies through admission —
  accepted for SYN-049; verify the rebuilt MCP path first and reuse the
  existing durable capability lifecycle, patching only a proven boundary.
- ADR-0056: Bounded stock-Codex `MANAGED_CONTINUITY` implementation — accepted
  for SYN-051; dedicated retained Codex homes and App Servers, exact thread
  resume, selected `env_vars` proof delivery, rotation, and generation fencing
  behind the existing provider-neutral authority seams.
