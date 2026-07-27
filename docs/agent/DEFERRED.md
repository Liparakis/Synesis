# Deferred Functionality Register

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

## SL-D-031 — Serverless direct internet connectivity

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Authenticated direct QUIC works with bounded local candidates and local/two-process evidence.
**Missing capability:** A serverless direct-internet path that reports unsupported topologies explicitly.
**Reason deferred:** Cross-network behavior, candidate exchange, NAT behavior, and firewall support boundaries are not physically verified.
**Activation trigger:** A requirement for direct internet operation without a Synesis-hosted or third-party service.
**Evidence required before planning:** Two-machine matrix covering direct IPv6, manual forwarding, router mapping, hole punching, unsupported NATs, identity, cleanup, and failure diagnostics.
**Public-claims impact:** No universal internet reachability claim; no hosted service dependency is implied.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md), `docs/protocol/SYNESIS_LINK_V1.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-032 — Offer/answer invitation exchange

**Status:** DEFERRED
**Area:** Connectivity and onboarding
**Current verified capability:** Signed, single-use invitations and bounded candidate descriptors exist for the current direct-session path.
**Missing capability:** A short-lived project-bound offer/answer exchange carrying ephemeral peer keys, candidates, and synchronized-attempt information.
**Reason deferred:** The complete exchange contract, expiry, replay handling, and out-of-band operator flow are not promoted as one capability.
**Activation trigger:** A serverless direct-connectivity task requires coordinated endpoint attempts.
**Evidence required before planning:** Signed vectors for expiry, single use, project binding, ephemeral keys, candidate integrity, synchronized-attempt bounds, replay, and wrong-project rejection.
**Public-claims impact:** Invitations remain operator-exchanged; no discovery or rendezvous service is claimed.
**Related documents:** `docs/protocol/SYNESIS_LINK_V1.md`, `docs/security/THREAT_MODEL.md`, `docs/demo/FIRST_DEMO.md`
**Last reviewed:** 2026-07-27

## SL-D-033 — Manual port-forwarded endpoint support

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Operators can exchange bounded direct candidates; public endpoint support is not verified.
**Missing capability:** A user-supplied public host/IP and UDP port path with authenticated direct connection and clear failure reporting.
**Reason deferred:** Endpoint authorization, address-family handling, firewall guidance, and cleanup evidence are incomplete.
**Activation trigger:** Users need a dependable direct-connectivity escape hatch without router mutation.
**Evidence required before planning:** Two-machine IPv4/IPv6 manual-forward tests, wrong-endpoint rejection, firewall diagnostics, identity checks, and cleanup.
**Public-claims impact:** Manual forwarding would be an explicit operator setup path, not automatic reachability.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md), `docs/operations/TWO_MACHINE_TESTING.md`
**Last reviewed:** 2026-07-27

## SL-D-034 — Optional router port mapping

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** No router mapping implementation is claimed.
**Missing capability:** One opt-in product capability covering PCP, NAT-PMP, and UPnP IGD mapping with explicit ownership and cleanup.
**Reason deferred:** Router trust, consent, renewal, crash cleanup, exposure, and protocol coverage require one security and operations decision.
**Activation trigger:** A supported router-mapping requirement is approved with an explicit user-consent model.
**Evidence required before planning:** Protocol-specific adapter tests, mapping ownership/renewal/cleanup evidence, forged-gateway tests, exposure review, and physical router matrix results.
**Public-claims impact:** No automatic router mapping claim until opt-in, ownership, and cleanup are verified.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md), `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-035 — Coordinated UDP/QUIC hole punching

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Direct candidate racing exists; coordinated simultaneous outbound attempts do not.
**Missing capability:** Bounded, authenticated simultaneous attempts using exchanged invitation candidates.
**Reason deferred:** NAT behavior, consent, resource limits, and failure classification are not physically verified.
**Activation trigger:** A serverless direct-connectivity task requires traversal of compatible NATs without relay infrastructure.
**Evidence required before planning:** Cone-NAT, symmetric-NAT, CGNAT, firewall, replay, spoofing, timeout, resource-limit, and unsupported-topology tests.
**Public-claims impact:** No universal NAT-traversal claim; incompatible topologies must fail explicitly.
**Related documents:** [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md), `docs/security/THREAT_MODEL.md`, `docs/protocol/SYNESIS_LINK_V1.md`
**Last reviewed:** 2026-07-27

## SL-D-036 — Direct-session reconnection

