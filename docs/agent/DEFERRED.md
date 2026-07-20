# Deferred Functionality Register

This register records intentionally postponed, unsupported, partially verified,
research-dependent, physically unverified, or currently non-claimable work. It
is not a roadmap, release promise, implementation permission, or evidence that
a capability exists. `TASKS.md` contains committed implementation work.

Allowed statuses: `DEFERRED`, `RESEARCH_REQUIRED`, `BLOCKED`,
`READY_FOR_PLANNING`, `SUPERSEDED`, `CANCELLED`.

Promotion lifecycle: a deferred item stays here until its activation trigger
and required evidence exist. Then it may become `READY_FOR_PLANNING`; a
concrete task with acceptance criteria must be created and explicitly made the
only `ACTIVE` task. Keep the entry until the task replaces it; then mark it
`SUPERSEDED` with the task and checkpoint. Use `CANCELLED` only for a deliberate
permanent scope decision and record the reason.

## SL-D-001 â€” NAT traversal

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** Bounded direct connectivity using manual and eligible local-interface candidates, normalization, compatible pairing, and racing.
**Missing capability:** General reachability through NATs and firewalls.
**Reason deferred:** Connection racing is not NAT traversal.
**Dependencies:** Router mapping/discovery, server-reflexive candidates, coordinated attempts, QUIC constraints.
**Activation trigger:** A product requirement for topologies not served by direct candidates.
**Evidence required before planning:** Two-machine router matrix covering firewall/NAT classes and failure cleanup.
**Security questions:** Endpoint authenticity, spoofing, amplification, consent, and abuse limits.
**Privacy questions:** Address disclosure, router metadata, and third-party observation.
**Operational questions:** Support ownership, timeouts, diagnostics, and failure policy.
**Public-claims impact:** Do not claim universal peer-to-peer connectivity.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/SYNESIS_LINK_V1.md`, `docs/security/THREAT_MODEL.md`, `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Candidate provider/racer APIs only; no traversal implementation.
**Last reviewed:** CP-0030

## SL-D-002 â€” PCP external port mapping

**Status:** RESEARCH_REQUIRED
**Area:** Connectivity
**Current verified capability:** No PCP provider; direct candidates only.
**Missing capability:** Authenticated, owned, renewable PCP mappings.
**Reason deferred:** Gateway discovery, ownership, cleanup, and router compatibility are unspecified.
**Dependencies:** NAT traversal decision and physical router evidence.
**Activation trigger:** Approved mapping requirement and router test plan.
**Evidence required before planning:** Physical PCP matrix, mapping lifetime/cleanup tests, and privacy review.
**Security questions:** Unauthorized mapping, gateway spoofing, stale mappings, and abuse.
**Privacy questions:** Gateway identity and exposed port disclosure.
**Operational questions:** Renewal, crash cleanup, diagnostics, and support.
**Public-claims impact:** No automatic router mapping claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-003 â€” NAT-PMP external port mapping

**Status:** RESEARCH_REQUIRED
**Area:** Connectivity
**Current verified capability:** No NAT-PMP provider.
**Missing capability:** Owned NAT-PMP mapping lifecycle.
**Reason deferred:** Protocol support, gateway trust, cleanup, and platform coverage are unverified.
**Dependencies:** NAT traversal decision and physical router evidence.
**Activation trigger:** Approved platform scope and mapping ownership model.
**Evidence required before planning:** Physical router tests, expiry/renewal, and crash cleanup.
**Security questions:** Gateway spoofing, unauthorized mapping, and exposed services.
**Privacy questions:** Gateway/network metadata disclosure.
**Operational questions:** Renewal failure and support diagnostics.
**Public-claims impact:** No automatic router mapping claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-004 â€” UPnP IGD external port mapping

