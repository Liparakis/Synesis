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
| Compact QR output preserves the exact share-link matrix and bounded dimensions | unit test | `cli/.../CompactQrRendererTest` | pass | PASS | SL-013 |
| Narrow terminals skip QR without wrapping | unit test | `cli/.../CompactQrRendererTest` | pass | PASS | SL-013 |
| Unsupported output charset skips QR without corrupted glyphs | unit test | `cli/.../CompactQrRendererTest` | pass | PASS | SL-013 |
| Listener-first two-profile host/join onboarding | process integration | `docs/evidence/ONBOARDING-PROCESS-2026-07-20.md` | pass | PASS | SL-012 |
| Physical zero-configuration onboarding | manual/network evidence | not run in this slice | explicit limitation | NOT CLAIMED | SL-012 |
| Link onboarding façade preserves typed lifecycle and failure classification | unit/integration tests | `OnboardingTest` | pass | PASS | SL-013 |
| Picocli parsing and numeric usage exits | CLI unit test | `SynesisCliParsingTest` | pass | PASS | SL-013 |
| Adapter rendering and typed failure mapping without global console mutation | CLI unit test | `CommandAdapterTest` | pass | PASS | SL-013 |
| Read-only doctor outcomes | CLI unit test | `ReadinessInspectorTest` | pass | PASS | SL-013 |
| Generated launcher help/version/identity smoke | launcher process test | `DistributionLauncherTest` | pass | PASS | SL-013 |
| Generated launcher two-profile host/join | bounded launcher integration | `GeneratedOnboardingTest` | pass | PASS | SL-013 |
| Generated launcher doctor and failure exits | launcher process validation | corrupt doctor and invalid join scenarios | pass | PASS | SL-013 |
| Fresh generated session after graceful close | bounded launcher integration | repeated `GeneratedOnboardingTest` run | pass | PASS | SL-013 |
| Transparent reconnect | protocol/manual validation | deferred capability | explicit limitation | NOT CLAIMED | SL-D-024 |
| Physical generated-launcher onboarding | manual/network evidence | `PHYSICAL-CLI-ONBOARDING.md` | pass | NOT CLAIMED | SL-013 |
| Signed decision canonical encoding, signature, provenance, and evidence bounds | CP-R2 unit/vector tests | `DecisionRecordTest` | pass | PASS | SYN-001 |
| Immutable local decision revision/head storage and crash-safe recovery | CP-R2 store tests | `DecisionStoreTest` | pass | PASS | SYN-001 |
| Duplicate, stale, gap, and divergent decision revisions never overwrite a head | CP-R2 deterministic store tests | `DecisionStoreTest` | pass | PASS | SYN-001 |
| Authenticated bounded application-stream seam without project semantics | Link unit/integration tests | `ApplicationStreamBindingTest`, `ApplicationStreamCodecTest`, focused `NettyQuicLoopbackTest` | pass | PASS | SL-014 |
| Project config projectId and explicit peer allowlist bounds/round trip | CP-R4 unit test | `ProjectConfigTest` | pass | PASS | SYN-001 |
| SYNC_REQUEST/RECORD/RESULT/ERROR deterministic bounded framing | CP-R4 unit test | `RecordMessageTest` | pass | PASS | SYN-001 |
| Two isolated profiles publish initial, duplicate, successor, stale, and conflict outcomes | CP-R4 process integration | `ProjectRecordSyncProcessTest` | pass | PASS | SYN-001 |
| Valid divergent revision quarantines without replacing head | CP-R4 process/store evidence | `ProjectRecordSyncProcessTest`; `DecisionStore.applyRemote` | pass | PASS | SYN-001 |
| Connection closes before result reports UNKNOWN | CP-R4 Link seam contract | application-stream completion mapping | pass | PASS | SYN-001 |
| Physical two-profile decision-record transfer claim | CP-R5 physical validation | `SL-D-028`; no physical run | deferred | NOT CLAIMED | SYN-001-CP-R5 |
| Searchable view bounded query grammar and deterministic ordering | SYN-002 | `DecisionSearchTest` | pass | PASS | SYN-002 |
| Searchable view returns only verified heads and fails closed on corruption | SYN-002 | `DecisionSearchTest`; `DecisionStore.verifiedHeads` | pass | PASS | SYN-002 |
| Searchable view performs no mutation and remains restart-equivalent | SYN-002 | `DecisionSearchTest` | pass | PASS | SYN-002 |
| Workspace profile paths are isolated and bounded | SYN-003 CP-W1 | `WorkspaceCliTest` | pass | PASS | CP-W1 |
| Identity show creates/reuses only the selected profile identity | SYN-003 CP-W1 | `WorkspaceCliTest` | pass | PASS | CP-W1 |
| Project creation persists one peer atomically and refuses overwrite/mismatch | SYN-003 CP-W1 | `WorkspaceCliTest` | pass | PASS | CP-W1 |
| Decision creation signs deterministic revision 1 with exactly one evidence reference | SYN-003 CP-W1 | `WorkspaceCliTest` | pass | PASS | CP-W1 |
| Workspace output is stable and omits secrets and absolute paths | SYN-003 CP-W1 | `WorkspaceCliTest` | pass | PASS | CP-W1 |
| Workspace host uses the sole configured peer and emits the invitation only on host | SYN-003 CP-W2 | `WorkspaceSyncProcessTest` | pass | PASS | CP-W2 |
| Workspace join pins expected remote identity before creating configuration | SYN-003 CP-W2 | `WorkspaceSyncProcessTest` | pass | PASS | CP-W2 |
| One-shot workspace sync reports APPLIED then DUPLICATE | SYN-003 CP-W2 | `WorkspaceSyncProcessTest` | pass | PASS | CP-W2 |
| Wrong host, malformed invitation, project mismatch, and missing record fail safely without unsafe mutation | SYN-003 CP-W2 | `WorkspaceSyncProcessTest` | pass | PASS | CP-W2 |
| Connection close before result reports UNKNOWN with nonzero exit | SYN-003 CP-W2 | `WorkspaceSyncProcessTest` | pass | PASS | CP-W2 |
