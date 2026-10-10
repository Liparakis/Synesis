## 2026-10-10 — Codebase remaster: subsystems with no production caller

The remaster survey found subsystems that no production code constructs. They
are tested, and some may be parked on purpose. The owner decided to keep them
unchanged and review each one separately. The remaster neither deletes nor
restructures them, and this entry does not activate any of them.

- `link`: `OverlayReconciler`, `OverlayKeyAgreement`, and `OverlayEnvelope`.
- `relay`: `OverlayRelayClient`, used only by tests and the acceptance entry
  point.
- `workspace`: Codex managed continuity (`ManagedCodexProcessLauncher`,
  `lifecycle.codex.runtime`, `WindowsJobObjectProcessTreeSupervisor`, and the
  parts of `ManagedAttachmentService` only tests call).
- `workspace`: project migration backup and restore
  (`ProjectMigrationService.executeInjected`,
  `ProjectMigrationRestorationService`).
- `workspace`: `ManagedBaselineTransactionService.recover` and its phase hook.
- `workspace`: `ProjectCommandMaintenanceService.cleanupDeadAnchor`.
- `coordination`: the ownership registry stack.
- `link`: the legacy SYN1 and SLO1 host flows in `Onboarding`, which only tests
  produce.

For each, the open question is whether to wire it in, keep it parked with a
stated reason, or remove it.

Plan: `docs/plans/codebase-remaster-2026-10-10.md`; ADR-0088.

## 2026-09-19 — SYN-082 continuation-authority scope review

The explicit successor-authority goal activates the bounded continuation slice
under the existing active task `SYN-082` and ADR-0083. The user-authorized
overnight extension additionally activates crash-prefix/replay proof, hostile
multi-lane recovery fencing, and mandatory independent-review/reviewer-loss
continuation through existing review semantics. It permits only read-only
recovery eligibility projection, explicit successor acquisition through the
existing generic coordination action, exact binding/epoch/scope fencing,
immutable snapshot access, and evidence through review/integration closure.
It does not activate automatic assignment, claim release, automatic review,
new MCP tools, grace-period changes, remote recovery, or source-repository
Synesis coordination.

## 2026-09-18 — SYN-082 lifecycle review

The supplied blind interruption evidence activated a bounded review of the
existing ordinary-MCP recovery trigger. The implemented slice only connects
ordinary admission to the existing lease reconciliation plan and prevents
terminal participants or stale plans from being mutated. Automatic assignment,
claim release, auto-review, new MCP tools, changed grace periods, and any
remote/hosted recovery capability remain outside this task.

Evidence: `docs/evidence/SYN-082-lifecycle-recovery-2026-09-18.md`; ADR-0082;
continuation scope: ADR-0083.

## 2026-09-18 — SYN-081 promotion review

The supplied `.synesis` canonical-storage and Windows Hidden requirement is
promoted into the sole active task `SYN-081` under ADR-0081. This is a bounded
filesystem-boundary and UX-hygiene evolution. It does not activate new
coordination, security, cloud, installer, Link, MCP, Git, or replica behavior.
The deferred register was reviewed; no deferred capability is promoted.

Implementation status: the bounded storage/visibility slice is implemented and
verified; no deferred capability was activated or expanded. Checkpoint
`CP-0973` records closure evidence.

## SYN-081 — Invisible canonical project metadata boundary

**Status:** PROMOTED TO ACTIVE TASK; ADR-0081; 2026-09-18
**Area:** Project-local storage, visibility normalization, root hygiene
**Activation trigger:** Explicit requirement that Synesis bookkeeping remain beneath `.synesis`, stay hidden by convention/attribute, and never be confused with user project content or authorization.
**Scope gate:** Reuse existing `.synesis` paths and lifecycle/storage code; add only a narrow shared seam, focused evidence, and documentation. Keep external host admin/workspace/runtime roots and visible `AGENTS.md` as explicit exceptions.

## 2026-09-18 — SYN-080 promotion review

