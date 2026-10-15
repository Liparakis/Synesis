# Network Validation Matrix

This is a validation plan and evidence classification, not a feature roadmap.
Rows describe topologies or failure conditions that may be tested if the
corresponding direct-connectivity capability is promoted. No row promises
support.

| Scenario                            | Expected support level                                                 | Evidence status                                                                                 | Related active capability               |
|-------------------------------------|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|-----------------------------------------|
| Same LAN                            | Baseline direct candidate path                                         | Windows and a bridged virtual machine, both directions, 2026-10-14; two physical computers not run | `SL-D-031`                              |
| Global IPv6                         | Candidate path; through stateful firewalls with the answer link (ADR-0097) | Lab 2026-10-15: two firewalls, answer pasted, session; no answer, none | `SL-D-031`, `SL-D-033` |
| Manual port forwarding              | Explicit operator-configured endpoint                                  | Physical evidence required                                                                      | `SL-D-033`                              |
| PCP                                 | Optional router mapping adapter                                        | Not implemented                                                                                 | `SL-D-034`                              |
| NAT-PMP                             | Optional router mapping adapter                                        | Not implemented                                                                                 | `SL-D-034`                              |
| UPnP IGD                            | Host asks on the user's choice; removed on close (ADR-0097) | Lab 2026-10-15: `miniupnpd` 2.3.9 on the host's router, session with no answer; no real router | `SL-D-034` |
| Cone-NAT hole punching              | Answer link and host punch (ADR-0097) | Two real home networks 2026-10-15: session over IPv4 with the answer. Lab: fails behind two Linux NAT routers that keep the joiner's early packets on the host's port | `SL-D-035` |
| Symmetric NAT                       | Explicit unsupported or failed result unless evidence proves otherwise | Not verified                                                                                    | `SL-D-035`                              |
| CGNAT                               | Explicit unsupported or failed result unless evidence proves otherwise | Not verified                                                                                    | `SL-D-031`, `SL-D-035`                  |
| VPN/overlay                         | Interface/provider-specific behavior                                   | Not verified                                                                                    | `SL-D-031`                              |
| Host firewall                       | Installer-owned Windows rule (ADR-0096); a blocked attempt is not classified | Rule by hand admitted a session, 2026-10-14                                                                                 | `SL-D-031`, `SL-D-033`                  |
| Router firewall                     | Diagnose and classify blocked direct attempts                          | Not verified                                                                                    | `SL-D-031`, `SL-D-034`                  |
| Abrupt disconnect                   | Terminal transport/liveness classification                             | Local/two-process evidence exists                                                               | `SL-D-036`                              |
| Reconnect                           | Fresh authenticated authority only                                     | Checkpoint 1: deterministic + real three-member localhost QUIC loss/rejoin; no Internet/NAT claim | `SL-D-036`                              |
| Physical decision-record transfer   | Evidence claim boundary, not a feature                                 | Two-process evidence only                                                                       | Historical `SL-D-028`                   |
| QUIC path migration                 | Evidence claim boundary, not a feature                                 | Not verified                                                                                    | Historical `SL-D-012`, research archive |
| Application-silence fault injection | Evidence claim boundary, not a feature                                 | Not verified                                                                                    | Historical `SL-D-017`, research archive |

Unsupported topologies must produce a bounded diagnostic result. ADR-0097
adds STUN Binding discovery (Cloudflare by default, configurable), an unsigned
answer link carried by the users, and an optional UPnP forward; it does not
authorize TURN, relay, hosted rendezvous, or implicit production
peer-discovery infrastructure. The controlled-NAT rows above come from a
Podman lab on one machine (`D:\SynesisAcceptance\link-vm\natlab`); physical
topology evidence remains an open gate.
