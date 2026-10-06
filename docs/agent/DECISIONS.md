## 2026-10-05 — Capability publication and bounded inbox delivery

An accepted owner implements normally and explicitly signals readiness with
`get_next_action({completionRequested:true})`. The inbox then projects the
existing `publish_capability_implementation` tool with the exact request,
contract revision, intent, claim epoch, and captured source-tree hash. Ordinary
polls do not declare implementation complete or authorize publication.

Publication captures claimed dirty and untracked source through the existing
TaskSnapshotService, preserving HEAD, the normal index, and working files.
Stable snapshot refs retain the same immutable commit for later lane completion.
Publication authority and source evidence are checked again at the mutation
boundary; unchanged retries reuse the exact immutable implementation.

Inbox delivery remains at least once. Workflow action identity includes contract
and implementation revisions and exact continuation payloads, with retry hints
of 5 seconds and at most 3 unchanged follow-up responses. Generated provider
instructions stop and report that condition, including executable WAIT polling;
these hints do not revoke claims or close a WorkGroup and are not a server-side
counter of model calls.

## 2026-10-05 — ADR-0087 capability requests negotiate APIs after claim conflicts

Keep repository claims exclusive. A conflicting `ensure_session` denies the
claim without creating reciprocal free-text conflict requests, and reports the
exact owning intent lineage. The losing participant must establish a disjoint
consumer lane and declare the capability in `task.knownDependencies`, then use
the existing MCP `capability_request` lifecycle to negotiate a complete API
contract. No MCP tool or second prediction lifecycle is added.

Contract changes are event-sequenced revisions. Owner responses and requester
acceptance/counters are fenced to the exact revision observed; a requester must
accept an owner revision before its lane can build against that contract. After
acceptance, both lanes can implement. Requests with an active owner intent retain
its exact bound supervisor and worker scope; node identity alone cannot distinguish
two lanes in the same project. The owner lane cannot complete before it publishes
the exact capability implementation. A dependent lane cannot complete or integrate
before requester validation, and integration also waits for the matching owner
task snapshot. Existing v1-v3 capability payloads remain decodable; new requests
use v4 for full API terms and revision fencing.

This reuses capability ownership, publication, validation, isolated worktrees,
and integration rather than adding prediction-specific state. Claim authority
does not transfer during negotiation.

## ADR index update

- ADR-0087: Capability API negotiation after claim conflicts — accepted on
  2026-10-05.

## 2026-09-20 — ADR-0086 recovery-continuation admission

Fresh replacement admission now has a read-only recovery-continuation
preflight. When an authenticated session's requested role and exact ownership
selectors match an active recovery-held lane, MCP admission defers the normal
claim announcement so the replacement can observe `get_next_action` and make
the explicit continuation choice. The preflight creates no participant intent,
claim, grant, or successor; failed reads fall back to the existing fail-closed
claim path. Ordinary reviewers remain required to claim a disjoint report or
test path when no matching recovery lane exists. ADR-0086 records the boundary
and `McpAdmissionRecoveryTest` proves no authority transfer occurs before the
explicit continuation request.

## ADR index update

- ADR-0086: Recovery continuation admission before claim declaration — accepted
  for SYN-082 on 2026-09-20.

## 2026-09-18 — ADR-0081 invisible canonical project metadata boundary

The filesystem-boundary milestone is activated as SYN-081. `.synesis` remains
the fixed cross-platform metadata root. A small project-storage seam will own
directory creation, no-follow root validation, and best-effort Windows Hidden
normalization. Hidden state is derived local presentation state: it is never
serialized, copied as authority, consulted for identity, or used by
authentication/authorization, registry, daemon, MCP, or Git logic. Existing
project writes that already target `.synesis` remain in place; external host
administration/workspace/runtime roots and the visible `AGENTS.md` integration
file remain documented exceptions rather than being moved speculatively.

## ADR index update

- ADR-0081: Invisible canonical project metadata boundary — accepted for SYN-081.

Implementation evidence: `ProjectMetadataStorage` is the only production
owner of DOS Hidden handling; supported project lifecycle paths re-ensure it,
and the complete write-surface inventory is recorded in
`docs/evidence/SYN-081-invisible-project-metadata-2026-09-18.md`.

## 2026-09-18 — ADR-0080 portable project identity and replica model

The existing `.synesis/project.json` UUID remains the sole canonical logical
project identity. A replica is a separately initialized local realization with
fresh private/node state; an explicit fork creates a new UUID. Discovery may
store multiple paths for one ID but never grants authority. Project context is
bound after Link authentication by higher-level consumers before mutation; core
`PeerSession` remains project-agnostic.

## ADR index update

- ADR-0080: Portable project identity and replica model — accepted for SYN-080.

## 2026-09-13 — SL-D-040 Checkpoint 3A Docker process-isolation lab