The user-supplied universal project identity and replica requirement is
promoted into the sole active task `SYN-080` under ADR-0080. This promotion
authorizes only the bounded identity, replica, registry, and project-facing
admission slices described there. Core Link remains project-agnostic; no
cloud/relay/federation, source `.synesis` state surgery, or MCP catalog change
is activated.

## SYN-080 — Universal portable project identity and replica model

**Status:** PROMOTED TO ACTIVE TASK; ADR-0080; 2026-09-18
**Area:** Workspace identity, replicas, discovery, and project-facing admission
**Activation trigger:** Explicit requirement that the same logical Synesis project survive local path/machine/Git-state changes while identity remains separate from membership and authority.
**Scope gate:** Existing project identity storage is reused; core Link remains a physical authenticated-session module; all project context is higher-level and post-authentication.

## 2026-09-14 — SYN-079 result-screen cleanup review

No deferred capability is activated. This continuation changes only result layout, concise failure presentation and diagnostic disclosure. Existing backend operations, ownership and data boundaries remain unchanged.

## 2026-09-14 — SYN-079 layout/gradient completion review

No deferred capability is activated. Work remains installer presentation and rendering; backend behavior, coordination and data ownership boundaries are unchanged.

## 2026-09-14 — SYN-079 visual-defect follow-up review

No deferred capability is activated. This completion corrects native paint, contrast, progress and log presentation only; installer backend behavior and data ownership boundaries remain unchanged.

## 2026-09-14 — SYN-079 installer-flow brief review

The user authorized shorter native installer flows and truthful progress presentation within the existing SYN-079 task. No deferred capability is activated: no shortcuts, daemon stop/cancellation, network service, provider setup or new filesystem/registry operation was added. Existing install/repair/uninstall functions only gained optional observer callbacks. Final visual acceptance is unverified after the user stopped Computer Use.

## SYN-055 final artistic-direction checkpoint review — 2026-09-10

Reviewed the current brief and final diff against this register. Typography,
responsive width, colors, accessible markup, and header polish preserve
ADR-0069. No endpoint, authority, network capability, or deferred behavior was
promoted. Existing entries remain deferred on their own activation criteria.
Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`.

# Deferred Functionality Register

## SL-D-041 — Windows setup wizard and owned installation deep-link lifecycle

**Status:** SUPERSEDED by replacement task SYN-067; checkpoint CP-0944
**Priority:** Current
**Area:** Windows installation and native deep-link activation
**Current verified capability:** The Go bootstrap now has a native Windows
setup wizard around the verified local bundle operations, with path selection,
Back/Next/Cancel flow, progress, owned per-user `synesis://` registration,
metadata-preserving uninstall, the existing Synesis logo, and mandatory dark
client/control/title-bar treatment. Screenshot-driven refinement is active for
selection-card styling and reliable logo compositing.
**Missing capability:** Direct button-navigation and pixel-level UI QA remain
unverified because the desktop inspection helper was unavailable; this does
not expand the bounded implementation into privileged installation or
universal browser/network onboarding.
**Remaining boundary:** Direct button-navigation and pixel-level UI QA remain
unverified because the desktop inspection helper was unavailable; privileged
machine-wide installation and universal browser/network onboarding remain out
of scope.
**Reason deferred:** The prior installed-product slices proved the daemon and
activation seams but deliberately left the native installer presentation and
normal installer registration lifecycle outside their scope. SYN-067 supplied
the bounded implementation and evidence.
**Activation trigger:** The operator explicitly requested a concrete Windows
setup experience with install-path selection and a metadata-removal choice
after observing the unusable console installer and non-opening invitation URI.
**Evidence required before planning:** For any future expansion, retain the
source trace of the existing bootstrap
activation/uninstall/registry boundaries, the accepted native activation ADR,
focused ownership tests, and a visual reference for the requested wizard
controls are recorded in SYN-067 and ADR-0080; final build and test evidence is
recorded in `docs/evidence/SYN-067-windows-setup-wizard-2026-09-13.md` and
checkpoint CP-0944 (the checkpoint files were removed from the tree; Git history has them).
**Public-claims impact:** Synesis may claim only a per-user Windows wizard and
owned `synesis://` registration after the bounded installer evidence passes.
It must not claim privileged machine-wide installation, daemon termination,
automatic provider setup, project-data cleanup, or universal browser/network
onboarding.
**Related documents:** `docs/adr/0075-native-deep-link-registration-and-installed-activation.md`, `docs/adr/0080-windows-setup-wizard.md`, `docs/installation/bootstrap-install.md`, `docs/evidence/SYN-056-installed-daemon-2026-09-10.md`
**Last reviewed:** 2026-09-13

