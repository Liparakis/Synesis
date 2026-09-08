# Network Validation Matrix

This is a validation plan and evidence classification, not a feature roadmap.
Rows describe topologies or failure conditions that may be tested if the
corresponding direct-connectivity capability is promoted. No row promises
support.

| Scenario                            | Expected support level                                                 | Evidence status                             | Related active capability               |
|-------------------------------------|------------------------------------------------------------------------|---------------------------------------------|-----------------------------------------|
| Same LAN                            | Baseline direct candidate path                                         | Local/two-process evidence only             | `SL-D-031`                              |
| Global IPv6                         | Candidate path if operator network permits it                          | Physical evidence required                  | `SL-D-031`, `SL-D-033`                  |
| Manual port forwarding              | Explicit operator-configured endpoint                                  | Physical evidence required                  | `SL-D-033`                              |
| PCP                                 | Optional router mapping adapter                                        | Not implemented                             | `SL-D-034`                              |
| NAT-PMP                             | Optional router mapping adapter                                        | Not implemented                             | `SL-D-034`                              |
| UPnP IGD                            | Optional, consentful router mapping adapter                            | Not implemented                             | `SL-D-034`                              |
| Cone-NAT hole punching              | Bounded coordinated attempts                                           | Same-socket loopback codec/provider/coordinator evidence; controlled/physical evidence required | `SL-D-035`                      |
| Symmetric NAT                       | Explicit unsupported or failed result unless evidence proves otherwise | Not verified                                | `SL-D-035`                              |
| CGNAT                               | Explicit unsupported or failed result unless evidence proves otherwise | Not verified                                | `SL-D-031`, `SL-D-035`                  |
| VPN/overlay                         | Interface/provider-specific behavior                                   | Not verified                                | `SL-D-031`                              |
| Host firewall                       | Diagnose and classify blocked direct attempts                          | Not verified                                | `SL-D-031`, `SL-D-033`                  |
| Router firewall                     | Diagnose and classify blocked direct attempts                          | Not verified                                | `SL-D-031`, `SL-D-034`                  |
| Abrupt disconnect                   | Terminal transport/liveness classification                             | Local/two-process evidence exists           | `SL-D-036`                              |
| Reconnect                           | Fresh authenticated authority only                                     | Not implemented                             | `SL-D-036`                              |
| Physical decision-record transfer   | Evidence claim boundary, not a feature                                 | Two-process evidence only                   | Historical `SL-D-028`                   |
| QUIC path migration                 | Evidence claim boundary, not a feature                                 | Not verified                                | Historical `SL-D-012`, research archive |
| Application-silence fault injection | Evidence claim boundary, not a feature                                 | Not verified                                | Historical `SL-D-017`, research archive |

Unsupported topologies must produce a bounded diagnostic result. SL-D-035
authorizes only configured STUN Binding discovery and opaque signed
offer/answer transport; it does not authorize TURN, relay, hosted rendezvous,
or implicit production peer-discovery infrastructure. Same-socket Netty
integration is verified on loopback; controlled-NAT and physical topology
evidence remain open gates.