**Status:** RESEARCH_REQUIRED
**Area:** Connectivity
**Current verified capability:** No UPnP implementation.
**Missing capability:** Consentful, bounded IGD discovery and mapping cleanup.
**Reason deferred:** UPnP trust and exposure risks require a separate security decision.
**Dependencies:** NAT traversal decision, explicit user consent, router matrix.
**Activation trigger:** Security-approved requirement with opt-in policy.
**Evidence required before planning:** Physical IGD compatibility and abuse tests.
**Security questions:** Untrusted LAN devices, forged SSDP, arbitrary mappings, and revocation.
**Privacy questions:** LAN topology and device identity disclosure.
**Operational questions:** Consent UI, cleanup, renewal, and diagnostics.
**Public-claims impact:** No automatic router mapping claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`, `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-005 â€” STUN server-reflexive candidates

**Status:** RESEARCH_REQUIRED
**Area:** Connectivity
**Current verified capability:** `SERVER_REFLEXIVE` candidates are policy-rejected by default; no STUN client exists.
**Missing capability:** Optional authenticated/managed server-reflexive discovery.
**Reason deferred:** Server dependency, address privacy, and NAT behavior are unverified.
**Dependencies:** NAT traversal threat model, server policy, physical cross-network tests.
**Activation trigger:** A bounded STUN service and privacy policy are approved.
**Evidence required before planning:** Multi-NAT two-machine tests and timeout/metadata measurements.
**Security questions:** STUN trust, response spoofing, reflection, and candidate poisoning.
**Privacy questions:** Public address disclosure and service-provider logs.
**Operational questions:** Service availability, rate limits, cost, and fallback.
**Public-claims impact:** No STUN reachability claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/WIRE_FORMAT.md`, `docs/security/THREAT_MODEL.md`
**Code extension seams:** `CandidateProvider` only.
**Last reviewed:** CP-0030

## SL-D-006 â€” Coordinated UDP hole punching

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** No coordinated outbound-attempt protocol.
**Missing capability:** Consentful endpoint coordination and endpoint reuse.
**Reason deferred:** Requires rendezvous, NAT behavior evidence, and new abuse controls.
**Dependencies:** STUN/rendezvous research, QUIC-library constraints, NAT matrix.
**Activation trigger:** Approved non-relay traversal requirement.
**Evidence required before planning:** Physical multi-NAT matrix and packet/resource limits.
**Security questions:** Spoofing, port prediction, reflection, and consent.
**Privacy questions:** Endpoint sharing and coordination metadata.
**Operational questions:** Coordination timeout, retry, and failure reporting.
**Public-claims impact:** No hole-punching claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-007 â€” Relay fallback

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** No mandatory central relay exists; `RELAY` is reserved and rejected by direct racing.
**Missing capability:** Authenticated relay selection and fallback.
**Reason deferred:** Hosting, identity, cost, metadata, and abuse ownership are undecided.
**Dependencies:** Relay threat model and operational owner.
**Activation trigger:** Product requirement for otherwise unreachable peers.
**Evidence required before planning:** Relay prototype threat/cost review and two-machine tests.
**Security questions:** Relay identity, authorization, payload confidentiality, abuse, and DoS.
**Privacy questions:** Metadata visibility and traffic correlation.
**Operational questions:** Hosting, bandwidth limits, cost, rate limits, and failure behavior.
**Public-claims impact:** No relay or zero-server-in-every-topology claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`, `docs/protocol/WIRE_FORMAT.md`
**Code extension seams:** Reserved `CandidateType.RELAY`; no transport.
**Last reviewed:** CP-0030

## SL-D-008 â€” TURN-style relaying

**Status:** RESEARCH_REQUIRED
**Area:** Connectivity
**Current verified capability:** No TURN client or relay transport.
**Missing capability:** Allocation, authentication, permissions, refresh, and relay teardown.
**Reason deferred:** Separate service/security/cost model required.
**Dependencies:** Relay fallback decision and hosting owner.
**Activation trigger:** Explicit decision to operate or depend on TURN infrastructure.
**Evidence required before planning:** Provider/security review and bandwidth/abuse tests.
**Security questions:** Allocation auth, credential scope, exhaustion, and payload handling.
**Privacy questions:** Relay metadata and operator visibility.
**Operational questions:** SLA, cost, capacity, abuse response, and outages.
**Public-claims impact:** No TURN support claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-009 â€” CGNAT connectivity