## SYN-057 promotion review — 2026-09-11

The operator explicitly promotes deterministic multi-project runtime selection
and deep-link routing as SYN-057. The promotion covers the narrowly bounded
resolver now implemented: signed v2 target lookup through
`KnownProjectRegistry` and ephemeral local SLO1 selection. It does not promote
an unsigned `projectId`, selected-project persistence, filesystem crawling,
runtime broadcast, OS protocol registration, membership authority, controlled-
NAT, or hosted coordination.

The architecture and implementation evidence are recorded in ADR-0074 and the
SYN-057 routing evidence. Runtime-restart operation continuity remains outside
this promotion.

## SYN-056 promotion review — 2026-09-10

The explicit installed-product goal promotes a bounded installation shell over
the existing project-local control plane. ADR-0073 selects one `cli`-module
daemon with authenticated loopback IPC and existing runtime launch/reuse. It
does not promote a second HTTP control plane, global project authority,
filesystem crawling, peer aggregation, privileged startup, OS URI registration,
or duplicate Link parsing/verification. Installer registration and a later
cross-project runtime-selection adapter remain deferred until their existing
seams are verified.

The r5 post-CONNECTED audit does not promote membership authority. The CLI
intentionally supplies no signed membership snapshot; physical Link
authentication therefore remains distinct from project membership and the
overlay remains `UNCONFIGURED`. Any membership creation, approval, revocation,
or persistence lifecycle requires a separately activated authority task under
SL-D-040 and must not be added to SYN-056 or the daemon.

## SYN-055 promotion review — 2026-09-09

The explicit screenshot-led UI request promotes a bounded frontend polish
task over the already accepted installed browser UI. It does not promote a
new backend, project-switching protocol, authority model, network capability,
provider behavior, or browser-owned coordination logic. ADR-0069 remains the
architecture boundary: one install-bundled static frontend, one same-origin
loopback server, one typed snapshot/SSE client, and existing onboarding
commands.

Promotion requires six real-data surfaces, shared visual primitives, truthful
empty/unconfigured/security states, preserved session/SSE/onboarding behavior,
frontend quality gates, packaged static verification, and installed UI smoke.
The screenshots are visual authority only; their example records, metrics,
actions, and relationships are not product truth. No new deferred capability
is introduced, and no production fixture or remote mutation is authorized.

## Current SYN-009E disposition — 2026-09-09

The free `PROTECTION_LITE` scope is complete and is being closed and pushed.
The commercial maximum profile remains deferred / external dependency until a
licensed, version-pinned protector adapter and production signing authority are
available. The maximum-release gate must continue to fail closed; protection-
lite output must never be relabeled as maximum.

## SYN-009E promotion review — 2026-09-09

The explicit maximum-protection goal promotes a release-engineering task in
the existing `SYN-009C`/`SYN-009D` distribution lineage. It does not promote a
new runtime service, protocol, provider, or frontend architecture. ADR-0070
and ADR-0071 select additive release-only profiles and an external commercial
adapter over the current Gradle platform bundle,
Go bootstrap integrity, stable installation, native launcher, relay
distribution, and packaged UI seams.