**Status:** DEFERRED
**Area:** Session lifecycle
**Current verified capability:** Closed or expired sessions do not resurrect; fresh authenticated sessions are supported.
**Missing capability:** Explicit recovery using fresh authenticated authority, including reconnect state, replay protection, and epoch rebinding.
**Reason deferred:** Transparent reconnection, resumption, and rebinding must be one coherent authority and work-safety design.
**Activation trigger:** A product requirement for recovery after direct-session loss.
**Evidence required before planning:** Duplicate-work, stale-callback, replay, stale-ticket, epoch, authority rollover, cancellation, and recovery tests.
**Public-claims impact:** No transparent reconnection, resumption, or session resurrection claim.
**Related documents:** `docs/protocol/STATE_MACHINES.md`, `docs/security/THREAT_MODEL.md`, [`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md)
**Last reviewed:** 2026-07-27

## SL-D-037 — Contract revision and dependency invalidation

**Status:** DEFERRED
**Priority:** Later
**Area:** Coordination correctness
**Current verified capability:** Bounded task and capability records carry explicit identity and revision data where implemented; no general accepted-harness-contract lifecycle exists.
**Missing capability:** Stable contract identity; explicit revision; owner; known consumers/dependants; contract status; superseded-revision linkage; revision and invalidation events; stale-dependent-work classification; rejection of publication against a superseded revision; and bounded notification to affected harnesses.
**Reason deferred:** Real multi-harness contract lifecycle semantics are not yet a promoted coordination slice.
**Activation trigger:** Real multi-harness work where one harness begins implementation against another harness's accepted but not yet finalized function signature, schema, message shape, return type, or parameter set.
**Evidence required before planning:** Two harnesses depend on one revision; the owner publishes a new revision; dependants become stale or replanning-required; old-revision publication is rejected as current; and old/new revisions plus invalidation events remain auditable.
**Public-claims impact:** Synesis records and communicates changed agreements and invalidates stale coordination claims. It does not rewrite, regenerate, repair, or automatically stub-regenerate harness code.
**Related documents:** `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-038 — Out-of-band mutation detection and integration enforcement

**Status:** DEFERRED
**Priority:** Later
**Area:** Coordination correctness and workspace safety
**Current verified capability:** MCP-managed writes are revision-checked and isolated worktrees are preserved; shell, IDE-native editing, scripts, provider-native tools, other MCP servers, and direct filesystem access are outside portable pre-write enforcement.
**Missing capability:** Detection of worker changes outside Synesis-managed mutation paths; comparison with current ownership and allowed mutation scope; an ownership/mutation-violation record; blocking publication, validation, completion, and integration while unresolved; preserved worktree boundaries; and bounded review, revert, reclassify, or authorize recovery.
**Reason deferred:** General-purpose harnesses may bypass Synesis-managed mutation tools, and portable filesystem-level prevention cannot be claimed.
**Activation trigger:** General-purpose harness adoption where Synesis cannot guarantee that every write passes through its MCP mutation tools.
**Evidence required before planning:** An unauthorized direct write is detected; the worker/task enters a violation state; publication and integration are blocked; and reverting or explicitly resolving the violation restores normal operation without destroying isolated work.
**Public-claims impact:** Synesis may enforce publication and integration boundaries but must not claim portable prevention of every filesystem write or default OS file locking. Preventive sandboxing is optional future research only.
**Related documents:** `docs/architecture/zero-touch-agent-collaboration.md`, `docs/architecture/autonomous-workspace-broker.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27

## SL-D-039 — Wait-for dependency graph and deadlock detection

**Status:** DEFERRED
**Priority:** Later
**Area:** Coordination correctness
**Current verified capability:** Task ownership and bounded coordination records exist; no wait-for graph is maintained.
**Missing capability:** Explicit blocking edges and a graph across tasks, owners, or contracts; cycle detection before accepting a new edge; rejection of the newest cycle-forming edge; preservation of existing ownership and completed work; and a bounded deadlock result with resolution options.
**Reason deferred:** Cross-task dependency semantics and operator resolution policy are not yet specified. A typical unresolved cycle is task A waiting on task B while task B waits on task A.
**Activation trigger:** Concurrent multi-owner work with blocking cross-task requests.
**Evidence required before planning:** Create A→B and B→A dependencies; reject the second cycle-forming edge; report a deterministic bounded cycle; preserve healthy ownership; and demonstrate resolution by making a request non-blocking, transferring/relinquishing ownership, assigning one owner to both sides, revising the dependency, or escalating to the operator.
**Public-claims impact:** Synesis detects and reports coordination cycles but does not choose the correct implementation or architecture. Lease expiry is for abandoned participants, not healthy circular waits, and no task decomposition or ranking is implied.
**Related documents:** `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, `docs/security/THREAT_MODEL.md`
**Last reviewed:** 2026-07-27