**Status:** DEFERRED
**Area:** Connectivity
**Current verified capability:** No CGNAT-specific evidence; direct local/process evidence only.
**Missing capability:** Reliable connectivity through carrier-grade NAT.
**Reason deferred:** Depends on traversal or relay capabilities not implemented.
**Dependencies:** NAT traversal, STUN, hole punching, or relay decisions.
**Activation trigger:** Physical requirement for carrier networks.
**Evidence required before planning:** Two-machine CGNAT topology evidence.
**Security questions:** Shared-address spoofing and abuse controls.
**Privacy questions:** Carrier metadata and endpoint exposure.
**Operational questions:** Failure classification and support diagnostics.
**Public-claims impact:** No CGNAT claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-010 â€” Hosted rendezvous infrastructure

**Status:** DEFERRED
**Area:** Operations
**Current verified capability:** Descriptor exchange is manual/out-of-band.
**Missing capability:** Hosted rendezvous service.
**Reason deferred:** No production hosted infrastructure is in scope.
**Dependencies:** Discovery/traversal threat model and service ownership.
**Activation trigger:** Requirement for automated exchange across machines.
**Evidence required before planning:** Service threat model, availability, cost, and privacy review.
**Security questions:** Registration auth, impersonation, retention, and abuse.
**Privacy questions:** Peer metadata and relationship visibility.
**Operational questions:** Hosting, uptime, rate limits, and incident response.
**Public-claims impact:** No hosted rendezvous claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-011 â€” Production peer discovery

**Status:** DEFERRED
**Area:** Discovery
**Current verified capability:** Signed descriptor can be exchanged manually.
**Missing capability:** Production discovery and trust policy.
**Reason deferred:** Higher-level ownership and deployment semantics are not specified.
**Dependencies:** Rendezvous, identity directory, or project-level authority.
**Activation trigger:** Explicit discovery product requirement.
**Evidence required before planning:** Threat, privacy, availability, and abuse analysis.
**Security questions:** Enumeration, impersonation, revocation, and poisoning.
**Privacy questions:** Peer graph and presence disclosure.
**Operational questions:** Directory ownership, retention, and outages.
**Public-claims impact:** No automatic peer discovery claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-012 â€” Physical QUIC path migration validation

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** Local QUIC sessions and liveness are tested; no two-machine migration evidence.
**Missing capability:** Two physical computers changing network paths while preserving expected semantics.
**Reason deferred:** Requires two machines and controlled network changes.
**Dependencies:** Physical test operator and documented topology.
**Activation trigger:** Two-machine test access.
**Evidence required before planning:** Wi-Fi/Ethernet and interface-loss timelines.
**Security questions:** Path validation and stale-path injection.
**Privacy questions:** New address disclosure.
**Operational questions:** User-visible state, deadlines, and cleanup.
**Public-claims impact:** Do not claim path migration.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Internal QUIC adapter only.
**Last reviewed:** CP-0030

## SL-D-013 â€” Path migration orchestration or policy

**Status:** DEFERRED
**Area:** Transport lifecycle
**Current verified capability:** No application migration policy.
**Missing capability:** Explicit path-change reporting, policy, and failure semantics.
**Reason deferred:** Physical adapter behavior is unverified.
**Dependencies:** SL-D-012.
**Activation trigger:** Physical migration evidence and product need.
**Evidence required before planning:** Adapter events, identity/session invariants, and fault tests.
**Security questions:** Stale-path traffic and rebinding authorization.
**Privacy questions:** Address/interface history.
**Operational questions:** Metrics, user state, and timeout policy.
**Public-claims impact:** No migration orchestration claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/STATE_MACHINES.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-014 â€” Transparent reconnection