Promotion requires a readable developer profile, an opt-in protection-lite
build with private mappings/provenance, narrow reflection/resource/native keep
rules, an installed protected acceptance harness, and explicit evidence for
every Seven Ring. Rings 1–6 remain blocked; Ring 7 is a partial release seam
until a licensed protector is installed and its actual customer-style output is
exercised. Open-source renaming/shrinking and native baseline checks are
supporting evidence, not substitutes for the commercial rings.

This task must not introduce a home-grown VM or packer, destructive
anti-debugging, host surveillance, a customer downgrade flag, production keys,
commercial credentials, public release, push, tag, or remote mutation. The
deferred register remains part of every release/security checkpoint. The
adapter/signing seam is now present, but its commercial execution and all ring
evidence remain deferred until the licensed tool and release signing authority
are supplied.

## SYN-053 promotion review — 2026-09-08

The explicit user goal promotes the first installed browser UI over the
already-verified local control plane. This is a product-surface task, not a
new networking, membership, provider, or authority capability. ADR-0069 fixes
the boundary: one static frontend module, one same-origin loopback server, and
one typed snapshot/SSE client; no second daemon, public API, or browser-owned
business logic. The pushed backend baseline is `2be88cf`.

Promotion requires the production bundle, installed CLI/static acceptance,
real control-plane/session/SSE evidence, frontend quality gates, and explicit
empty/unconfigured/security checks. It does not promote Electron, a native
GUI, cloud hosting, OS URI registration, telemetry, or a Link/overlay redesign.

Current evidence passes for the implemented local scope; the remaining
checkpoint is to record the local commit boundary. The CLI overlay remains
`UNCONFIGURED` until a legitimate signed membership authority is supplied.

## SYN-052 promotion review — 2026-09-08

The explicit user goal promotes a local backend control surface, not the
cancelled `SL-D-027` production GUI. Current-source and planning
re-investigation found reusable loopback HTTP, durable projections, provider
diagnostics, and Link onboarding seams; ADR-0067 records the bounded
architecture. The activation does not promote remote administration, a
frontend, a generic proxy, durable browser replay, or any deferred reconnect,
NAT, or provider capability.

This is the active register of realistic future Synesis capabilities. It is
not a roadmap, release promise, implementation permission, or evidence that a
capability exists. Committed implementation work belongs in `TASKS.md`.

Synesis coordinates independent development harnesses; it does not own harness
intelligence, model context, memory, reasoning, task decomposition, ranking, or
automatic harness-code rewriting. Direct connectivity is serverless and
operator-mediated. Unsupported topologies must fail explicitly.

Promotion requires an activation trigger, the listed evidence, an explicit task
with acceptance criteria, and exactly one `ACTIVE` task. Historical IDs and
dispositions are preserved in [`DEFERRED_FUNCTIONALITY_HISTORY.md`](../archive/DEFERRED_FUNCTIONALITY_HISTORY.md).

## SYN-051 implementation status — 2026-09-04

The explicitly activated bounded implementation now includes same-App-Server
generation-1 creation, provider-thread ownership/pinning, pending transport
quarantine, a trusted provider-persistence boundary, and a fresh end-to-end
Worker-A generation-1 death plus exact generation-2 resume acceptance. The
in-scope workspace and MCP regression classes now pass. Worker B/A-B
validation and full SYN-049 acceptance remain out of scope for this bounded
objective and are the remaining repository-level acceptance criterion. No new
capability or MCP tool is activated.

## SYN-051 review — 2026-09-03

SYN-051 activates only the explicitly authorized bounded stock-Codex
`MANAGED_CONTINUITY` implementation from ADR-0055/ADR-0056/ADR-0061. The
selected runtime mode is shared normal provider-home auth with Synesis-enforced
provider-thread ownership, proof admission, broker pinning, and process-tree
fencing. It does not activate Claude continuity, anonymous continuity, shared
App Servers, arbitrary fleet launching, connectivity work, or Review/Doctor
redesign. Atomic ownership, process containment, private proof delivery,
matched build/install provenance, and real restart acceptance remain gates. No
second identity graph or broad deferred capability is introduced.

