# Demo Gap Analysis

Baseline: CP-0030. Evidence classifications are conservative: two JVMs on one
computer are `TWO_PROCESS_VERIFIED`, not `TWO_MACHINE_VERIFIED`.

| Capability | Classification | Evidence / gap |
|---|---|---|
| Two genuinely separate physical computers | TWO_MACHINE_VERIFIED for Scenario A | Normal-operation LAN evidence recorded in `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`; abrupt-loss and wrong-identity scenarios remain pending. |
| Independent long-term node identities | READY | `NodeIdentity` and `FileIdentityStore` are implemented and tested. |
| Separate JVM/process state | READY | Existing two-process harness passes. |
| Manual/LAN candidate exchange | READY | Candidate providers, descriptors, and pair ranking pass. |
| Signed candidate descriptor validation | READY | `CandidateDescriptorTest` passes. |
| Expected remote identity verification | READY | Wrong-identity transport tests pass. |
| Authenticated QUIC establishment | READY | Local and two-process QUIC evidence passes. |
| CONTROL_READY | READY | Existing control stream gates `PeerSession`. |
| LIVE state and heartbeat | READY | Local/two-process heartbeat evidence passes. |
| One bounded WORK_REQUEST | MINIMAL_IMPLEMENTATION_REQUIRED | No application-stream API or demo protocol exists. Add one fixed operation and bounded frame. |
| One bounded WORK_RESULT | MINIMAL_IMPLEMENTATION_REQUIRED | Add correlated fixed result over one authenticated application stream. |
| Graceful closure | READY | Existing control close tests pass. |
| Abrupt-loss classification | READY | Existing process test proves documented terminal categories, but not physically. |
| Invalid expected-identity rejection | READY | Existing integration evidence passes. |
| Deterministic cleanup | READY | Existing control/race cleanup tests pass; demo stream cleanup needs tests. |
| Understandable CLI operation | MINIMAL_IMPLEMENTATION_REQUIRED | No production/demo entry point exists. Add a small source-run CLI using existing internal transport seams. |
| Safe output suitable for recording | MINIMAL_IMPLEMENTATION_REQUIRED | CLI must redact keys, paths, and full endpoints by default. |
| Reproducible commands | MINIMAL_IMPLEMENTATION_REQUIRED | Add `docs/demo/FIRST_DEMO.md` and CLI command contract. |
| Truthful public claims | READY | Constrain claims with `DEFERRED.md`; physical claims remain blocked. |

## Minimum implementation

The demo blocker is a namespaced example protocol `synesis-demo-work/1` with
one fixed operation (`describe-session`), UUID request correlation, bounded
UTF-8 fields, one request per stream, bounded concurrent application streams,
and safe result statuses. It is not RPC, arbitrary method invocation, project
work, or agent authority. It rides a new authenticated application stream only
after `PeerSession.isUsable()` and reuses the existing Netty stream framing.

The CLI is a validation tool, not a production management application. It may
use the existing internal Netty adapter behind a small public-free executable
surface, but must not expose Netty types or private keys. The first source-run
version can use explicit identity/descriptor files and a supplied TLS keystore;
packaging remains SL-D-024.

No wire change to the existing SLH1 control protocol is required. The demo
application stream gets its own magic/version and a bounded frame. Tests must
cover codec limits, correlation, wrong identity, pre-ready use, local QUIC, and
two-process exchange.

## Explicit non-blockers

NAT traversal, router mapping, STUN/TURN, relays, CGNAT, rendezvous, path
migration, reconnection, session resumption, physical IPv6/public-IPv4 tests,
temporary silence recovery, GUI, packaging, and production Synesis semantics
are deferred in `DEFERRED.md`. Do not add them to this slice.