**Status:** DEFERRED
**Area:** Session lifecycle
**Current verified capability:** Closed/expired sessions do not resurrect.
**Missing capability:** New authenticated sessions with application recovery semantics.
**Reason deferred:** Reconnection changes authority, epochs, streams, and work safety.
**Dependencies:** Session epoch/replay design and application semantics.
**Activation trigger:** Explicit requirement for recovery after session loss.
**Evidence required before planning:** Duplicate-work and stale-callback tests.
**Security questions:** Rebinding authority, replay, stale sessions, and cancellation.
**Privacy questions:** Reconnection metadata and peer presence.
**Operational questions:** Retry limits, backoff, and user-visible state.
**Public-claims impact:** No transparent reconnection claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/STATE_MACHINES.md`, `docs/security/THREAT_MODEL.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-015 â€” Session resumption

**Status:** DEFERRED
**Area:** Session lifecycle
**Current verified capability:** Full fresh authenticated sessions only.
**Missing capability:** Safe resumption of prior session state.
**Reason deferred:** 0-RTT/resumption semantics are intentionally absent.
**Dependencies:** Replay protection, epochs, transcript evolution, stream retry safety.
**Activation trigger:** Performance requirement with explicit replay model.
**Evidence required before planning:** Replay, stale-ticket, and application recovery tests.
**Security questions:** Ticket theft, replay, key separation, and authority freshness.
**Privacy questions:** Linkability across sessions.
**Operational questions:** Ticket expiry, rotation, revocation, and storage.
**Public-claims impact:** No session resumption or 0-RTT claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/SYNESIS_LINK_V1.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-016 â€” Session epoch rebinding after reconnect

**Status:** DEFERRED
**Area:** Session lifecycle
**Current verified capability:** Epochs are transcript-bound values on fresh sessions.
**Missing capability:** Rebinding authority and stale-work rejection across reconnect.
**Reason deferred:** Requires higher-level work semantics.
**Dependencies:** SL-D-014 and application request identity model.
**Activation trigger:** Reconnection design approval.
**Evidence required before planning:** Old-session message and duplicate-work tests.
**Security questions:** Authority rollover and stale operation fencing.
**Privacy questions:** Session linkage.
**Operational questions:** Recovery, observability, and cleanup.
**Public-claims impact:** No session resurrection claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/STATE_MACHINES.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-017 â€” Temporary application-silence suppression and recovery

