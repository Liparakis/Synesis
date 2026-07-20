# Persistence Test Matrix

| Invariant | Automated check | Fixture | Expected result | Latest result | Evidence |
|---|---|---|---|---|---|
| Required files exist | fixture validator | valid-state | pass | PASS | CP-0002 |
| Exactly one task is ACTIVE | validator | zero/multiple-active-tasks | reject | PASS | CP-0002 |
| TASKS and CURRENT agree | validator | current-task-mismatch | reject | PASS | CP-0002 |
| NEXT_SESSION has exact action | validator | missing-next-action | reject | PASS | CP-0002 |
| Placeholder contract blocks product implementation | resume/validator | placeholder-contract | block product, pass structure | PASS | CP-0002 |
| DONE tasks require evidence | validator | done-without-evidence | reject | PASS | CP-0002 |
| Checkpoint captures Git and task state | checkpoint script | real repository | checkpoint written | PASS | CP-0002 |
| Stale documentation is detectable | doctor | real repository | warn when stale | PASS | CP-0002 |
| Inconsistent fixtures are rejected | fixture validator | invalid fixtures | reject | PASS | CP-0002 |
| Valid fixtures are accepted | fixture validator | valid-state | pass | PASS | CP-0002 |
| Active contract is installed | contract review | CONTRACT.md | revision 1 ACTIVE | PASS | CP-0005 |
| Architecture baseline is recorded | architecture review | BASELINE.md and ADRs | pass | PASS | CP-0005 |
| Java 25 is available | runtime check | local JDK | Java 25 | PASS | CP-0005 |
| Gradle build has a wrapper | build check | project root | pass | NOT RUN | SL-001 |
| Identity generation/signing/verification | unit tests | NodeIdentityTest | pass | PASS | SL-002 |
| Deterministic node-ID derivation | unit test | NodeIdentityTest | pass | PASS | SL-002 |
| Identity persistence and unsafe overwrite prevention | unit test | NodeIdentityTest | pass | PASS | SL-002 |
| Candidate canonical bytes/signature/expiry/limits | unit tests | CandidateDescriptorTest | pass | PASS | SL-003 |
| Real local QUIC connection | process integration test | transport integration tests | pass | NOT RUN | SL-004 |
| Netty native QUIC loopback | integration test | NettyQuicLoopbackTest | pass | PASS | SL-004 |
| Two-process QUIC connection | process integration test | NettyQuicLoopbackTest | pass | PASS | SL-004 |
| Expected identity is authenticated before session exposure | integration test | NettyQuicLoopbackTest | pass | PASS | SL-005 |
| Replay and downgrade are rejected | protocol/integration tests | SessionAuthenticatorTest; NettyQuicLoopbackTest | pass | PASS | SL-005 |
| Canonical transcript round trip | unit test | SessionAuthenticatorTest | pass | PASS | SL-005 |
| Role-specific proof binding | unit test | SessionAuthenticatorTest | pass | PASS | SL-005 |
| Expected identity and cross-connection substitution rejection | unit test | SessionAuthenticatorTest | pass | PASS | SL-005 |
| Same-process transcript replay rejection | unit test | SessionAuthenticatorTest | pass | PASS | SL-005 |
| Identity-bound PeerSession over a real QUIC stream | integration test | NettyQuicLoopbackTest | pass | PASS | SL-005 |
| Two-process authenticated PeerSession | process integration test | NettyQuicLoopbackTest | pass | PASS | SL-005 |
| Incompatible protocol version over transport | integration test | NettyQuicLoopbackTest | pass | PASS | SL-005 |
| Bounded SLH1 control frame round trip | unit test | ControlFrameTest | pass | PASS | SL-006 |
| Oversized, truncated, invalid-flag, and overflow frames reject safely | unit test | ControlFrameTest | pass | PASS | SL-006 |
| Exactly one control stream is accepted | integration test | NettyQuicLoopbackTest | pass | PASS | SL-006 |
| PeerSession usability waits for CONTROL_READY | integration test | NettyQuicLoopbackTest | pass | PASS | SL-006 |
| Graceful GOODBYE delivers remote reason | local QUIC integration test | NettyQuicLoopbackTest | pass | PASS | SL-006 |
| Graceful close is bounded and idempotent | local/process integration test | NettyQuicLoopbackTest | pass | PASS | SL-006 |
| Control progress during non-control traffic | integration test | NettyQuicLoopbackTest | pass | PASS | SL-006 |
| Heartbeat payload round trip and golden vectors | unit test | HeartbeatMessageTest; TEST_VECTORS.md | pass | PASS | SL-007 |
| Invalid heartbeat version, length, and session binding reject | unit test | HeartbeatMessageTest | pass | PASS | SL-007 |
| Liveness starts only after control readiness | deterministic/integration test | LivenessTrackerTest; NettyQuicLoopbackTest | pass | PASS | SL-007 |
| LIVE, SUSPECT, recovery, and irreversible EXPIRED transitions | deterministic test | LivenessTrackerTest | pass | PASS | SL-007 |
| Delayed scheduler derives direct expiry history | deterministic test | LivenessTrackerTest | pass | PASS | SL-007 |
| Graceful close is not liveness expiry | local QUIC integration | NettyQuicLoopbackTest | pass | PASS | SL-007 |
| Heartbeat ACK exchange on authenticated local QUIC | integration test | NettyQuicLoopbackTest | pass | PASS | SL-007 |
| Healthy authenticated two-process heartbeat exchange | process integration test | NettyQuicLoopbackTest | pass | PASS | SL-007 |
| Exactly-once cancellation, terminal state, and schedule cleanup | deterministic/integration test | LivenessTrackerTest; NettyQuicLoopbackTest | pass | PASS | SL-007 |
| Abrupt process loss terminal classification | process integration test | NettyQuicLoopbackTest.reportsAbruptProcessLossWithDocumentedTerminalCategory | pass | PASS | SL-007 |
| Physical path migration and temporary application-silence recovery | manual/process evidence | not run in this slice | documented limitation | NOT CLAIMED | SL-007 |
| Deferred register schema, IDs, statuses, required fields, TODO references, and claim boundary | durable validator | `scripts/agent-validate-deferred.ps1` | pass | PASS | SL-DEMO-001 |
| Demo request/result codec bounds, strict UTF-8, correlation, and deterministic encoding | unit tests | `DemoWorkProtocolTest` | pass | PASS | SL-DEMO-001 |
| Demo work rejected before control readiness and delegated only after readiness | unit tests | `DemoWorkBindingTest` | pass | PASS | SL-DEMO-001 |
| Real local QUIC demo request/result exchange | integration test | `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream` | pass | PASS | SL-DEMO-001 |
| Two-process demo request/result exchange | process integration test | `NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses` | pass | PASS | SL-DEMO-001 |
| Source CLI help and safe identity creation | CLI test | `DemoCliTest`; `gradlew.bat :link:demoCli --args=--help` | pass | PASS | SL-DEMO-001 |
| Two-machine normal, abrupt-loss, and invalid-identity demonstration | physical validation | `docs/demo/FIRST_DEMO.md` | requires two computers | NOT CLAIMED | SL-DEMO-001 |
| Candidate normalization, mapped-address handling, privacy filtering, and deterministic compatible pairs | deterministic unit tests | CandidateNormalizationTest | pass | PASS | SL-008 |
| Concurrent provider gathering, failure isolation, timeout, cancellation, and diagnostics | deterministic unit tests | CandidateGathererTest | pass | PASS | SL-008 |
| Bounded race, authenticated/control-ready winner, wrong identity rejection, and loser cleanup | deterministic unit test | CandidateRacerTest | pass | PASS | SL-008 |
| Candidate pair selection feeding real local and two-process QUIC harnesses | integration/process tests | NettyQuicLoopbackTest | pass | PASS | SL-008 |
| PCP/NAT-PMP/UPnP/STUN/TURN/relay/hole-punching/physical reachability/path migration | manual/network evidence | not implemented or not run | explicit limitation | NOT CLAIMED | SL-008 |
| Automatic identity create/reuse and corruption rejection | unit tests | `IdentityBootstrapTest` | pass | PASS | SL-012 |
| Signed invitation canonical encoding, tamper, expiry, and bounded link | unit tests | `SessionInvitationTest` | pass | PASS | SL-012 |
| Single-use capability admission and pre-auth release | unit tests | `InvitationAdmissionTest` | pass | PASS | SL-012 |
| Compact QR output preserves the exact share-link matrix and bounded dimensions | unit test | `CompactQrRendererTest` | pass | PASS | SL-012 |
| Narrow terminals skip QR without wrapping | unit test | `CompactQrRendererTest` | pass | PASS | SL-012 |
| Unsupported output charset skips QR without corrupted glyphs | unit test | `CompactQrRendererTest` | pass | PASS | SL-012 |
| Listener-first two-profile host/join onboarding | process integration | `docs/evidence/ONBOARDING-PROCESS-2026-07-20.md` | pass | PASS | SL-012 |
| Physical zero-configuration onboarding | manual/network evidence | not run in this slice | explicit limitation | NOT CLAIMED | SL-012 |
