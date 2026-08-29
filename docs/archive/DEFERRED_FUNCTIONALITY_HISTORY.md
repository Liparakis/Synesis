# Deferred Functionality History

This archive preserves the former deferred-register IDs, titles, review
metadata, and disposition. IDs are never reused. The active register is
[`docs/agent/DEFERRED.md`](../agent/DEFERRED.md).

## Historical disposition

| ID       | Former entry                                           | Original status / last review       | Disposition                                                                                                                |
|----------|--------------------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| SL-D-001 | NAT traversal                                          | SUPERSEDED / CP-0030                | Reframed as active serverless direct connectivity `SL-D-031`; no universal traversal claim.                                |
| SL-D-002 | PCP external port mapping                              | RESEARCH_REQUIRED / CP-0030         | Merged into optional router mapping `SL-D-034`.                                                                            |
| SL-D-003 | NAT-PMP external port mapping                          | RESEARCH_REQUIRED / CP-0030         | Merged into optional router mapping `SL-D-034`.                                                                            |
| SL-D-004 | UPnP IGD external port mapping                         | RESEARCH_REQUIRED / CP-0030         | Merged into optional router mapping `SL-D-034`.                                                                            |
| SL-D-005 | STUN server-reflexive candidates                       | RESEARCH_REQUIRED / CP-0030         | Cancelled by product direction; direct serverless design does not depend on STUN.                                          |
| SL-D-006 | Coordinated UDP hole punching                          | DEFERRED / CP-0030                  | Reframed as `SL-D-035` using exchanged candidates and no relay.                                                            |
| SL-D-007 | Relay fallback                                         | DEFERRED / CP-0030                  | Cancelled by product direction; unsupported topologies fail explicitly.                                                    |
| SL-D-008 | TURN-style relaying                                    | RESEARCH_REQUIRED / CP-0030         | Cancelled by product direction; no relay infrastructure.                                                                   |
| SL-D-009 | CGNAT connectivity                                     | DEFERRED / CP-0030                  | Moved to the network-validation matrix as a topology, not a feature.                                                       |
| SL-D-010 | Hosted rendezvous infrastructure                       | DEFERRED / CP-0030                  | Cancelled by product direction; invitations are exchanged out of band.                                                     |
| SL-D-011 | Production peer discovery                              | DEFERRED / CP-0030                  | Cancelled by product direction; no production discovery service.                                                           |
| SL-D-012 | Physical QUIC path migration validation                | BLOCKED / CP-0030                   | Moved to the network-validation matrix; orchestration remains research.                                                    |
| SL-D-013 | Path migration orchestration or policy                 | DEFERRED / CP-0030                  | Long-term research archive; not an active capability.                                                                      |
| SL-D-014 | Transparent reconnection                               | DEFERRED / CP-0030                  | Merged into `SL-D-036`.                                                                                                    |
| SL-D-015 | Session resumption                                     | DEFERRED / CP-0030                  | Merged into `SL-D-036`; performance work remains research.                                                                 |
| SL-D-016 | Session epoch rebinding after reconnect                | DEFERRED / CP-0030                  | Merged into `SL-D-036`.                                                                                                    |
| SL-D-017 | Temporary application-silence suppression and recovery | BLOCKED / CP-0030                   | Moved to the network-validation matrix; recovery experiments remain research.                                              |
| SL-D-018 | Physical global-IPv6 validation                        | BLOCKED / CP-0030                   | Moved to the network-validation matrix.                                                                                    |
| SL-D-019 | Physical mapped-public-IPv4 validation                 | BLOCKED / CP-0030                   | Moved to the network-validation matrix.                                                                                    |
| SL-D-020 | Physical automatic-router-mapping validation           | BLOCKED / CP-0030                   | Moved to the network-validation matrix.                                                                                    |
| SL-D-021 | Physical cross-network internet validation             | BLOCKED / CP-0030                   | Moved to the network-validation matrix.                                                                                    |
| SL-D-022 | VPN or overlay-network validation                      | RESEARCH_REQUIRED / CP-0030         | Moved to the network-validation matrix.                                                                                    |
| SL-D-023 | Production Synesis cooperation semantics               | SUPERSEDED / 2026-07-23 CP-0144     | Historical CAF slice; future coordination correctness is represented by `SL-D-037`–`SL-D-039`.                             |
| SL-D-024 | CLI packaging, installation, and distribution          | SUPERSEDED / CP-0131                | Stable packaging work is complete under SYN-009D; retained as history.                                                     |
| SL-D-025 | Unresolved physical firewall behavior                  | BLOCKED / CP-0030                   | Moved to the network-validation matrix.                                                                                    |
| SL-D-026 | Reserved relay candidate representation                | DEFERRED / CP-0030                  | Cancelled by product direction; reserved wire value remains in code.                                                       |
| SL-D-027 | Production GUI and management surface                  | DEFERRED / CP-0030                  | Cancelled by product direction; no GUI capability is planned here.                                                         |
| SL-D-028 | CP-R5 physical decision-record transfer claim          | DEFERRED / 2026-07-21 CP-R4 closure | Moved to the network-validation matrix as evidence scope.                                                                  |
| SL-D-029 | Zero-touch provider workspace transition               | DEFERRED / 2026-07-24 CP-0147       | Useful remainder reframed as `SL-D-038`; filesystem prevention is not claimed.                                             |
| SL-D-030 | Real Codex and Antigravity provider validation         | DEFERRED / CP-0151                  | Archived validation record; provider claims remain governed by the validation documents and task gates, not this register. |

## Product-direction rule

Synesis intentionally avoids mandatory hosted or third-party connectivity
services. Unsupported direct-connectivity topologies fail explicitly. The
reserved relay wire value is retained for compatibility and is not an active
relay implementation.

## Long-term research, not active capabilities

These topics remain explicitly outside the active register until a concrete
activation trigger and evidence plan exists:

- QUIC path-migration orchestration and policy;
- session-resumption performance work beyond the authority design in `SL-D-036`;
- application-silence suppression/recovery experiments;
- preventive filesystem sandboxing, which cannot be claimed as portable
  filesystem-level enforcement;
- any other test harness, topology, or implementation experiment that does not
  establish a user-owned Synesis capability.