**Status:** BLOCKED
**Area:** Liveness validation
**Current verified capability:** Healthy heartbeat exchange, deterministic expiry, and abrupt process-loss classification.
**Missing capability:** Physically or deterministically suppressing application/control processing and then recovering without false expiry.
**Reason deferred:** No contrary evidence exists.
**Dependencies:** Controlled two-machine fault injection.
**Activation trigger:** Test harness or physical operator can pause/resume processing safely.
**Evidence required before planning:** Timeline proving suppression, recovery, and terminal-state behavior.
**Security questions:** Stale callbacks and delayed messages.
**Privacy questions:** None beyond normal diagnostics.
**Operational questions:** Pause duration and user-visible uncertainty.
**Public-claims impact:** Do not claim suppression recovery.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0005
**Related documents:** `docs/security/THREAT_MODEL.md`, `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Liveness test hooks only.
**Last reviewed:** CP-0030

## SL-D-018 â€” Physical global-IPv6 validation

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** IPv6 candidate normalization and same-family pairing are implemented; physical global-IPv6 reachability is unverified.
**Missing capability:** Two-machine global-IPv6 evidence.
**Reason deferred:** No second physical machine/topology evidence.
**Dependencies:** Two-machine IPv6 network.
**Activation trigger:** Physical test access.
**Evidence required before planning:** Address class, firewall, authentication, and cleanup record.
**Security questions:** Scope and address authorization.
**Privacy questions:** Global address disclosure.
**Operational questions:** Interface selection and diagnostics.
**Public-claims impact:** No global-IPv6 claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Local-interface provider.
**Last reviewed:** CP-0030

## SL-D-019 â€” Physical mapped-public-IPv4 validation

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** IPv4-mapped IPv6 normalization is unit-tested; public reachability is not.
**Missing capability:** Physical mapped/public IPv4 evidence.
**Reason deferred:** No second physical machine/network evidence.
**Dependencies:** Two-machine topology and privacy-safe recording.
**Activation trigger:** Physical test access.
**Evidence required before planning:** Sanitized address class and authenticated result.
**Security questions:** Address spoofing and exposure.
**Privacy questions:** Public address handling.
**Operational questions:** Firewall and cleanup.
**Public-claims impact:** No mapped-public-IPv4 claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Candidate normalizer.
**Last reviewed:** CP-0030

## SL-D-020 â€” Physical automatic-router-mapping validation

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** No automatic router mapping implementation.
**Missing capability:** Physical evidence of an approved mapping protocol.
**Reason deferred:** Protocols are not implemented.
**Dependencies:** SL-D-002, SL-D-003, SL-D-004.
**Activation trigger:** One mapping protocol is promoted and implemented.
**Evidence required before planning:** Router lifecycle and security evidence.
**Security questions:** Consent, gateway trust, stale mappings, and abuse.
**Privacy questions:** LAN/router metadata.
**Operational questions:** Renewal, cleanup, and support.
**Public-claims impact:** No automatic-router-mapping claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/security/THREAT_MODEL.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030

## SL-D-021 â€” Physical cross-network internet validation

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** Local and two-process evidence only.
**Missing capability:** Two separate computers across a documented internet topology.
**Reason deferred:** Requires user-provided second machine and network access.
**Dependencies:** Demo CLI/application path and two-machine operator.
**Activation trigger:** Two physical computers available.
**Evidence required before planning:** Normal, abrupt-loss, wrong-identity, and cleanup scenarios.
**Security questions:** Public endpoint exposure, identity, and firewall policy.
**Privacy questions:** Avoid recording personal addresses/usernames.
**Operational questions:** Reproducible setup, firewall, and cleanup.
**Public-claims impact:** No physical two-machine claim until executed.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`, `docs/demo/FIRST_DEMO.md`
**Code extension seams:** Demo CLI.
**Last reviewed:** CP-0030

## SL-D-022 â€” VPN or overlay-network validation

**Status:** RESEARCH_REQUIRED
**Area:** Physical validation
**Current verified capability:** No VPN/overlay evidence.
**Missing capability:** Documented behavior over an overlay interface.
**Reason deferred:** Overlay ownership and address semantics are unknown.
**Dependencies:** Physical two-machine baseline.
**Activation trigger:** A supported overlay topology is selected.
**Evidence required before planning:** Two-machine overlay test and interface/provider diagnostics.
**Security questions:** Overlay trust, identity layering, and route spoofing.
**Privacy questions:** Provider metadata and endpoint disclosure.
**Operational questions:** MTU, outages, and support.
**Public-claims impact:** No VPN/overlay claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Local-interface provider.
**Last reviewed:** CP-0030

## SL-D-023 â€” Production Synesis cooperation semantics