## SYN-050 review — 2026-09-03

SYN-050 does not activate a broad deferred capability or a new identity graph.
Its bounded provider-neutral design investigation selects Result A for the core
and formally approves `MANAGED_CONTINUITY` as a supported product profile, with
Synesis-supervised Codex App Server as the first target. The isolated
inherited-pipe model passed. The version-matched real Codex App Server
child-launch investigation passed topology and exact-thread restart/resume,
while its dynamic private-context boundary remains `FAIL`. The follow-up stock
isolated-runtime experiment passed conservative dedicated-home delivery, exact
same-home resume, and proof rotation; its classification is `PASS-A` at
feasibility level. The managed implementation remains unimplemented, and
ordinary Codex and Claude stdio remain
`SESSION_BOUND`. The existing recovery path remains semantically different
from same-session attachment.
The strict exact-connection fence, monotonic provider lifecycle, and ten-tool
MCP surface remain in scope. No connectivity, harness, or Review/Doctor
redesign is activated. Static wrappers, anonymous brokers, process lineage,
and model-visible bearers do not establish continuity. Evidence:
`docs/evidence/SYN-050-protected-carrier-prototype-2026-09-03.md`,
`docs/evidence/SYN-050-managed-attachment-design-2026-09-03.md`,
`docs/evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md`,
`docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`,
and `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`.

## SYN-049 review — 2026-09-02

The explicit completion lifecycle and structured dependency-admission
verification are bounded corrections to existing implemented seams. No
deferred capability is activated, and no new connectivity, orchestration,
provider, or review/Doctor capability is promoted.

The fresh SYN-049 run proved those two scoped seams but stopped at a separate
post-compliance review/session lifecycle stall. That observation is evidence
only; it does not activate or authorize a review/Doctor redesign.

