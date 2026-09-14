# SL-D-032 compact bootstrap evidence — 2026-09-13

## Scope and boundary

Starting HEAD: `06acc4031f6c6ec77581463f3cfa7a3bdbfbc1b5`.

Durable checkpoint: `docs/agent/checkpoints/CP-0937.md`.

This evidence covers the additive `SLB1` baseline, the active `SLB2`
minimization refinement, their authenticated post-control descriptor exchange,
CLI/control-plane/deep-link integration, and legacy SLO1/SLA2 compatibility.
It is local two-process or same-process evidence. It does not claim Internet
reachability, symmetric-NAT hole punching, relay, rendezvous, or physical
two-machine success.

## Before: human-facing field inventory

The current legacy path is a wrapper around two signed records. The following
inventory is for the fixed one-candidate fixture used for the size measurement;
descriptor size grows with the candidate set.

| Field | SLO1 / SLA2 presence | Pre-auth requirement | New disposition |
|---|---|---|---|
| Magic, format, protocol version | Both records and wrapper | Select and frame the supported parser/handshake | Retained in SLB1; SLB2 selects the parser by URI revision and is fixed to Link v1 |
| Session UUID | SYN1, offer, answer | Bind one admission and transcript | Derived in SLB1 from host key, capability, and validity interval |
| Issue and expiry times | SYN1, offer, answer | Reject stale/future artifacts and bound replay | SLB1 retains both; SLB2 signs issue plus bounded lifetime and derives expiry |
| 32-byte invitation capability | SYN1 and transcript | Single-use admission binding | Compact 16-byte capability in SLB1; 12-byte (96-bit) capability in SLB2 |
| Full host candidate descriptor | SYN1 and SLO1 offer | Not required when one endpoint hint can start contact | Moved to signed SLX1 response after control readiness |
| Full joiner candidate descriptor | SLA2 answer | Not required for the joiner's first outbound packet | Moved to signed SLX1 request after control readiness |
| Host node ID and public key | Offer and SYN1 descriptor | Durable identity trust anchor for QUIC proof | Compact Ed25519 public key retained; node ID is derived |
| Expected responder node ID | Signed host offer | Host-side admission policy, not joiner discovery | Remains local host policy; not copied into the invite |
| Invitation digest / offer binding | Offer and answer | Prevent cross-session answer substitution | Replaced by the derived SLB1/SLB2 session/capability binding for one-way contact; legacy retains its binding |
| Traversal attempt nonce | Offer and answer | Needed by the bilateral candidate exchange | Moved to the authenticated Link/traversal path; not human-facing in compact revisions |
| One or more candidate addresses and UDP ports | Both descriptors | Some destination is needed before first contact | One signed endpoint hint retained in SLB1/SLB2; complete set is refreshed in-band |
| Ed25519 signatures | SYN1, offer, answer, descriptors | Authenticate every signed pre-auth record | One host signature retained in SLB1/SLB2; descriptor signatures remain in-band |
| Return target / project routing metadata | Versioned traversal variants only | Not required by direct one-way local path | Remains in legacy coordinated traversal; no project authority in compact revisions |

The legacy SLO1 wrapper serializes the complete SYN1 invitation and signed
offer. SLA2 serializes the joiner's signed descriptor and answer binding. Those
records are still decoded and validated for compatibility; the new encoder does
not compress or repack them.

## Measured sizes

Measurements use canonical URL-safe, unpadded Base64 links from the protocol
classes, with a ten-minute interval and one manual IPv4 candidate per full
descriptor. Raw is the complete binary payload; URI is the complete
`synesis://...` string.

| Artifact | Raw bytes | URI characters |
|---|---:|---:|
| Legacy SLO1, one candidate | 951 | 1,288 |
| Legacy SLA2, one candidate | 579 | 794 |
| New SLB1, IPv4 endpoint | 135 | 200 |
| New SLB1, IPv6 endpoint | 147 | 216 |
| New SLB2, IPv4 endpoint | 120 | 180 |
| New SLB2, IPv6 endpoint | 132 | 196 |

The SLB1 lower bound is dominated by the 32-byte Ed25519 public key and
64-byte Ed25519 signature, plus the capability, time, version, endpoint, and
framing fields. A universal sub-150-character signed URI is therefore not a
credible target for this existing trust primitive; the measured 200/216
characters are nevertheless dramatically smaller than the descriptor-bearing
legacy links.

## Exact SLB1 budget and minimized SLB2 budget

The pasted real IPv6 SLB1 artifact decodes to 147 raw bytes, 196 unpadded
Base64URL payload characters, and 216 complete URI characters. Its fields are:

| Field | SLB1 raw bytes | SLB2 raw bytes | Pre-auth reason |
|---|---:|---:|---|
| URI-selected format / inner magic | 4 magic + 1 format | 0 | The `SLB1-`/`SLB2-` URI revision selects a strict parser; SLB1 keeps its legacy framing. |
| Link protocol version | 2 | 0 | SLB2 is explicitly fixed to Link v1; a future protocol/layout gets a new URI revision. |
| Issue time | 4 | 4 | Rejects future-dated artifacts within the existing clock-skew rule. |
| Expiry / lifetime | 4 | 2 | SLB1 stores absolute expiry; SLB2 stores bounded seconds and derives expiry. |
| Admission capability | 16 | 12 | Single-use online admission; SLB2 retains a defensible 96-bit guessing floor. |
| Raw Ed25519 host public key | 32 | 32 | Expected durable identity needed before accepting the peer proof. |
| Endpoint type / address length | 1 + 1 | 0 | SLB2 uses exact total length and normalizes the one direct hint's type. |
| Endpoint address | 4 or 16 | 4 or 16 | Some destination is required for the first QUIC packet. |
| UDP port | 2 | 2 | The destination transport port is required before contact. |
| Host Ed25519 signature | 64 | 64 | Self-contained non-TOFU binding of identity, endpoint, capability, and freshness. |
| **Total raw bytes** | **135 IPv4 / 147 IPv6** | **120 IPv4 / 132 IPv6** | Every remaining field is consumed before authenticated contact. |

SLB1's fixed overhead is 131 bytes plus the 4/16-byte address. Base64URL
expands 135 to 180 and 147 to 196 payload characters; the 20-character
`synesis://join/SLB1-` prefix produces 200/216. SLB2's fixed raw overhead is
116 bytes plus the 4/16-byte address. Base64URL expands 120 to 160 and 132 to
176; the 20-character `synesis://join/SLB2-` prefix produces 180/196.

The detached signature is intentionally retained. The in-band Link proof
authenticates the key presented in the transcript; without the signed
invitation an attacker could replace both endpoint and expected key and have
the joiner authenticate the attacker's key. This is the exact TOFU failure the
bootstrap prevents. Project identity, membership, full candidates, topology,
and reconnect state remain outside both human-facing formats.

## Compact state and security evidence

The compact lifecycle is:

```text
INVITE_CREATED
  -> INVITE_PARSED / INVITE_VERIFIED
  -> TRAVERSAL_STARTED
  -> authenticated QUIC + CONTROL_READY
  -> DESCRIPTORS_EXCHANGED
  -> PEER_CONNECTED / Link-owned session
```

`SLB1` and `SLB2` are canonical, versioned, bounded, signed, expiry-checked, and reject
trailing bytes, unsupported format, unsafe endpoint types, any/multicast
addresses, zero ports, and oversized links. `InvitationAdmission` accepts the
compact capability, releases only pre-authentication reservations, and consumes
the capability after identity authentication. The handshake still checks the
expected durable identity; the endpoint is never used as identity and no TOFU
key update exists.

`AuthenticatedDescriptorExchange` carries only an envelope marker, kind,
length, and the existing signed `CandidateDescriptor`. The caller checks the
descriptor node ID, public key, validity interval, and expiry against the
authenticated session and compact invitation expiry. Project membership remains the
existing signed-membership authorization boundary.

## Verification

- Compact codec vectors: round-trip, canonical IPv4/IPv6 sizes, tamper, expiry,
  unsupported version, trailing bytes, endpoint bounds, single-use admission,
  SLB2 fixed-length family selection, 96-bit capability, and minimized size
  regression.
- In-band exchange vectors: request/response round-trip, wrong kind/trailing
  bytes, and descriptor signature substitution failure.
- Prior real local compact onboarding: host emitted one SLB1 link, join consumed it,
  no SLA2 is emitted, both sides reach control readiness, and both sides emit
  `DESCRIPTORS_EXCHANGED`.
- Current focused SLB2 codec, admission, and local onboarding tests pass after
  the minimization change; the full post-change Link/CLI/workspace gates remain
  to be rerun before closure.
- Existing human-mediated onboarding remains covered by the SLO1/SLA2 tests.
- CLI launcher, workspace control-plane, retained Link owner, and daemon
  deep-link focused tests pass for the compact path.
- `:link:check` passes format, compilation, static analysis, Javadocs, and 107
  Link tests. Workspace architecture/format/static-analysis/Javadocs and CLI
  format/static-analysis/Javadocs also pass.
- The packaged `GeneratedOnboardingTest`, `DaemonServerTest`, and
  `UnifiedCliSyncProcessTest` pass. The full CLI gate retains two unrelated
  baseline failures in `CodexHookProcessTest` and `WorkspaceCliTest`.
- A broad workspace test run encountered unrelated Synesis-managed
  capability/session/worktree-state failures. It was not bypassed by creating
  `.synesis` state or using coordination tools.

No `.synesis` state was created in this source checkout. No remote repository
was modified and no push was performed.