Checkpoint 3A uses disposable Docker Linux namespaces on the current Windows
Docker Desktop/WSL2 engine as the local process-isolation substrate. The lab
may supply one explicit signed six-member snapshot to a test-only process
adapter and must bootstrap the desired edges through the existing compact SLB2
host/join flow. Existing authenticated Link/overlay classes remain the only
transport and membership-validation surfaces. Endpoint refresh after container
replacement is a controlled lab descriptor update, not a shipped-CLI claim.
Native Ubuntu, KVM, physical NAT, Internet traversal, membership authority in
the CLI, and source `.synesis` state remain outside this decision. ADR-0079 is
the architecture boundary. Two cycles passed using the resulting harness; the
evidence is recorded in
`docs/evidence/sl-d-040-checkpoint-3a-docker-2026-09-13.md`.

## ADR index update

- ADR-0079: Docker process-isolation lab for SL-D-040 Checkpoint 3A — accepted
  for the bounded local lab slice.

## 2026-09-13 — SL-D-040 Checkpoint 2 native multi-peer seam

Checkpoint 2 keeps the accepted overlay architecture above direct
`PeerSession` bindings. The native QUIC adapter now gives each accepted QUIC
connection one responder handshake state, gives authenticated clients a
safe handler for peer-initiated application streams, and retains the existing
bounded application-stream protocol. Overlay topology publication is
transition-driven and propagation is sequential across the current direct
peer snapshot to avoid a publication storm; neither change creates a new
route or membership authority.

Physical loss removes the exact direct binding and its local topology
advertisement from route inputs, while signed membership remains unchanged.
There is no distributed tombstone, session resumption, path migration, relay
redesign, UI/SSE projection, or process/VM harness in this checkpoint. ADR-0077
records the architecture seam and its consequences.

## ADR index update

- ADR-0077: Native multi-peer QUIC stream state and bounded overlay convergence
  — accepted for SL-D-040 Checkpoint 2.

## 2026-09-10 — SYN-055 visual direction

The current explicit brief supersedes the older hidden runtime indicator and
uncapped desktop width. Use a quiet dot/text reachability indicator, bounded
page-specific widths, and one resource header. These are presentation changes
within ADR-0069; no new architecture ADR or deferred capability is needed.
Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`.

# Decisions / ADR Index

- ADR-0080: Native Windows setup wizard for the bootstrap installer — accepted
  for the bounded SYN-067 slice; uses native Win32 controls, existing verified
  activation, owned per-user deep-link registration, custom-root discovery,
  and metadata cleanup that preserves user data.

Product decisions are indexed in ADRs under `docs/adr/`.

- ADR-0078: Compact Link first-contact bootstrap — accepted for the bounded
  `SL-D-032` slice; the inviter key, short-lived admission, and one signed
  endpoint hint remain in the compact token while complete candidate
  descriptors move to the authenticated application stream. Legacy SLO1/SLA2,
  direct-only transport, expected identity, and no-TOFU boundaries remain.

- ADR-0071: External maximum-protector adapter and signed candidate gate —
  accepted for `SYN-009E`; a versioned release-environment adapter supplies
  real commercial protection and private evidence, while the existing
  bootstrap signer and trust root remain the only release-signing boundary.

- ADR-0070: Synesis maximum-protection commercial release profile — accepted
  for `SYN-009E`; readable developer builds remain separate from opt-in
  protection-lite and licensed maximum-release packaging, the existing
  distribution/integrity foundations are extended in place, virtualization and
  protected packing are selective and evidence-gated, legitimate VMs are not
  rejected by presence alone, and anti-analysis is fail-safe and non-hostile.

- ADR-0069: Installed browser UI over the local control plane — accepted for
  SYN-053; a locked TypeScript/React/Vite/Tailwind `web-ui` module produces
  packaged static resources served same-origin by the existing loopback server,
  with fragment bootstrap, typed snapshot/SSE state, and no Node runtime.

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

- ADR-0068: Bounded live Link/overlay control owner — accepted for SYN-052
  continuation; a small in-process owner retains authenticated Link sessions,
  composes existing overlay views and propagation seams, requires an explicit
  signed membership snapshot, and keeps the CLI `UNCONFIGURED` until a real
  authority source exists.

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

## 2026-09-19 — ADR-0085 continuation grants remain outside review authority

The fresh blind continuation target showed that a replay projection must keep
the consumed continuation authorization visible for audit without presenting
it as review authority. The continuation grant is tagged and excluded from
review-obligation projection; the collaboration projection and
`ReviewValidationService` reject a review decision against it with
`REVIEW_SELF_APPROVAL_FORBIDDEN`. No automatic review or identity transfer was
added.
## 2026-09-19 — ADR-0083 lawful recovery continuation and successor authority

The remaining SYN-082 continuation gap is activated within the existing task.
Recovery reconciliation remains responsible only for fencing and preserving
unfinished work. A fresh exact participant may explicitly choose a derived
recovery-held continuation action through the existing generic coordination
surface; the action creates a new successor intent under the append lock while
preserving task/work lineage and never reviving the source runtime authority.

## ADR index update

- ADR-0083: Lawful recovery continuation and successor authority — accepted for SYN-082.