## SL-D-031 — Serverless direct internet connectivity

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Authenticated direct QUIC works with bounded local candidates and local/two-process
evidence.
**Missing capability:** A serverless direct-internet path that reports unsupported topologies explicitly.
**Reason deferred:** Cross-network behavior, candidate exchange, NAT behavior, and firewall support boundaries are not
physically verified.
**Activation trigger:** A requirement for direct internet operation without a Synesis-hosted or third-party service.
**Evidence required before planning:** Two-machine matrix covering direct IPv6, manual forwarding, router mapping, hole
punching, unsupported NATs, identity, cleanup, and failure diagnostics.
**Public-claims impact:** No universal internet reachability claim; no hosted service dependency is implied.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md),
`docs/protocol/SYNESIS_LINK_V1.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-032 — Compact first-contact bootstrap and authenticated descriptor exchange

**Status:** DEFERRED
**Current refinement:** The bounded SLB2 minimization refinement is complete
for the activated direct-onboarding scope; broader coordinated traversal and
physical-evidence capability remains deferred.
**Area:** Connectivity and onboarding
**Current verified capability:** Signed, single-use invitations and bounded candidate descriptors exist for the current
direct-session path.
**Activated scope:** A compact signed first-contact token for one-way direct onboarding where one endpoint hint is
reachable, with the full signed candidate descriptors exchanged only after authenticated control readiness, plus a
source-first minimization refinement that preserves the signed identity/endpoint and admission boundary. The existing
SLO1/SLA2 path remains the compatibility/coordinated-traversal path.
**Missing capability:** Coordinated endpoint exchange for compatible NATs, project-bound offer/answer policy, and physical
traversal evidence remain outside this completed compact-bootstrap slice.
**Reason deferred:** The current goal explicitly bounded the implementation to a signed direct endpoint hint and local
authenticated descriptor exchange; expanding to coordinated traversal would change the transport and deployment boundary.
**Activation trigger:** A separately authorized controlled two-network/NAT requirement with a concrete traversal contract
and acceptance matrix.
**Evidence delivered for this slice:** Strict parser vectors for expiry, single use, canonical form, tamper, wrong identity,
wrong protocol, endpoint bounds, measured URI sizes, and authenticated post-control descriptor exchange are recorded in
`docs/evidence/sl-d-032-compact-bootstrap-2026-09-13.md`.
**Evidence required before planning:** Controlled two-network candidate exchange, project-bound offer/answer authority,
identity/replay/failure diagnostics, and explicit physical reachability limitations.
**Public-claims impact:** Invitations remain operator-exchanged; no discovery, rendezvous, relay, universal NAT, or
Internet reachability claim is made.
**Related documents:** `docs/protocol/SYNESIS_LINK_V1.md`, `docs/security/THREAT_MODEL.md`, `docs/demo/FIRST_DEMO.md`
**Last reviewed:** 2026-09-13; SLB2 minimization refinement complete for current scope

## SL-D-033 — Manual port-forwarded endpoint support

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Operators can exchange bounded direct candidates; public endpoint support is not
verified.
**Missing capability:** A user-supplied public host/IP and UDP port path with authenticated direct connection and clear
failure reporting.
**Reason deferred:** Endpoint authorization, address-family handling, firewall guidance, and cleanup evidence are
incomplete.
**Activation trigger:** Users need a dependable direct-connectivity escape hatch without router mutation.
**Evidence required before planning:** Two-machine IPv4/IPv6 manual-forward tests, wrong-endpoint rejection, firewall
diagnostics, identity checks, and cleanup.
**Public-claims impact:** Manual forwarding would be an explicit operator setup path, not automatic reachability.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md),
`docs/operations/TWO_MACHINE_TESTING.md`
**Last reviewed:** 2026-07-27

## SL-D-034 — Optional router port mapping

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** No router mapping implementation is claimed.
**Missing capability:** One opt-in product capability covering PCP, NAT-PMP, and UPnP IGD mapping with explicit
ownership and cleanup.
**Reason deferred:** Router trust, consent, renewal, crash cleanup, exposure, and protocol coverage require one security
and operations decision.
**Activation trigger:** A supported router-mapping requirement is approved with an explicit user-consent model.
**Evidence required before planning:** Protocol-specific adapter tests, mapping ownership/renewal/cleanup evidence,
forged-gateway tests, exposure review, and physical router matrix results.
**Public-claims impact:** No automatic router mapping claim until opt-in, ownership, and cleanup are verified.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md),
`docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-035 — Coordinated UDP/QUIC hole punching

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Human-mediated signed SLO1/SLA2 exchange, bounded candidate pairing, and coordinated direct attempts exist on the existing Link UDP/QUIC endpoint.
**Missing capability:** Controlled-NAT and physical two-network evidence for compatible and unsupported topologies.
**Reason deferred:** NAT behavior, consent, resource limits, and failure classification are not physically verified.
**Activation trigger:** A serverless direct-connectivity task requires traversal of compatible NATs without relay
infrastructure.
**Evidence required before planning:** Cone-NAT, symmetric-NAT, CGNAT, firewall, replay, spoofing, timeout,
resource-limit, and unsupported-topology tests.
**Public-claims impact:** No universal NAT-traversal claim; incompatible topologies must fail explicitly.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md),
`docs/security/THREAT_MODEL.md`, `docs/protocol/SYNESIS_LINK_V1.md`
**Last reviewed:** 2026-07-27

## SL-D-036 — Direct-session reconnection