**Status:** DEFERRED
**Area:** Application semantics
**Current verified capability:** Demo-only bounded `synesis-demo-work/1` request/result path is the current scope.
**Missing capability:** Project synchronization, ownership, task delegation, leases, patch governance, or agent authority.
**Reason deferred:** These belong to higher-level Synesis slices.
**Dependencies:** First physical Link demonstration and explicit product contract.
**Activation trigger:** Higher-level cooperation task is explicitly promoted.
**Evidence required before planning:** Product invariants, authority model, and conflict/failure tests.
**Security questions:** Authorization, delegation, leases, fencing, and replay.
**Privacy questions:** Project/task data and participant visibility.
**Operational questions:** Recovery, ownership, and audit.
**Public-claims impact:** Demo messages are not production agent cooperation.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/agent/CONTRACT.md`, `docs/demo/FIRST_DEMO.md`
**Code extension seams:** Demo request/result API only.
**Last reviewed:** CP-0030

## SL-D-024 â€” CLI packaging, installation, and distribution

**Status:** DEFERRED
**Area:** Release operations
**Current verified capability:** Gradle Application development distribution
with generated Windows and Unix launchers is verified; no production installer
or supported package is claimed.
**Missing capability:** Signed, supported installation/distribution workflow.
**Reason deferred:** First demo uses reproducible local Java/Gradle commands.
**Dependencies:** Physical demo evidence and release ownership.
**Activation trigger:** External operator needs installation outside the repository.
**Evidence required before planning:** Clean-machine installation, upgrade, rollback, and secret handling.
**Security questions:** Artifact signing, dependency supply chain, and secret storage.
**Privacy questions:** Logs, paths, and telemetry.
**Operational questions:** Platform matrix, support, and uninstall.
**Public-claims impact:** No packaged production client claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0010
**Related documents:** `docs/demo/FIRST_DEMO.md`
**Code extension seams:** Gradle application packaging only if promoted.
**Last reviewed:** CP-0030

## SL-D-025 â€” Unresolved physical firewall behavior

**Status:** BLOCKED
**Area:** Physical validation
**Current verified capability:** Local direct QUIC and two-process behavior.
**Missing capability:** Documented behavior across real host/network firewalls.
**Reason deferred:** No physical two-machine run has been performed.
**Dependencies:** SL-D-021.
**Activation trigger:** Physical demonstration access.
**Evidence required before planning:** Safe firewall matrix with exact failure classification.
**Security questions:** Required ports, exposure, and least privilege.
**Privacy questions:** Network inventory leakage.
**Operational questions:** User setup and troubleshooting.
**Public-claims impact:** Do not claim operation across all firewalls.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/operations/TWO_MACHINE_TESTING.md`
**Code extension seams:** Candidate diagnostics only.
**Last reviewed:** CP-0030

## SL-D-026 â€” Reserved relay candidate representation

**Status:** DEFERRED
**Area:** Wire/model compatibility
**Current verified capability:** `CandidateType.RELAY` is an appended reserved enum value and direct racing rejects it.
**Missing capability:** Relay endpoint semantics and authenticated relay transport.
**Reason deferred:** A wire/model placeholder is not a relay implementation.
**Dependencies:** SL-D-007 and SL-D-008.
**Activation trigger:** Relay architecture is explicitly approved.
**Evidence required before planning:** Compatibility analysis, relay threat model, and transport tests.
**Security questions:** Relay identity, authorization, confidentiality, and abuse.
**Privacy questions:** Metadata visibility and traffic correlation.
**Operational questions:** Hosting, capacity, cost, and failure.
**Public-claims impact:** No relay candidate support claim.
**Potential future task:** Unassigned
**Related ADRs:** ADR-0006
**Related documents:** `docs/protocol/WIRE_FORMAT.md`, `docs/security/THREAT_MODEL.md`
**Code extension seams:** `CandidateType.RELAY`; direct racer rejection.
**Last reviewed:** CP-0030

## SL-D-027 â€” Production GUI and management surface

**Status:** DEFERRED
**Area:** Product surface
**Current verified capability:** No GUI; source CLI/demo commands are sufficient for validation.
**Missing capability:** Production management UI.
**Reason deferred:** Explicitly outside the first demonstration.
**Dependencies:** Product semantics and release ownership.
**Activation trigger:** Explicit UI requirement.
**Evidence required before planning:** User workflows, threat model, accessibility, and packaging.
**Security questions:** Secret display, authorization, and remote-control risks.
**Privacy questions:** Diagnostic and peer-data exposure.
**Operational questions:** Support, updates, and offline behavior.
**Public-claims impact:** No production GUI claim.
**Potential future task:** Unassigned
**Related ADRs:** None
**Related documents:** `docs/demo/FIRST_DEMO.md`
**Code extension seams:** None.
**Last reviewed:** CP-0030