**Status:** DEFERRED
**Area:** Session lifecycle
**Current verified capability:** Closed or expired sessions do not resurrect; fresh authenticated sessions are
supported.
**Checkpoint 1 delivered:** Local overlay adjacency reconciliation after
terminal physical loss is implemented under the active `SL-D-036` task, using a
retained signed candidate descriptor and the existing fresh authenticated
Link/session path. This does not add session resumption, path migration,
authority succession, or work replay.
**Missing capability:** Session resumption, path migration, work-safe replay,
and explicit epoch rebinding remain unspecified and deferred.
**Reason deferred:** Transparent resumption, rebinding, and work recovery must
be one coherent authority and work-safety design beyond Checkpoint 1.
**Activation trigger:** The 2026-09-12 recovery goal activated and completed
Checkpoint 1; a later explicit requirement is still needed for the remaining
resumption/rebinding capability.
**Evidence required before planning:** Duplicate-work, stale-callback, replay,
stale-ticket, epoch, authority rollover, cancellation, and recovery tests for
the remaining resumption/rebinding design. Checkpoint 1 evidence is recorded in
`docs/evidence/sl-d-036-overlay-reconciliation-2026-09-12.md`.
**Public-claims impact:** Checkpoint 1 supports fresh local authenticated
reconnection only; no session resumption, path migration, work replay, or
Internet/NAT traversal claim.
**Related documents:** `docs/protocol/STATE_MACHINES.md`, `docs/security/THREAT_MODEL.md`, [
`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md)
**Last reviewed:** 2026-07-27

## SL-D-037 — Contract revision and dependency invalidation

**Status:** READY_FOR_PLANNING
**Priority:** Later
**Area:** Coordination correctness
**Current verified capability:** Bounded task and capability records carry explicit identity and revision data where
implemented; no general accepted-harness-contract lifecycle exists.
**Missing capability:** Stable contract identity; explicit revision; owner; known consumers/dependants; contract status;
superseded-revision linkage; revision and invalidation events; stale-dependent-work classification; rejection of
publication against a superseded revision; and bounded notification to affected harnesses.
**Reason deferred:** No longer deferred; SYN-022 is the single active task implementing the bounded contract lifecycle.
**Activation trigger:** Real multi-harness work where one harness begins implementation against another harness's
accepted but not yet finalized function signature, schema, message shape, return type, or parameter set.
**Evidence required before planning:** Two harnesses depend on one revision; the owner publishes a new revision;
dependants become stale or replanning-required; old-revision publication is rejected as current; and old/new revisions
plus invalidation events remain auditable.
**Public-claims impact:** Synesis records and communicates changed agreements and invalidates stale coordination claims.
It does not rewrite, regenerate, repair, or automatically stub-regenerate harness code.
**Related documents:** `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-038 — Out-of-band mutation detection and integration enforcement

**Status:** READY_FOR_PLANNING
**Priority:** Later
**Area:** Coordination correctness and workspace safety
**Current verified capability:** MCP-managed writes are revision-checked and isolated worktrees are preserved; shell,
IDE-native editing, scripts, provider-native tools, other MCP servers, and direct filesystem access are outside portable
pre-write enforcement.
**Missing capability:** Detection of worker changes outside Synesis-managed mutation paths; comparison with current
ownership and allowed mutation scope; an ownership/mutation-violation record; blocking publication, validation,
completion, and integration while unresolved; preserved worktree boundaries; and bounded review, revert, reclassify, or
authorize recovery.
**Reason deferred:** General-purpose harnesses may bypass Synesis-managed mutation tools, and portable filesystem-level
prevention cannot be claimed.
**Activation trigger:** General-purpose harness adoption where Synesis cannot guarantee that every write passes through
its MCP mutation tools.
**Evidence required before planning:** An unauthorized direct write is detected; the worker/task enters a violation
state; publication and integration are blocked; and reverting or explicitly resolving the violation restores normal
operation without destroying isolated work.
**Public-claims impact:** Synesis may enforce publication and integration boundaries but must not claim portable
prevention of every filesystem write or default OS file locking. Preventive sandboxing is optional future research only.
**Related documents:** `docs/architecture/zero-touch-agent-collaboration.md`,
`docs/architecture/autonomous-workspace-broker.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-039 — Wait-for dependency graph and deadlock detection

**Status:** DEFERRED
**Priority:** Later
**Area:** Coordination correctness
**Current verified capability:** Task ownership and bounded coordination records exist; no wait-for graph is maintained.
**Missing capability:** Explicit blocking edges and a graph across tasks, owners, or contracts; cycle detection before
accepting a new edge; rejection of the newest cycle-forming edge; preservation of existing ownership and completed work;
and a bounded deadlock result with resolution options.
**Reason deferred:** Cross-task dependency semantics and operator resolution policy are not yet specified. A typical
unresolved cycle is task A waiting on task B while task B waits on task A.
**Activation trigger:** Concurrent multi-owner work with blocking cross-task requests.
**Evidence required before planning:** Create A→B and B→A dependencies; reject the second cycle-forming edge; report a
deterministic bounded cycle; preserve healthy ownership; and demonstrate resolution by making a request non-blocking,
transferring/relinquishing ownership, assigning one owner to both sides, revising the dependency, or escalating to the
operator.
**Public-claims impact:** Synesis detects and reports coordination cycles but does not choose the correct implementation
or architecture. Lease expiry is for abandoned participants, not healthy circular waits, and no task decomposition or
ranking is implied.
**Related documents:** `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-040 — Distributed project overlay, routed envelopes, and self-hosted relay

**Status:** DEFERRED / PROMOTED TO ACTIVE TASK; Checkpoint 3A activated 2026-09-13
**Priority:** Current
**Area:** Link overlay networking and project delivery
**Current verified capability:** Authenticated direct Link adjacencies, durable Ed25519 node identities, bounded control/application streams, signed project membership, opaque E2E logical envelopes, bounded deterministic topology, peer-transit forwarding, route selection, replay/loop/queue controls, and native six-endpoint convergence/failure/rejoin evidence.
**Missing capability:** Optional self-hosted live relay operations beyond the existing bounded relay seam; distributed stale-topology tombstones; browser/SSE overlay projection; and physical NAT/Internet traversal. Checkpoint 3A now has two-cycle bounded local Docker process/network-isolation evidence in `docs/evidence/sl-d-040-checkpoint-3a-docker-2026-09-13.md`; it does not promote native VM, KVM, or physical traversal capability.
**Reason deferred:** The remaining overlay capabilities are staged behind
separate evidence gates: self-hosted relay operations, globally unambiguous
stale-topology withdrawal, browser projection, isolated VM/process churn, and
physical traversal each have distinct operational and security boundaries.
**Activation trigger:** Explicit requirement for project messages to reach members through direct, peer-transit, or organization-relay paths without exposing application plaintext to transit infrastructure.
**Evidence required before planning:** The Checkpoint 2 baseline is recorded in the source, ADR-0066/0077, the overlay protocol, and the dated evidence file. Any remaining capability requires its own explicit task, acceptance criteria, and evidence before implementation.
**Public-claims impact:** Synesis may claim only the route classes, endpoint confidentiality, bounded topology, and local recovery demonstrated by evidence. It must not claim metadata anonymity, generic proxying, offline delivery, universal reachability, TURN/Internet traversal, global stale-topology tombstones, or VM/process-isolated churn.
**Related documents:** `docs/adr/0066-distributed-project-overlay-proposal.md`, `docs/adr/0077-native-multi-peer-quic-overlay-convergence.md`, `docs/protocol/SYNESIS_OVERLAY_V1.md`, `docs/security/THREAT_MODEL.md`, `docs/operations/NETWORK_VALIDATION_MATRIX.md`
**Last reviewed:** 2026-09-13

## 2026-09-14 SYN-068 screenshot refinement
Presentation-only continuation; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-071 page regression repair
Reviewed at activation and checkpoint preparation. Presentation-only geometry and native painting repairs; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-072 main footer clipping repair
Presentation-only main-page geometry repair; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-073 main footer composition polish
Presentation-only typography and alignment repair; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-074 main footer centering
Presentation-only text alignment repair; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-075 main return repaint and footer alignment
Presentation-only repaint and geometry repair; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-076 remove main warning footer
Presentation-only removal of warning visuals; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-077 main footer layout alignment
Presentation-only geometry repair; no deferred capability or architecture change is activated.

## 2026-09-14 SYN-078 main maintenance-page copy cleanup
Copy-only presentation repair; no deferred capability or architecture change is activated.
