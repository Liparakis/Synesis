# Session Log

Append-only operational history.

## Entry format

- Timestamp:
- Checkpoint:
- Active task:
- Completed work:
- Files changed:
- Commands run:
- Results:
- Decisions:
- Failed attempts:
- Remaining work:
- Exact continuation:

## 2026-07-25 — Post-MVP Hardening Slice 4: Doctor diagnostics and safe administrative repair

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0190
- Active task: SYN-014D
- Completed work: Implemented unified read-only `DoctorService` diagnostics (`synesis doctor`), 38 machine-readable finding codes, severity/status/confidence models, actionable recommendations, read-only guarantees, concise/verbose/JSON formatters, and a separate, reviewable, narrowly scoped repair-plan system (`synesis repair`: `--dry-run`, `--prepare`, `--show-plan`, `--execute`, `--rollback`) with immutable persisted plans (`RepairPlanStore`), repair execution lock (`RepairExecutionLock`), append-only journal (`RepairExecutionJournal`), and pre-mutation backup & exact atomic rollback (`RepairBackupService`). Added unit/integration tests for DoctorService, RepairPlan, DoctorCommand, and RepairCommand.
- Files changed: workspace/src/main/java/org/synesis/workspace/doctor/*, workspace/src/main/java/org/synesis/workspace/repair/*, cli/src/main/java/org/synesis/cli/command/DoctorCommand.java, cli/src/main/java/org/synesis/cli/command/RepairCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, tests in workspace/cli, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all 135+ unit tests and smoke tests passed cleanly.
- Decisions: DoctorService is strictly read-only by construction; DoctorCommand performs no repair logic; repair actions require prepared immutable plans with SHA-256 content verification; backups stored outside control checkout under admin directory; MCP tools count remains 11; provider config remains diagnostic-only.
- Failed attempts: `DoctorServiceTest` initially failed on `ProjectApplicationService.init` argument count; fixed by using single Path parameter `init(tempDir)`; Javadoc doclint warning on record constructors; fixed by adding compact constructor doc comments.
- Remaining work: Post-MVP operational milestone.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-27 — Bootstrap launcher and immutable-tree cleanup correction

- Checkpoint: CP-0236
- Completed work: The Unix stable launcher is chmodded `0755` after atomic
  replacement, and uninstall makes immutable installation trees removable.
- Verification: `go test -count=1 ./...`, `go vet ./...`, and `git diff --check`
  pass on the local Windows host. Docker was unavailable for a local Linux run.
- Failed attempts: The first portability correction fixed the staging rename but
  exposed launcher mode and immutable cleanup failures on Linux CI.
- Remaining work: GitHub Actions verification.

## 2026-07-27 — Linux MCP absolute file-URI binding correction

- Completed work: `McpProtocolHandler.parseUriOrPath` now preserves absolute
  Unix paths by parsing `file:` values through `Path.of(URI)`; a portable
  absolute-file-URI regression assertion was added.
- Root cause: the previous parser stripped all leading slashes from decoded
  `file:` URIs, turning `/tmp/...` into a relative path on Linux while Windows
  remained masked by drive-letter paths.
- Verification: focused `McpServerTest` and full
  `./gradlew.bat clean check --no-daemon --dependency-verification=strict`
  pass locally.
- Remaining work: review/commit disposition and GitHub Actions verification.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-27 — Immutable bootstrap test cleanup

- Completed work: Versioned-install tests now remove immutable installation roots
  through the safe cleanup helper before Go's `TempDir` cleanup runs.
- Verification: `go test -count=1 ./...` and `go vet ./...` pass locally.
- Remaining work: GitHub Actions verification.

## 2026-07-25 — Post-MVP Hardening Slice 3: Crash reconciliation and task cancellation

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0189
- Active task: SYN-014C
- Completed work: Implemented provider-session lease evidence, strong process verification, suspected-stale grace periods, immutable reconciliation plans, explicit reconciliation execution CLI (`synesis reconcile`), safe recovery of interrupted integrations, durable session abandonment, ambient `synesis.cancel_task` MCP tool (tool #11), dependency invalidation, and semantic ownership release. Added unit/integration/MCP tests proving non-destructive worktree preservation, lease state evaluation, plan immutability, and 11 MCP tools schema registration.
- Files changed: workspace/src/main/java/org/synesis/workspace/lease/*, workspace/src/main/java/org/synesis/workspace/reconcile/*, workspace/src/main/java/org/synesis/workspace/application/AgentTaskCancellationService.java, coordination/src/main/java/org/synesis/coordination/*, mcp/src/main/java/org/synesis/mcp/McpProtocolHandler.java, cli/src/main/java/org/synesis/cli/command/ReconcileCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, tests in workspace/mcp/cli, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all 133+ unit tests and smoke tests passed cleanly.
- Decisions: Non-destructive worktree preservation on session abandonment and task cancellation; external administration directory storage for leases, plans, locks, and journals; ambient task cancellation authorization enforced strictly for owning worker; 11 MCP tools registered in `tools/list`.
- Failed attempts: `CoordinationService` authorization initially failed for `SESSION_ABANDONED` event due to missing bypass entry; fixed by adding new event types to `CoordinationService` bypass list; `PredictionProjection` initially failed on `current == null` for non-prediction events; fixed in `PredictionProjection.transition`.
- Remaining work: SYN-014D, SYN-014E (installer/updater, demo fixtures).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Post-MVP Hardening Slice 2: Controlled lifecycle cleanup execution

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0188
- Active task: SYN-014B
- Completed work: Implemented controlled lifecycle cleanup execution using Slice 1 foundation. Created `PersistedCleanupPlan`, `PersistedCleanupPlanEntry`, `CleanupPlanStore`, `CleanupExecutionLock`, `CleanupExecutionRecord`, `CleanupEntryExecutionState`, `CleanupExecutionJournal`, `LifecycleQuarantineService`, and `CleanupExecutionService`. Extended `CleanupCommand` with `--prepare`, `--show-plan <plan-id>`, and `--execute <plan-id>`. Added 10 unit/integration/mutation tests proving safe worktree removal (without `--force`), exact file deletion, atomic quarantine move, lock acquisition, journal idempotency, and zero control checkout / durable state mutations.
- Files changed: workspace/src/main/java/org/synesis/workspace/cleanup/*, workspace/src/test/java/org/synesis/workspace/cleanup/*, cli/src/main/java/org/synesis/cli/command/CleanupCommand.java, cli/src/test/java/org/synesis/cli/Slice2MutationBoundaryTest.java, cli/src/test/java/org/synesis/cli/CleanupCommandTest.java, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all pass. Checkpoint CP-0188 created.
- Decisions: Atomic move for quarantine; non-forced git worktree removal for worktrees; strict precondition re-verification at execution time; project-scoped lock stored outside control checkout under admin directory.
- Failed attempts: `NoForceAndSafetyArchitectureTest` initially failed due to comments containing `--force`; fixed by stripping comments before code checks; `WorkerCleanupTest` failed due to relative git-common-dir output resolution; fixed in `LifecyclePathVerifier` and `CleanupEligibilityService`.
- Remaining work: SYN-014C through SYN-014E (remaining hardening slices).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Post-MVP Hardening Slice 1: Read-only lifecycle inventory and cleanup dry-run

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0187 (pending)
- Active task: SYN-014A
- Completed work: Implemented full read-only lifecycle cleanup planning foundation. Created 12 domain enums and records in `org.synesis.workspace.cleanup`: `LifecycleResourceType`, `CleanupClassification`, `ProcessEvidenceState`, `CleanupReason`, `LifecycleResourceFingerprint`, `RetentionPolicy`, `LifecyclePathVerifier`, `CleanupPlanEntry`, `CleanupPlan`, `ProcessInspector`. Implemented `LifecycleInventoryService`, `CleanupEligibilityService`, and `CleanupPlanService`. Added `synesis cleanup --dry-run` CLI command (`CleanupCommand`) with concise/verbose/JSON output modes registered in `SynesisCli`. Added 5 test classes including `FullMutationSafetyTest` proving zero runtime mutations. All 49 Gradle tasks pass.
- Files changed: workspace/src/main/java/org/synesis/workspace/cleanup/*, workspace/src/test/java/org/synesis/workspace/cleanup/*, cli/src/main/java/org/synesis/cli/command/CleanupCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, cli/src/test/java/org/synesis/cli/CleanupCommandTest.java, cli/src/test/java/org/synesis/cli/FullMutationSafetyTest.java, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all pass.
- Decisions: Snapshot types (`TASK_SNAPSHOT`, `IMPLEMENTATION_SNAPSHOT`) are evaluated before path safety to ensure they always receive `PROTECTED`+`SNAPSHOT_CLEANUP_NOT_SUPPORTED` regardless of path location.
- Failed attempts: Initial `CleanupCommandTest` used non-existent `TestTerminal` and `initialize()` method; corrected to `ConsoleTerminal` pattern from `WorkspaceCliTest`; `FullMutationSafetyTest` initially used `Files.readString` causing `MalformedInputException` on binary files; corrected to SHA-256 byte hashing.
- Remaining work: SYN-014B through SYN-014E (remaining hardening slices).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Stage 2B Slice 1 capability negotiation implementation

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0179
- Active task: SYN-013D
- Completed work: Implemented Stage 2B Slice 1 capability negotiation with durable handle domain types, binary payload codecs, event store projections, application services, and 2 new MCP tools (synesis.describe_required_capability, synesis.respond_to_owner_request).
- Files changed: coordination/src/main/java/org/synesis/coordination/*, workspace/src/main/java/org/synesis/workspace/*, mcp/src/main/java/org/synesis/mcp/*, docs/agent/*
- Commands run: `.\gradlew.bat :coordination:check --no-daemon`; `.\gradlew.bat :workspace:check --no-daemon`; `.\gradlew.bat :mcp:check --no-daemon`; `.\gradlew.bat :cli:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `git commit -m "Add Synesis MCP capability negotiation"`; `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: PASS; full test suite passed (49 actionable tasks); git commit 1fb83df; checkpoint CP-0179 created.
- Decisions: Handles use SecureRandom Base32 tokens with req_ prefix and upper-case normalization; total tools exposed via tools/list is exactly 7.
- Failed attempts: None.
- Remaining work: Awaiting Stage 2B Slice 2 implementation instructions.
- Exact continuation: powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1

## 2026-07-20 — persistence installation

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0002
- Active task: SL-SETUP-002
- Completed work: Installed and validated durable memory, scripts, and fixtures.
- Files changed: AGENTS.md, docs/agent/*, scripts/*, src/test/resources/agent-persistence-fixtures/*
- Commands run: `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-fixtures.ps1`; resume; doctor; checkpoint
- Results: PASS; invalid fixtures rejected; valid fixture accepted; checkpoint written.
- Decisions: No product architecture decisions.
- Failed attempts: None.
- Remaining work: Install complete v1 contract.
- Exact continuation: Install the complete contract into `docs/agent/CONTRACT.md`, reconcile `GOAL.md`, create the initial product task graph, and unblock `SL-001`.

## 2026-07-20 — contract and architecture installation

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0005
- Active task: SL-001
- Completed work: Installed the complete contract verbatim at revision 1, ran the constraint-driven architecture pass, recorded the modular-monolith and Netty QUIC decisions, created the initial protocol/security/operations documentation, and activated Slice 1.
- Files changed: `docs/agent/CONTRACT.md`, `docs/agent/GOAL.md`, `docs/agent/STATE.md`, `docs/agent/TASKS.md`, `docs/agent/CURRENT.md`, `docs/agent/DECISIONS.md`, `docs/agent/TEST_MATRIX.md`, `docs/agent/NEXT_SESSION.md`, `docs/architecture/`, `docs/adr/`, `docs/protocol/`, `docs/security/`, `docs/operations/`.
- Commands run: resume; durable-memory read; Java 25 check; architecture skill/reference review; primary-source QUIC research; checkpoint.
- Results: Contract and architecture gates pass; product build not yet run.
- Decisions: One Gradle project; Netty 4.2 native QUIC behind an internal adapter, pending build validation.
- Failed attempts: None.
- Remaining work: Materialize the Gradle project and first passing test.
- Exact continuation: Create `settings.gradle.kts`, `build.gradle.kts`, and `gradle/libs.versions.toml` for the single Java 25 project, without adding production networking code.

## 2026-07-20 — build and identity slices

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0006
- Active task: SL-003
- Completed work: Added Gradle Wrapper, Java 25 build, strict compile/Javadocs/tests, formatting/package/static checks, dependency locking and verification, protocol ALPN marker, Ed25519 identity generation/signing/verification, bounded file persistence, and tests.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: JDK Ed25519 identity with SHA-256 lowercase hexadecimal `sl1-` node IDs; file storage refuses overwrite and uses atomic sibling moves.
- Failed attempts: JUnit launcher was initially absent; adding the explicit JUnit Platform launcher fixed the test runtime. Provider key algorithm reports `EdDSA`; the implementation accepts the provider alias while generating with `Ed25519`.
- Remaining work: Candidate descriptors.
- Exact continuation: Create `package-info.java`, `Candidate`, and `CandidateDescriptor` under `src/main/java/org/synesis/link/candidate/` with bounded canonical encoding and signature verification.

## 2026-07-20 — candidate descriptor slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0007
- Active task: SL-004
- Completed work: Added bounded immutable candidates, deterministic normalization, versioned canonical descriptor encoding, Ed25519 signing/verification, expiry and clock-skew checks, parser limits, and tests.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: Descriptor signatures cover canonical unsigned bytes; routes remain distinct from identity.
- Failed attempts: Javadocs initially failed on missing `@return` tags for descriptor accessors; tags were added and the gate passed.
- Remaining work: First real local QUIC connection.
- Exact continuation: Resolve `io.netty:netty-codec-native-quic:4.2.16.Final` with the local platform classifier and add only an internal transport adapter package; do not expose Netty types publicly.

## 2026-07-20 — QUIC transport validation slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0007
- Active task: SL-004
- Completed work: Added Netty 4.2.16 native QUIC dependency with Windows runtime classifier, updated locks and verification metadata, enabled explicit native access for tests, and passed a real local QUIC loopback handshake and deterministic close.
- Commands run: `gradlew.bat dependencies --write-locks --write-verification-metadata sha256`; `gradlew.bat clean check --dependency-verification=strict`; targeted `NettyQuicLoopbackTest`.
- Results: PASS for dependency resolution and in-process QUIC loopback; two-process and identity binding remain unverified.
- Decisions: Keep Netty types internal; use insecure TLS trust only in the pre-identity transport test and replace it in Slice 5.
- Failed attempts: Initial JUnit runtime lacked the launcher; deprecated Netty test certificate failed on Java 25; missing QUIC token/connection handlers caused handshake setup failures; each was corrected with explicit launcher, keytool-generated test TLS material, token handler, and connection handlers.
- Remaining work: Production-internal adapter and two-process local harness.
- Exact continuation: Move the passing loopback path into a production-internal transport adapter and add a two-process local integration harness; do not expose Netty types publicly.

## 2026-07-20 - QUIC authenticated control stream

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-005
- Completed work: Added bounded `SLH1` handshake envelopes, Netty length framing, internal client/server stream handlers, and a real local QUIC test proving both sides receive an authenticated `PeerSession` only after transcript proofs pass.
- Commands run: targeted `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream`.
- Results: PASS.
- Decisions: Keep the stream adapter package-private and retain insecure TLS trust only inside the test fixture; application identity is established by the signed transcript proofs.
- Remaining work: Advertised-version negotiation, wrong-identity transport rejection, and authenticated two-process evidence.
- Exact continuation: Extend the control exchange with version offers and update the two-process harness to exchange persistent node identities.

## 2026-07-20 - SL-005 completion

- Timestamp: 2026-07-20 Europe/Athens
- Active task: SL-005 completed; SL-006 is handoff-only.
- Results: PASS. Repeated independent JVMs authenticated expected identities and agreed on version 1/session ID; real-QUIC wrong-identity and no-common-version cases returned categorized failures without `PeerSession`; transcript version tampering was rejected; strict clean verification passed.
- Evidence: `NettyQuicLoopbackTest`, `SessionAuthenticatorTest`, `gradlew.bat clean check --dependency-verification=strict`.
- Handoff: No SL-006 code was implemented.

## 2026-07-20 - SL-006 completion

- Active task: SL-006 completed; SL-007 is handoff-only.
- Completed work: Added bounded SLH1 control frames, CONTROL_READY gating, one-control-stream ownership, protocol-error closure, GOODBYE/GOODBYE_ACK, categorized close reasons, exactly-once terminal completion, idempotent graceful close, duplicate-stream rejection, malformed/oversized parser tests, large non-control stream isolation, and process-level graceful close.
- Results: PASS — `ControlFrameTest`, local QUIC control integration, repeated process test, and `gradlew.bat clean check --dependency-verification=strict`.
- Handoff: No heartbeat, liveness, reconnect, candidate, or application protocol behavior was implemented.

## 2026-07-20 - SL-007 completion

- Checkpoint: CP-0027
- Active task: SL-007 completed; SL-008 is handoff-only.
- Completed work: Added fixed-size versioned HEARTBEAT/HEARTBEAT_ACK payloads, sequence and session binding validation, control-ready liveness start, manual-clock LIVE/SUSPECT/EXPIRED state machine with recovery and delayed-expiry history, public liveness state/metrics/listener API, bounded callback dispatch, terminal precedence and cleanup, local QUIC heartbeat evidence, healthy two-process heartbeat evidence, golden vectors, ADR-0005, protocol state/wire/security updates, and durable-memory reconciliation.
- Files changed: `src/main/java/org/synesis/link/session/`, `src/main/java/org/synesis/link/transport/`, affected tests, `docs/adr/0005-heartbeat-liveness.md`, `docs/protocol/`, `docs/security/`, `docs/agent/`.
- Commands run: targeted codec/liveness tests; local QUIC and two-process tests; `gradlew.bat clean check --dependency-verification=strict`; fixture validator; doctor; resume; checkpoint.
- Results: PASS. Abrupt child-process loss is classified within the documented transport-failure/liveness-expiry bound; physical migration, temporary application silence, and QUIC idle-timeout tuning remain explicitly unverified.
- Decisions: newest valid current-session heartbeat/ACK is the only v1 liveness refresh; duplicates/stale messages do not refresh; first terminal reason wins; expiry is irreversible; transport closure before expiry remains transport failure.
- Failed attempts: initial RED compile failure before liveness production types; one malformed test assumption about heartbeat type binding; one process assertion race fixed with a readiness marker. No failed approach was repeated.
- Remaining work: SL-008 candidate providers and racing.
- Exact continuation: Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, read durable memory, and inspect candidate boundaries; do not implement SL-008 in the handoff.

## 2026-07-20 — SL-008 completion

- Checkpoint: CP-0030
- Active task: SL-008 completed; SL-009 is handoff-only.
- Completed work: Added bounded manual and local-interface providers, explicit provider policy and diagnostics, cancellable concurrent gathering, normalization and privacy filtering, deterministic same-family pair ranking, bounded staggered racing, exact authenticated expected-identity/control-ready winner selection, loser cancellation and late-session cleanup, redacted diagnostics, ADR-0006, protocol/state/wire/security/operations documentation, and local/two-process QUIC candidate-pair integration evidence.
- Files changed: `src/main/java/org/synesis/link/candidate/`, candidate tests, QUIC integration harnesses, `docs/protocol/`, `docs/security/`, `docs/operations/`, `docs/adr/ADR-0006-bounded-direct-candidates.md`, and `docs/agent/`.
- Commands run: targeted candidate tests; `NettyQuicLoopbackTest`; `gradlew.bat clean check --dependency-verification=strict`; fixture validator; doctor; resume; checkpoint.
- Results: PASS. Unsupported router discovery, relays, NAT traversal, physical reachability, path migration, reconnect, and temporary liveness-suppression recovery remain explicitly unverified or out of scope.
- Decisions: direct-only manual/local providers; no fake PCP/NAT-PMP/UPnP/STUN/TURN support; only authenticated control-ready sessions win; deterministic bounded cleanup is required.
- Failed attempts: one combined documentation patch failed due to an exact-text/encoding mismatch and made no changes; smaller exact patches succeeded. No product code was reverted.
- Remaining work: SL-009 reconnect and path behavior, not started here.
- Exact continuation: Read CP-0030 and the SL-009 contract; do not infer SL-009 implementation from this handoff.

## 2026-07-20 — SL-DEMO-001 automated readiness

- Checkpoint: CP-0031
- Active task: SL-DEMO-001; SL-009 deferred.
- Completed work: Added the 27-entry deferred register and validator enforcement in resume, doctor, fixtures, and checkpoints; added demo-gap analysis; implemented bounded `synesis-demo-work/1` request/result records, strict codec, authenticated control-ready binding, bounded QUIC application streams, source-run identity/server/client CLI, local/two-process exchange evidence, first-demo procedure, threat/protocol/release documentation, and ADR-0007.
- Files changed: `docs/agent/DEFERRED.md`, `docs/agent/DEMO_GAP_ANALYSIS.md`, `scripts/agent-validate-deferred.ps1`, durable scripts/docs, `src/main/java/org/synesis/link/demo/`, `PeerSession`, transport demo stream/CLI, tests, `docs/demo/FIRST_DEMO.md`, and release readiness draft.
- Commands run: deferred validator; resume; doctor; fixture validator; RED demo tests; targeted demo/local/process tests; `gradlew.bat demoCli --args=--help`; `gradlew.bat check --dependency-verification=strict`.
- Results: PASS. Physical two-machine normal, abrupt-loss, and wrong-identity runs are not available in this workspace and remain unclaimed.
- Decisions: Keep SL-009 reconnect/path behavior deferred; ship only one fixed demo operation; no NAT/router/STUN/relay/discovery work; preserve two-process versus two-machine evidence distinction.
- Failed attempts: Initial deferred validator invocation needed explicit `$LASTEXITCODE` initialization under PowerShell strict mode; initial application stream ordering incorrectly classified app frames as duplicate control and was fixed at the shared responder boundary. Both were retested.
- Remaining work: Execute and record `docs/demo/FIRST_DEMO.md` on two independent computers; do not add transport features.
- Exact continuation: Run the physical normal, abrupt-loss, and invalid-identity scenarios with sanitized evidence, then update `TEST_MATRIX.md`, `CURRENT.md`, and the checkpoint without claiming unsupported capabilities.

## 2026-07-20 — authenticated session core

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-005
- Completed work: Added canonical bounded handshake transcripts, role-specific transcript challenges, Ed25519 proof creation/verification, bounded process-local replay protection, and an immutable transport-neutral `PeerSession`.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS. Unit tests cover transcript round trips, role binding, expected-identity rejection, cross-connection proof substitution, and replay rejection.
- Decisions: The transcript binds ALPN, version, session ID, both role identities, both nonces, and both epochs. QUIC stream integration is still required before calling the session authenticated over transport.
- Failed attempts: Strict Javadocs initially failed because new public accessors lacked `@return` tags; corrected and reran the full gate.
- Remaining work: Exchange transcript/proofs through a bounded bidirectional QUIC control stream and prove wrong-identity rejection with a real transport.
- Exact continuation: Add the internal QUIC control-stream handshake adapter; do not expose Netty types publicly or publish `PeerSession` before authentication completes.

## 2026-07-20 — two-process QUIC slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0009
- Active task: SL-005
- Completed work: Added test-only keytool TLS material, a process entry point, and a separate-process integration test. Two Java processes now establish and close a real local Netty QUIC connection using `synesis-link/1`.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: The proof is transport-only and uses test-only insecure TLS trust; identity binding is explicitly deferred to Slice 5.
- Failed attempts: Process launch initially shadowed the `java` package name; the executable variable was renamed.
- Remaining work: Identity-bound handshake and PeerSession.
- Exact continuation: Create `package-info.java`, `ProtocolVersion`, and a bounded signed handshake transcript under `src/main/java/org/synesis/link/protocol/`; do not expose Netty types publicly.

## 2026-07-20 — internal transport adapter

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-004
- Completed work: Moved Netty codec construction behind package-private `NettyQuicTransport`; the public API remains Netty-free.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: Insecure QUIC token handling remains test-only; production adapter accepts a token handler supplied by the future transport configuration.
- Failed attempts: A client builder was incorrectly given connection handlers; Netty 4.2 requires those handlers on `QuicChannel` bootstrap, so the adapter now owns codec construction only.
- Remaining work: Two-process local integration and later identity-bound TLS/session handshake.
- Exact continuation: Move the passing loopback path into a production-internal transport adapter and add a two-process local integration harness; do not expose Netty types publicly.

## 2026-07-20 â€” dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0032
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the nine Netty 4.2.16.Final source JARs named by the failing Gradle verification report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; do not use `--offline`, disable verification, or record unverified local hashes.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 — candidate scan bug fix

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0037
- Active task: SL-DEMO-001
- Completed work: Changed the local interface provider to skip down adapters instead of terminating the scan. Added `demo/` protection to `.gitignore`.
- Commands run: direct Java candidate gather check; `gradlew.bat :link:test --tests org.synesis.link.candidate.CandidateGathererTest --tests org.synesis.link.candidate.CandidateNormalizationTest --dependency-verification=strict`; full `gradlew.bat clean check --dependency-verification=strict`.
- Results: Direct gather returned 10 candidates; targeted tests PASS. Full check remains blocked by seven package-info files removed in the user commit `0cc4d3a`.
- Decisions: Keep the minimal `continue` fix; do not add interface-specific or VPN-specific filtering.
- Failed attempts: None in the fix; the original failure was the provider’s premature `break`.
- Remaining work: Restore package-info files before a clean gate; rerun the physical demo with a fresh descriptor.
- Exact continuation: Restore the seven required `package-info.java` files, rerun strict check, then restart the server and recopy `demo\node-a.descriptor`.

## 2026-07-20 — package metadata restoration and strict verification

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0038
- Active task: SL-DEMO-001
- Completed work: Restored the seven required package-info.java files from the last valid commit.
- Commands run: `gradlew.bat :link:packageInfoCheck --dependency-verification=strict`; `gradlew.bat clean check --dependency-verification=strict`.
- Results: Focused package check PASS. First full run had one `NettyQuicLoopbackTest` NoClassDefFoundError; targeted rerun PASS and immediate full rerun PASS with all 40 tests.
- Decisions: No test or production workaround for the transient class-loading failure; retain the strict suite unchanged.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Restart the server with the candidate-provider fix and recopy the fresh descriptor.

## 2026-07-20 — first physical normal-operation demo

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0041
- Active task: SL-DEMO-001
- Completed work: Recorded a successful two-computer same-LAN normal-operation run.
- Evidence: `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`.
- Results: Host A advertised 10 candidates; Host B gathered 6 and generated 8 compatible pairs; direct LAN pair selected; both sides authenticated the expected node, reached `CONTROL_READY=true` and `LIVENESS=LIVE`, returned `WORK_RESULT=OK`, closed cleanly, and reported cleanup.
- Claim boundary: Classify only Scenario A as `TWO_MACHINE_VERIFIED`; abrupt process loss and wrong expected identity remain unverified.
- Remaining work: Execute and record Scenario B and Scenario C if physical evidence for those cases is required.
- Exact continuation: Repeat the demo for abrupt process loss, then wrong expected identity.

## 2026-07-20 — package-info gate removed by user request

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0042
- Active task: SL-DEMO-001
- Completed work: Removed the seven package-info.java files, removed the `packageInfoCheck` Gradle task from `:link:check`, and removed the corresponding active-contract requirements.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; compilation, strict Javadocs, formatting, static analysis, and all 40 tests passed.
- Decision: Package-level `package-info.java` documentation is intentionally out of scope by explicit user direction; public/protected API Javadocs remain required.
- Remaining work: Physical abrupt-loss and wrong-identity demo scenarios remain pending.
- Exact continuation: Repeat the demo for Scenario B abrupt process loss and Scenario C wrong expected identity.

## 2026-07-20 — Synesis root and Link module migration

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0036
- Active task: SL-DEMO-001 after completing SL-ARCH-001.
- Completed work: Moved Link `src/`, module `build.gradle.kts`, and module lockfile into `link/`; added root Synesis settings and build delegation; updated Link CLI commands and durable architecture/contract state; added ADR-0008.
- Commands run: `gradlew.bat projects --dependency-verification=strict`; `gradlew.bat clean check --dependency-verification=strict`; `gradlew.bat :link:demoCli --args=--help --dependency-verification=strict`; resume; doctor; fixture validator; deferred validator.
- Results: PASS; root discovery shows `synesis` and `:link`; root check executes `:link:check`; CLI and durable validators pass.
- Decisions: Keep one root modular monolith with Link as the first subproject; do not invent placeholder Synesis modules.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” Gradle and Kotlin tooling verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0035
- Active task: SL-DEMO-001
- Completed work: Added verification entries for the Gradle 9.0.0 source distribution and four Kotlin 2.2.0 artifacts named by the failing compile classpath report. The Gradle ZIP matched its official SHA-256 sidecar; Kotlin artifacts were byte-identical between the Gradle Plugin Portal and Maven Central.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; do not disable verification or record unverified local hashes.
- Failed attempts: Kotlin repository `.sha256` sidecar URLs returned 404; exact artifact bytes were independently compared with Maven Central before recording hashes.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” test runtime dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0034
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the three JUnit engine/runtime source JARs named by the failing `testRuntimeClasspath` report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; main, test compile, and test runtime classpaths now pass strict verification and all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; source artifacts remain allowlisted only by exact module/version/artifact/hash.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” test dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0033
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the six JUnit-related source JARs named by the failing `testCompileClasspath` report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; main and test classpaths now pass strict verification and all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; source artifacts are allowlisted only by exact module/version/artifact/hash.
- Failed attempts: The first PowerShell hash-collection command used an unescaped colon in an interpolated variable name and failed before downloading; the corrected command succeeded with no repository impact.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.
## 2026-07-20 — SL-012 zero-configuration onboarding slice

- Checkpoint: CP-0047
- Active task: SL-012
- Completed work: Added automatic identity bootstrap, bounded signed
  listener-first invitations, transcript capability binding, single-use
  admission with 15-second reservation expiry, ephemeral QUIC TLS material,
  source-run `SynesisCli`, and compact Unicode QR rendering while preserving
  `DemoCli`.
- Verification: invitation/bootstrap/admission/QR tests PASS; prior strict
  clean check PASS; two isolated-profile host/join processes completed control,
  liveness, demo work, and close. Physical onboarding is not claimed.
- Remaining work: physical onboarding and deferred demo negative-path
  scenarios remain unverified.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available.

## 2026-07-20 — SL-012 behavior-preserving cleanup

- Checkpoint: CP-0048
- Active task: SL-012
- Completed work: Replaced oversized doubled-width ASCII QR output with
  compact Unicode half-block rendering, terminal-width skipping, focused exact
  matrix/dimension tests, and a clearer renderer name. No package moves were
  made because transport classes share package-private handshake ownership.
- Verification: focused QR/protocol/admission tests PASS; forced narrow-terminal
  two-profile onboarding PASS with authenticated control, liveness, work, and
  graceful close. Invitation bytes, handshake, identity, admission/replay,
  and stable onboarding semantics remain unchanged.
- Physical status: physical SL-012 onboarding remains unclaimed; existing
  physical `DemoCli` Scenario A evidence remains valid.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available; do not claim it from process
  evidence.

## 2026-07-20 â€” SL-012 terminal QR compatibility fix

- Checkpoint: CP-0050
- Active task: SL-012
- Completed work: Detect whether the process output charset can encode the
  compact Unicode QR glyphs. Unsupported consoles now emit the explicit
  `QR_SKIPPED=UNICODE_UNSUPPORTED` status while preserving `SHARE_LINK`; the
  existing narrow-terminal skip remains unchanged.
- Verification: focused QR tests and `gradlew.bat clean check
  --dependency-verification=strict` PASS; no invitation or transport bytes
  changed.
- Remaining work: physical onboarding and deferred demo negative-path
  scenarios remain unverified.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available; do not claim it from process
  evidence.
## 2026-07-20 — standalone CLI development distribution

- Task: SL-013.
- Completed: promoted SL-013; added ADR-0010; added `:cli` with Picocli,
  Application distributions, typed commands, terminal output, QR rendering,
  exit mapping, doctor, and launcher tests; extracted Link onboarding into
  typed `Onboarding` events/failures; removed the legacy `:link:synesisCli` and
  Link-side ZXing/QR path while retaining `DemoCli`.
- Verification: `./gradlew.bat clean check --dependency-verification=strict`,
  `:cli:installDist`, generated launcher smoke, and generated two-profile
  onboarding all PASS.
- Remaining: physical generated-launcher onboarding and physical abrupt-loss/
  wrong-identity scenarios are not claimed.

## 2026-07-20 — SL-013 local generated-launcher validation

- Task: SL-013; validation scope CP-0054.
- Commands: `gradlew.bat clean check --dependency-verification=strict` and
  `gradlew.bat :cli:installDist --dependency-verification=strict` — PASS.
- Generated launcher: `--help`, `--version`, `identity show`, and valid
  `doctor` exited `0`; corrupt identity `doctor` exited `10`; invalid join
  exited `11` with `FAILURE=INVITE_INVALID`.
- Two-profile onboarding: generated `.bat` host/join completed authenticated
  control readiness, liveness, work result, and graceful close; a fresh second
  session also passed. This is one-machine process evidence, not physical
  two-machine evidence.
- Link-level negative paths: abrupt process loss and wrong identity tests PASS.
  Generated early-kill was attempted but did not reach a bounded host terminal
  status, so it is not claimed as generated-launcher abrupt-loss evidence.
- Reconnect: transparent reconnect is deferred; only fresh-session restart was
  observed.
- Next action: review the complete diff for secrets/artifacts, checkpoint, and
  commit the current SL-013/CP-0054 workspace changes without claiming
  physical generated-launcher validation.

## 2026-07-21 — CAF Phase 1 planning review

- Active task: SL-013 remains ACTIVE but frozen by the user's planning-only
  instruction; no implementation was performed.
- Completed work: Read the CAF concept PDF, current roadmap/task/evidence
  records, ADRs, deferred register, protocol/security documents, and the
  actual Link/CLI surfaces. Added the CAF phase map, proposed ADR-0011, and
  blocked `SYN-001` plan for one signed shared decision record.
- Finding: Link's public `PeerSession` permits only fixed demo work and
  `Onboarding` retains the session lifecycle. Authenticated record sync above
  Link cannot be implemented while Link is frozen. Recreating transport or
  treating Markdown as canonical state is rejected.
- Remaining work: user review must choose whether to keep the freeze or permit
  the one transport-neutral Link application-stream prerequisite described in
  ADR-0011. CLI remains frozen in either choice.
- Exact continuation: review `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`
  and ADR-0011 before any task promotion.

## 2026-07-21 — SL-014 activation

- User approved ADR-0011's required Link prerequisite.
- SL-013/CP-0054 is frozen and marked DONE; `:cli` remains unchanged.
- `SL-014` is now the only ACTIVE task. `SYN-001` remains BLOCKED on it.
- Scope is limited to one bounded transport-neutral authenticated application
  stream. No project, record, owner, sync, storage, or CLI work is permitted.
- Exact continuation: implement and verify the SL-014 seam, then checkpoint;
  do not begin SYN-001.

## 2026-07-21 — SL-014 verified

- SL-014 implemented the bounded opaque `SLA1` application-stream seam; Link
  retains framing, bounds, deadlines, liveness, and cleanup, and `:cli` is
  unchanged.
- Focused unit/integration/two-JVM tests and the full strict root check passed.
- Evidence: `docs/evidence/APPLICATION-STREAM-SEAM-2026-07-21.md`.
- `SL-014` is DONE. `SL-015` is the only ACTIVE review gate; `SYN-001`
  remains BLOCKED. No record storage or sync was implemented.

## 2026-07-21 — SYN-001 CP-R2 activation

- User approved closing `SL-015` as DONE and promoting `SYN-001`.
- Active scope is CP-R2 only: canonical signed `decision` v1, bounded
  deterministic encoding, immutable local revision/head storage, restart
  recovery, and a JDK-only inspection launcher in `:project-record`.
- CP-R4 networking, synchronization, extra record types, background behavior,
  and `:cli` changes remain deferred.

## 2026-07-21 — SYN-001 CP-R2 verified

- Added the JDK-only `:project-record` module with canonical bounded `SDR1`
  decisions, Ed25519 signatures, immutable revision/head storage, recovery,
  and local inspection.
- Duplicate, stale-base, conflict, signature/corruption, and restart-recovery
  tests pass; `:cli` and Link sources are unchanged.
- `gradlew.bat clean check --dependency-verification=strict` passed for all
  three modules. Stop before CP-R4 networking or sync.

## 2026-07-21 — SYN-001 CP-R4 verified

- Activated only CP-R4 above the verified SL-014 seam; `:cli` remains
  unchanged and SL-013/SL-015 remain frozen DONE.
- Added strict local project configuration, bounded `SRP1` messages, one-shot
  authenticated publish/sync, deterministic outcomes, and conflict quarantine.
- Two isolated JVM profiles prove initial publish, duplicate retry, valid
  successor, stale detection, same-version conflict, and a head-preserving
  quarantine; a sync request observes the shared head.
- Focused project-record check and full strict root verification pass. Stop
  before CP-R5 and broader CAF functionality.

## 2026-07-21 — SYN-001 closure and SYN-002 planning

- Closed `SYN-001` as DONE at CP-R4; recorded CP-R5 physical decision-record
  transfer as deferred under `SL-D-027`.
- Compared a second `failed-experiment` record with a minimal read-only
  searchable decision view. Selected the view for planning because it reuses
  verified signed decisions without adding schema, sync, or authority surface.
- Added ADR-0013 and promoted `SYN-002` as planning-only. Link and
  `:project-record` remain frozen except for proven blockers; no production
  implementation was started.

## 2026-07-21 — SYN-002 implementation verified

- Implemented only the local `DecisionSearch` view inside `:project-record`.
  It scans bounded fully validated current heads on demand and renders stable
  safe results; it does not persist an index or mutate state.
- Focused search tests and full strict root verification pass. Corrupt heads,
  stale revisions, conflicts, temporary files, bounds, empty results, filters,
  ordering, and restart equivalence are covered.
- Evidence: `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`. Stop after
  SYN-002 review/closure; no broader CAF slice is started.

## 2026-07-21 — SYN-003 planning and CP-W1 activation

- User approved SYN-003 and ADR-0014 after review through CP-0075.
- Closed SYN-002 as DONE and promoted SYN-003 as the sole ACTIVE task.
- Selected a thin JDK-only `:workspace` composition layer for isolated profile
  bootstrap, one-peer project creation, and revision-1 signed decision
  creation. Link, `:cli`, and `:project-record` production code remain frozen.
- CP-W1 is active. Host/join, sync, networking, and broader CAF behavior remain
  deferred to CP-W2 or later.

## 2026-07-21 — SYN-003 CP-W1 verified

- Added the JDK-only `:workspace` application and generated
  `synesis-workspace` launcher.
- Focused workspace tests and full strict root verification pass. Identity
  reuse, isolated profiles, project overwrite/mismatch refusal, signed
  revision-1 creation, restart readability, bounds, and safe output are
  covered.
- Evidence: `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`.
- Stop before CP-W2 host/join or sync.

## 2026-07-21 — SYN-003 CP-W2 verified

- Implemented only `:workspace` one-shot `sync host` and `sync join` over the
  existing Link onboarding seam and CP-R4 project-record sync API.
- Join authenticates and checks the expected host before creating B's project
  configuration; existing mismatched configuration is never overwritten. Host
  uses exactly one configured peer. Invitations are emitted only by host and
  are omitted from tests and evidence.
- Generated-launcher process tests cover APPLIED, DUPLICATE, wrong host,
  malformed invitation, project mismatch, missing record, and UNKNOWN on close
  before result. Full strict verification and validators pass.
- Evidence: `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`. Stop before CP-W3.

## 2026-07-21 — SYN-003 CP-W3 verified and closed

- Implemented `:workspace` search and inspect subcommands, completing CP-W3 and closing SYN-003.
- `decision search` composes the existing `DecisionSearch` API to scan only fully validated current heads.
- `decision inspect --record <uuid>` implements a dedicated single-record validator that validates the head and revision chain of the requested record, isolating it from corruption in other records.
- Outputs for search and inspect are stable, safe, and byte-stable. Standard CLI exit codes are preserved (0 for success, 10 for failure).
- Generated-launcher integration tests verify APPLIED, DUPLICATE, restart stability, empty search, malformed filters, missing records, corruption, conflicts, and stale revisions. Full strict check and validators pass.
- Evidence: `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`.

## 2026-07-21 — SYN-004 planning and activation

- User requested SYN-004 minimal guided workspace demo flow design.
- Analyzed the two-person demo. Determined that a single copyable URI can safely embed the Link invitation payload, project ID, record ID, and host Node ID parameters while preserving cryptographic host pinning.
- Corrected the roadmap in `PRODUCT_REVIEW.md` to ensure decision records are treated as declarative policies/metadata, never as executable tasks or job scripts.
- Documented the exact operator commands, security guarantees, command contracts, failure behavior, hints, and test matrix in `docs/agent/SYN_004_DESIGN.md`.
- Closed the planning/review task and promoted `SYN-004` as the ACTIVE task. All validators and checkpoint pass.
## 2026-07-22 — SYN-009A promotion

- User supplied the SYN-009A scope for a unified CLI, application services,
  project initialization, and local `.synesis` state layout.
- Repository truth showed SYN-008 DONE and zero ACTIVE tasks, so the mandatory
  resume validator failed before implementation. SYN-009A is now the sole
  ACTIVE task and durable state agrees on its scope.
- Architecture mode is EVOLUTION. The four existing Gradle modules remain;
  `:cli` becomes the only composition root, while `:workspace` becomes a
  library exposing small structured application services.
- Exact continuation: write the implementation note, then implement and test
  the smallest service/CLI vertical slice. Do not begin SYN-009B or SYN-009C.

## 2026-07-22 — SYN-009A verified at CP-0096

- Unified `synesis` launcher, application-service boundaries, project discovery,
  `.synesis` initialization, local profile layout, adapter package boundaries,
  launcher retirement, and documentation are implemented.
- `gradlew.bat clean check --dependency-verification=strict` passed with 34
  actionable tasks. `:cli:installDist`, generated init/repeat/help/hook checks,
  `:workspace:architectureCheck`, and focused service tests passed.
- CP-0096 was created. SYN-009B provider management and SYN-009C portable
  packaging remain unpromoted and unimplemented.
- Exact continuation: commit the verified SYN-009A changes; do not expand scope.

## 2026-07-22 — SYN-009C.1 native bundle

- Checkpoint: CP-0107
- Active task: SYN-009C
- Completed work: Promoted the supplied distribution goal; added the
  implementation note and ADR-0023 through ADR-0025; embedded build metadata,
  `synesis version`, a native jlink runtime, relative launchers, platform
  ZIP/tar.gz tasks, clean-room provider/init/doctor smoke, the Go bootstrapper,
  install scripts, and the CI matrix.
- Commands run: `:cli:platformBundle`; `:cli:bundleSmokeTest`;
  `:cli:platformArchive`; `clean check --dependency-verification=strict`;
  PyYAML workflow parse; attempted `gofmt -w . && go test ./...` with a
  temporary Go 1.26.5 toolchain.
- Results: Java bundle/archive/smoke/strict check PASS; workflow YAML PASS;
  Go attempt failed because the temporary toolchain exhausted disk space and
  its partial GOROOT lacked standard-library sources.
- Decisions: Keep Java authoritative; bootstrapper uses standard-library Go,
  detached Ed25519 plus SHA-256, bounded extraction, version pointer, and
  manual/public-release signing gates. Codex remains degraded and separate.
- Failed attempts: Initial smoke omitted the temp project directory and then
  lacked a configured provider launcher; both were fixed by creating the
  project and setting the bundle launcher fixture. A YAML parse found an
  unquoted colon and was fixed.
- Remaining work: Run/fix Go tests, build native bootstrap, verify fixture
  install/update/rollback/uninstall/doctor, then complete CI manifest evidence.
- Exact continuation: Run `gofmt -w . && go test ./...` from `bootstrap` using
  a complete Go 1.26.5 toolchain.

## 2026-07-22 — SYN-009C.2 Go bootstrap verification

- Active task: SYN-009C
- Completed work: Fixed the missing artifact download call, bounded ZIP/TAR
  size arithmetic, version-path validation, safe executable permissions,
  unsigned file-based development manifests, checksum-verifying installer
  scripts, and native subprocess smoke wiring in CI.
- Commands run: temporary Go 1.26.5 `gofmt -w .`; `go test -v ./...`; `go vet
  ./...`; native Windows `TestNativeBootstrapProcess`; six `GOOS/GOARCH`
  cross-compiles; PowerShell installer parse; YAML parse.
- Results: PASS. Install/update/no-op/failed-update preservation/doctor/
  uninstall-project-preservation, Ed25519 fixture verification, checksum and
  size rejection, ZIP traversal/absolute-path rejection, TAR executable
  preservation, and native process smoke all pass.
- Java follow-up: D: clean-room `clean check --dependency-verification=strict`
  PASS with 39 actionable tasks; `:cli:platformArchive` PASS with ZIP
  25,825,206 bytes and tar.gz 25,591,351 bytes. Two earlier clean-room runs
  were invalidated by copied Gradle caches/C: temp state; the final run used
  a fresh project, disabled build cache, and D: TEMP/TMP.
- Remaining work: create the phase-2 checkpoint, commit the verified slice,
  and continue to SYN-009C.3.

## 2026-07-22 — SYN-009C.3 release-consistency gate

- Checkpoint: CP-0108 phase evidence; CP-0109 final completion.
- Completed work: made native Java bundle smoke explicit in both Windows and
  Unix CI branches; validated the six-platform matrix, manifest aggregation,
  checksum/signature handling, install scripts, release docs, and deferred
  signing limitations.
- Results: final Java/Go/CI/native-smoke/manifest/signature/documentation/clean
  tree gate PASS. No secret was accessed and no release was published.
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1` and select a separately authorized task.

## 2026-07-22 — SYN-009C post-CP-0109 audit

- CP-0109 was reopened after an isolated real-archive trial found three gaps:
  Windows-only archive smoke, missing bundled launcher self-identification,
  and CI verification of the wrong manifest signature path.
- Fixes: bundle smoke now extracts the native ZIP/tar.gz archive and selects
  the native launcher; platform launchers set `SYNESIS_LAUNCHER`; CI verifies
  `bootstrap/manifest.json.sig`.
- Evidence: D: clean source copy `:cli:bundleSmokeTest :cli:platformArchive`
  PASS; actual Windows ZIP bootstrap trial PASS, including provider lifecycle,
  doctor, project preservation, uninstall, and an install root with spaces.
- Exact continuation: rerun final validators, create CP-0110, commit, and only
  then close SYN-009C.

## 2026-07-22 — SYN-009C final closure

- Checkpoint: CP-0110.
- Commit: `7a40324 Harden cross-platform bundle release trial`.
- Final evidence: Java strict clean build with archive extraction smoke, Go
  native test/vet and six cross-compiles, real Windows ZIP bootstrap trial with
  bundled version/init/provider lifecycle/doctor/uninstall, CI YAML and
  signature-sidecar validation, durable validators, and clean working tree.
- Status: `SYN-009C_STATUS=DONE`. Production signing-key replacement, OS vendor
  signing/notarization, public hosting, and real Codex trust validation remain
  outside this task.

## 2026-07-22 — SYN-010A public preview preparation

- Promoted SYN-010A as the sole ACTIVE task after SYN-009C closed at CP-0110.
- Prepared README, SECURITY.md, CONTRIBUTING.md, and `.gitignore` for an early
  developer preview without adding product behavior, release assets, or
  open-source claims.
- Added `docs/agent/SYN_010A_PUBLICATION_AUDIT.md` and
  `docs/legal/LICENSE_DECISION_REQUIRED.md`.
- Equivalent current-tree/reachable-history secret and privacy scans found no
  credential material; the only password-pattern match is an interactive TLS
  keystore prompt. Personal-looking commit author metadata and the old remote
  target require owner review; no history rewrite or push was performed.
- Verification: resume, doctor, deferred-register validation, fixture
  validation, `git diff --check`, ignore-rule checks, and documentation-link
  checks PASS. Publication was initially blocked by the unfinished license.
- Checkpoint: CP-0111.
- Exact continuation: record the supplied license decision, then obtain
  explicit push authorization before any public GitHub action.

## 2026-07-22 — SYN-010A license decision recorded

- Owner selected GNU AGPL v3.0 only (`AGPL-3.0-only`) and requested the
  commercial-license availability statement in README.
- Replaced the unfinished `LICENSE` placeholder with the canonical GNU AGPL
  text and updated the license decision, audit, and durable-state records.
- Checkpoint: CP-0112.
- No public push or repository visibility change was performed.

## 2026-07-22 — SYN-010A Go verification completed

- Go 1.26.5 was installed and verified with `bootstrap` `go test ./...` and
  `go vet ./...`: PASS.
- The Codex process PATH had not refreshed, so the standard install executable
  was invoked directly. No code changes were required.
- Checkpoint: CP-0113.

## 2026-07-22 — SYN-010A Linux Gradle wrapper permission fix

- Root cause: Git tracked `gradlew` as `100644`, so Ubuntu could not execute
  `./gradlew` and returned exit 126.
- Changed only the tracked mode to `100755`; Git Bash wrapper smoke and full
  `./gradlew check --dependency-verification=strict` both PASS.
- Checkpoint: CP-0114.

## 2026-07-22 — SYN-010A Linux dependency verification repair

- Added the Maven Central SHA-256 for the Linux x86_64 Netty QUIC native JAR
  missing from `gradle/verification-metadata.xml`.
- Updated CLI test process launch to use `synesis` directly on Unix and retain
  `cmd.exe`/`synesis.bat` on Windows.
- Native Windows `gradlew.bat clean check --dependency-verification=strict`:
  PASS. Linux CI remains the required final verification environment.
- Checkpoint: CP-0115.

## 2026-07-22 — SYN-010A remaining Linux test portability fixes

- Claude path test now derives absolute fixtures from the OS-native temp path
  instead of hard-coding a Windows drive path.
- QUIC loopback tests now reuse `TestTlsMaterial`, which selects `keytool` or
  `keytool.exe` by platform; the duplicate hard-coded helper was removed.
- Targeted workspace and link tests pass on Windows. Linux remains the final CI
  validation environment.
- Native Windows `gradlew.bat clean check --dependency-verification=strict`:
  PASS. Checkpoint: CP-0116.

## 2026-07-22 — SYN-010A bundle smoke executable-mode fix

- Root cause: Gradle `tarTree` extraction dropped the Unix launcher mode, so
  `bundleSmokeTest` received `EACCES` on Linux.
- `bundleSmokeTest` now asserts the source launcher mode and restores executable
  mode after extraction before running it. Native Windows bundle smoke passes.
- Checkpoint: CP-0117.

## 2026-07-22 — SYN-010A bundled Java runtime permission fix

- Root cause: Gradle `tarTree` extraction also dropped the Unix mode for
  `runtime/bin/java`, causing the next `EACCES` after the launcher fix.
- `bundleSmokeTest` now asserts and restores the bundled Java runtime mode as
  well. Native Windows bundle smoke passes.
- Checkpoint: CP-0118.

## 2026-07-22 — SYN-010A Unix launcher argument quoting fix

- Root cause: the shared process-test helper added literal `"` characters to
  arguments containing `&` or `*` even when invoking the Unix launcher directly.
- Quoting is now limited to the Windows `cmd.exe` path. Codex and unified-sync
  process tests pass locally; Ubuntu remains the final validation environment.
- Checkpoint: CP-0119.

## 2026-07-22 — SYN-010A macOS dependency verification repair

- Added the Maven Central SHA-256 for the macOS x86_64 Netty QUIC native JAR.
- Strict `:cli:startScripts` resolution with `os.name=Mac OS X` passes.
- Checkpoint: CP-0120.

## 2026-07-22 — SYN-010A CI abrupt-loss timing repair

- GitHub Ubuntu full strict check failed at `NettyQuicLoopbackTest` line 312:
  the server process did not classify the killed client within the ten-second
  parent wait. Local reproduction passed, identifying a timing-sensitive test
  bound rather than a production protocol failure.
- The independent-process helper now uses a test-only 250 ms heartbeat, 1 s
  suspicion, and 2 s expiry policy. Production `LivenessConfiguration.DEFAULT`
  remains unchanged.
- Focused abrupt-loss test passed three consecutive forced runs on Windows.
- Checkpoint: CP-0121.

## 2026-07-22 — SYN-010A Windows ARM64 Java/Actions runtime repair

- GitHub `bundles (windows-arm64)` could not resolve Temurin Java 25; the
  runner exposed only older cached distributions.
- `.github/workflows/release.yml` now uses Microsoft OpenJDK 25, which has a
  Windows ARM64 JDK 25 archive, and upgrades checkout, setup-java, setup-go,
  upload-artifact, and download-artifact to Node 24-compatible majors.
- Workflow YAML parses successfully. GitHub rerun remains required for native
  Windows ARM64 confirmation.
- Checkpoint: CP-0122.

## 2026-07-22 — SYN-010A Windows Gradle version-argument repair

- GitHub Windows bundle packaging parsed the unquoted
  `-PsynesisVersion=0.1.0-dev.10` suffix as task `.1.0-dev.10`.
- Quoting the complete version property in both Windows and Unix archive
  commands fixes the Gradle argument boundary. The exact Windows x64 command
  passes locally with `bundleSmokeTest` and `platformArchive`.
- Checkpoint: CP-0123.

## 2026-07-22 — SYN-010B aggregated release-candidate artifact

- Promoted SYN-010B as the sole active task from the supplied continuation
  request; no GitHub Release or remote publication was performed.
- Added `bootstrap/cmd/aggregate-release-candidate`, its local PowerShell
  wrapper, and validation tests for completeness, duplicates, manifest
  digests, signatures, archive traversal, exact output layout, checksums, and
  sensitive-text/path leakage.
- Changed matrix artifact names to short-retention internal names, added the
  aggregation job, and retained one 30-day `synesis-release-candidate` output.
- Verification: `go test ./...`, `go vet ./...`, PowerShell parse, and
  `gradlew.bat clean check --dependency-verification=strict` PASS.
- Hosted workflow confirmation remains pending until an authorized push/rerun.
- Checkpoint: CP-0126.
## 2026-07-22 — SYN-011 Antigravity real-integration investigation

- Promoted SYN-011 as the sole active task after the supplied failed real
  Antigravity protected-edit observation; preserved the test-project evidence
  before changing configuration or code.
- Found the original generated Windows hook command was not executable because
  the `.cmd` launcher was not wrapped through `cmd.exe`; corrected that command
  minimally and added an exact real-shape `replace_file_content` regression
  fixture.
- Manual exact-command result: `deny`, bounded reason on stdout/stderr, exit
  `0`. Direct constraint result: `BLOCKED`, exit `10`.
- Real Antigravity 1.0.16 still modified the protected file with no observed
  hook invocation or decision in transcript/database diagnostics. Provider
  status is now `DEGRADED`/`UNVALIDATED`; the protected file was restored to
  the canonical bytes.
- Real unrelated editing succeeded with `--add-dir`; proposal replan reached
  `proposals/tcp-fallback.md`. Full sanitized evidence is in
  `docs/evidence/antigravity-real-investigation-2026-07-22/report.md`.
## 2026-07-22 — Local Synesis installation

- Built the current source as `0.1.0-dev.16` with the Windows platform bundle
  task and installed it under
  `C:\Users\Liparakis\AppData\Local\Synesis\versions\0.1.0-dev.16`.
- Switched the local `current` pointer from `0.1.0-dev.15` to `0.1.0-dev.16`;
  the previous version directory was preserved.
- Installed launcher verification passed: `synesis version` reports
  `0.1.0-dev.16`, help exits `0`, and Antigravity status remains intentionally
  `DEGRADED`/`UNVALIDATED`.
- Existing `bundleSmokeTest` still fails because it expects Antigravity
  `HEALTHY`; that expectation conflicts with the real-validation health gate
  and was not weakened.

## 2026-07-22 — SYN-009D stable flat installation promotion

- The supplied stable-layout request activated the ready packaging capability
  `SL-D-024` as SYN-009D and superseded the old deferred entry.
- SYN-011 remains VERIFYING with its failed real Antigravity evidence intact;
  it is no longer the sole active task.
- ADR-0026 records one stable root, sibling staging, one temporary rollback,
  legacy migration, user PATH ownership, and the provider launcher policy.

## 2026-07-23 — SYN-009D implementation and verification

- Replaced versioned bootstrap activation with a flat stable root, sibling
  staging, one rollback root, signed/checksum validation, safe extraction,
  rollback recovery, legacy migration, user PATH ownership, doctor, and
  uninstall cleanup.
- Updated provider launcher resolution to PATH-first plus OS stable fallback;
  preserved the Antigravity degraded-health expectation and existing Link data.
- Passed Go tests/vet, Windows/Linux/macOS cross-builds, strict Java checks,
  disposable migration/rollback tests, and native Windows archive smoke.
- Migrated the local install from `current`/`versions/` to
  `%LOCALAPPDATA%\Synesis` after automated verification; stable `version`,
  application `doctor`, bootstrap `doctor`, PATH resolution, identity
  preservation, and cleanup checks passed.

## 2026-07-23 — Antigravity generated-wrapper correction

- Traced the external `SynesisTestProject` failure: quoted PowerShell `-File`
  parsing prevented wrapper launch; removing quotes exposed the inherited
  working-directory bug; Synesis returned `Project is not initialized.` with
  exit `10` outside the project root and the expected protected-scope deny with
  exit `0` from the root.
- Changed the generated Antigravity integration to write a project-local
  wrapper, resolve `synesis.cmd` PATH-first with the stable fallback, set the
  project root, forward stdin unchanged, keep diagnostics on bounded stderr,
  emit one compact JSON decision, and return exit `0`.
- Added `cli` process regression coverage for a non-project working directory;
  the protected file remained unchanged. No constraints, protected project
  files, or remote repositories were modified.
- Commands: `gradlew.bat :workspace:test --tests
  org.synesis.workspace.ProviderApplicationServiceTest --no-daemon`;
  `gradlew.bat :cli:test --tests org.synesis.cli.AntigravityHookProcessTest
  --no-daemon`; `gradlew.bat clean check
  --dependency-verification=strict --no-daemon`.
- Results: PASS.
- Remaining work: real Antigravity project-hook discovery/loading remains
  unverified; provider status remains `DEGRADED`/`UNVALIDATED` until a real
  protected edit is denied and replanning is observed.
- Exact continuation: run the repository resume script and inspect the new
  checkpoint for durable-state consistency.

## 2026-07-23 — Antigravity reinstall and final wrapper correction

- The real external-project smoke test caught a malformed `cmd.exe /c`
  argument quote in the generated wrapper; corrected it and added an explicit
  `ProcessStartInfo.WorkingDirectory` set to the project root.
- Built a clean versioned Windows bundle as `0.1.0-dev.17` and reinstalled it
  under the stable root using the local development manifest. The stable
  launcher reports `0.1.0-dev.17`.
- Reinstalled the Antigravity provider into
  `C:\Users\Liparakis\Desktop\SynesisTestProject` and invoked its wrapper
  from outside the project root. Result: one compact protected-scope deny
  JSON line on stdout, exit `0`, bounded diagnostic on stderr, and the
  protected-file hash unchanged.
- Final commands: `gradlew.bat clean check
  --dependency-verification=strict --no-daemon`; repository resume; external
  wrapper smoke. Results: PASS.
- Exact continuation: run the repository resume script and inspect CP-0134;
  no feature work remains for this slice.

## 2026-07-24 — SYN-013B project-session binding

- Added automatic project-scoped provider session binding for Codex and
  Antigravity. Bindings are atomic, versioned, local-only, credential-free,
  and preserve project/node identity; explicit provider evidence is hashed and
  missing evidence is reported as fallback.
- Wired both project hook paths and provider install/status. Added ADR-0030,
  migration/installation guidance, protocol and maturity updates, and the
  deferred real-validation gate.
- Verification: `:workspace:test` focused binding tests PASS;
  `:coordination:check` PASS; root `check` PASS; Windows x64 bundle rebuilt and
  installed at `%LOCALAPPDATA%\\Synesis`; external project provider reinstall
  yielded distinct Codex/Antigravity session, supervisor, and worker records
  with unchanged project/node IDs.
- Remaining: real provider mutation interception, workspace transition, and
  re-plan evidence remain deferred; no provider is marked real-validated.

## 2026-07-23 — SYN-012 coordination foundation

- Promoted SYN-012 as the sole active task after the explicit speculative
  coordination request; recorded ADR-0027 and the bounded architecture/state
  machine/protocol plan.
- Added the `:coordination` module with bounded prediction contracts, signed
  command envelopes, coordinator-signed hash-chained events, deterministic
  lifecycle projection, durable command idempotency, and replayable subscriber
  queues. Existing Link and provider behavior were not changed.
- Verification: `.\gradlew.bat :coordination:check --no-daemon` passed,
  including strict Javadocs, format/static checks, and two focused tests.
- Exact continuation: implement loopback HTTP commands and SSE replay, then
  verify reconnect semantics before touching supervisor/provider integration.

## 2026-07-23 — SYN-012 coordination transport

- Added loopback HTTP `/command` and replayable SSE `/events` over the
  coordinator service, preserving signed command idempotency and durable event
  replay.
- Added an HTTP command/SSE replay test. `.\gradlew.bat :coordination:test
  --no-daemon` passed; strict `:coordination:check` passed before this slice.
- Exact continuation: implement provider `REQUEST_OWNER` enforcement and a
  minimal foreground supervisor inbox before worktree speculation.

## 2026-07-23 — SYN-012 real CLI acceptance complete

- Ran the built Synesis CLI as one coordinator and two independent supervisor
  processes across separate profiles, identities, cursors, worktrees, and
  branches. The run passed live ownership/prediction delivery, controlled A
  restart with ordered replay, unresolved and resolved Git gates, real owner
  implementation, validation, and retirement.
- Evidence: `docs/evidence/speculative-coordination-real-cli-2026-07-23/report.md`.
- Root strict verification passed after adding the integration-gate regression
  test: coordination check and root check both PASS.
- Exact continuation: remote HTTPS/enrollment remains deferred; no remote
  publication is authorized.

## 2026-07-23 — SYN-012 ownership and supervisor slice

- Added durable semantic ownership evaluation, policy-first `REQUEST_OWNER`
  guardrail handling for Antigravity/Claude adapters, optional ownership-aware
  constructors, and a foreground `SupervisorInbox` with local event copies.
- Added two-supervisor coordination and ownership non-transfer tests. Root
  `.\gradlew.bat check --no-daemon` passed in 90 seconds, including CLI,
  workspace architecture, Link, project-record, and coordination checks.
- Exact continuation: implement bounded worktree/speculation metadata and a
  Git acceptance gate for accepted predictions.

## 2026-07-23 — SYN-012 speculation isolation

- Added `SpeculationWorkspace` for detached worktrees, bounded metadata, and a
  fail-closed Git gate; whitespace and unmerged index states reject before
  integration.
- Added a temporary Git repository integration test. `.\gradlew.bat
  :coordination:check :workspace:check --no-daemon` passed.
- Exact continuation: run the exact two-node acceptance scenario and preserve
  event sequence, digest, commit, gate, and final Git-state evidence.

## 2026-07-23 — SYN-012 lifecycle evidence

- Added supervisor lifecycle methods and an end-to-end test covering
  `PROPOSED` through `RETIRED` (nine ordered events) with replay evidence.
- Focused coordination tests pass; the remaining unverified requirement is a
  real two-process/CLI acceptance transcript, not just an in-process harness.
- Exact continuation: run the real two-node acceptance scenario and preserve
  event sequence, digest, commit, gate, and final Git-state evidence.

## 2026-07-23 — SYN-012 final local verification

- Final root `.\gradlew.bat check --no-daemon` passed: 42 actionable tasks,
  including strict Javadocs/static analysis, workspace architecture, CLI
  bundle/launcher smoke, Link, project-record, and all coordination tests.
- No real two-process CLI acceptance was claimed; the next slice must capture
  that external transcript before SYN-012 can be marked DONE.

## 2026-07-23 — SYN-012 final bound/race correction

- Enforced the aggregate prediction-contract encoded-size bound and made SSE
  subscription replay registration atomic with respect to command publication.
- Final coordination check passed. Real two-process CLI evidence is still an
  explicit next action, so SYN-012 remains ACTIVE.

## 2026-07-23 — SYN-012 root verification

- Root `.\gradlew.bat check --no-daemon` passed after adding the coordination
  module; workspace architecture validation and existing Link/project-record/
  workspace/CLI checks remain green.
- Exact continuation: implement provider `REQUEST_OWNER` enforcement and a
  minimal foreground supervisor inbox before worktree speculation.
# 2026-07-24 — SYN-013 activation and architecture plan

- Closed SYN-012 against the recorded CP-0144 real CLI evidence and activated
  SYN-013 as the sole ACTIVE task.
- Added the zero-touch architecture package, explicit provider/workspace
  capability gates, phased MVP plan, ADR-0029, and durable-state reconciliation.
- No production code or runtime behavior changed.
- Exact continuation: run the deferred-register validator, strict repository
  check, and checkpoint validator; then create the documentation-only commit.

## 2026-07-24 — SYN-013A activation

- User authorized implementation of the approved AGENTS.md bootstrap contract.
- Promoted SYN-013A as the sole ACTIVE task; scope is limited to init-managed
  AGENTS.md content and focused tests.
# 2026-07-24 — SYN-013B provider-session binding activation

- Closed SYN-013A after AGENTS.md bootstrap tests and bundled smoke passed.
- Activated SYN-013B as the sole ACTIVE task for automatic project-scoped
  provider session binding.
- The implementation preserves project/node identity and keeps fallback
  provider evidence explicitly degraded; real Codex/Antigravity validation is
  not claimed.

# 2026-07-24 — SYN-013B Codex workspace safety correction

- Removed only the stale Codex proof file from the external validation project;
  the Antigravity proof file was absent. The external project has no committed
  `HEAD`, so it cannot receive a detached session worktree.
- Added durable session worktree allocation for committed Git projects and a
  fail-closed hook cwd/worktree gate. Provider status now reports workspace
  assignment and interception as unproven rather than readiness.
- Focused workspace and CLI tests pass; the external project remains degraded
  by design until a real provider session can transition into its assigned
  worktree.

# 2026-07-24 — SYN-013B worktree provisioning and routing

- Added explicit `GIT_HEAD_UNAVAILABLE` handling and an unborn-repository init
  policy that creates one minimal Synesis-only commit when Git has no `HEAD`.
- Added user-local per-project worktree allocation, durable branch/common-dir/
  control-path/verification fields, Git registration and ancestry checks, and
  recovery for deleted or stale worktrees.
- Added an assigned-worktree marker so hooks can load policy from the control
  checkout while resolving patch paths in the assigned tree. The final bundle
  produced a valid external Codex worktree and a no-output assigned-worktree
  hook result; actual Codex interception is still not claimed.

# 2026-07-26 — SYN-014E Slice 5C.2 Codex TOML migration

- Replaced the Codex JSON MCP assumption with bounded editing of the real
  `%USERPROFILE%\\.codex\\config.toml`, preserving unrelated text and nested
  tables while managing only `mcp_servers.synesis`.
- Added compare-and-set migration, verified backup/rollback, idempotent
  lifecycle integration, and read-only Doctor inspection. Source commit:
  `e278ea735318c6dbf84d8bcff3435034335c2322`.
- `codex mcp get/list` recognizes the stable Synesis command and args. A
  bounded real Codex retry reached the agent loop but did not expose Synesis
  tools because MCP handshake timed out; no real provider success is claimed.

# 2026-07-26 — SYN-014E Slice 5C.3 Codex MCP handshake correction

- Resolved the CP-0202 base discrepancy against actual `HEAD` `b61b0dc`; the
  reported `fe8b3e3` is a descendant of reported `e278ea7`.
- Isolated all four local layers. Direct payload and the stable `.cmd` pointer
  returned clean stdio and exactly 11 tools. Opt-in tracing showed Codex
  `0.140.0` sending `initialize` with `2025-06-18`, while Synesis serialized
  integer JSON-RPC IDs as floating-point values (`0.0`), which Codex rejected.
- Corrected `ProviderJson` integer parsing, added protocol-version negotiation,
  and added disabled-by-default trace-file diagnostics. Rebuilt and activated
  Version B `0.1.0-dev-local-5c6`.
- Real Codex now reaches initialize, receives the valid response, sends
  `notifications/initialized` and `tools/list`, and receives all 11 tools.
  Noninteractive Codex runs cancel MCP tool calls, so successful provider tool
  execution is not claimed. Required Gradle and Go verification passes.
# 2026-07-26 — SYN-014E Slice 5C.6 signed Codex closure

- Resolved actual source lineage: `HEAD=8d5447b35772a99f7097faa10f401c6c6156a714`; both reported freshness fixes are ancestors and the source tree is clean.
- Classified prior successful tool calls as `SYNTHETIC_MCP_HARNESS`; no evidence attributes them to a real Codex process.
- Audited the unchanged 11-tool MCP surface: the content-hash precondition is an explicit backward-compatible optional/clarifying contract, with `patch_precondition_required` for omission.
- Built candidate `0.1.0-dev-local-5c10` from the actual HEAD with deterministic payload hash metadata. The established signer requires `SYNESIS_MANIFEST_PRIVATE_KEY_B64`; it is absent, and the unsigned release-mode manifest was rejected before update planning.
- Required Gradle checks, Go tests, and Go vet passed. Existing active `0.1.0-dev-local-5c9`, fixture control checkout, and preserved worker evidence were not altered. No processes were terminated and no worktrees were force-removed.
# 2026-07-26 — SYN-014E Slice 5C.7 signing provisioning and real Codex retry

- Audited the trust model: Ed25519 detached signatures, embedded release public key, signer private key supplied only through `SYNESIS_MANIFEST_PRIVATE_KEY_B64`, and no pre-existing matching local credential or registration path.
- Added explicit acceptance-only trust validation and `provision-acceptance-key`; generated the user-scoped private key outside Git with restrictive ACLs. Acceptance mode requires a signed `developmentOnly` manifest, matching public-key identifier, and explicit `--acceptance`; release verification remains isolated.
- Verified matching-key acceptance, tampered-manifest rejection, wrong-key rejection, unsigned acceptance rejection, and release isolation. Built and activated signed `0.1.0-dev-local-5c11` from `b5f264a`; previous `5c10` remains retained.
- Corrected the Codex MCP launcher entry through supported `codex mcp remove/add`, preserving `other-server` and `notify`; the real Codex process loaded the global config, discovered 11 tools, and executed ensure/read/apply/run/get. A complete final mutation/test closure was not claimed because repeated refreshes caused genuine workspace-generation rejections on later test patches.
- Gradle checks, Go tests, and Go vet passed. Fixture control checkout stayed clean; no process termination or forced worktree removal was used.

# 2026-07-26 — SYN-014E Slice 5C.8 multi-file freshness

- Resolved lineage from reported `b5f264a`/`35cc7bd` to actual implementation base `49da341ae33123cd040ce96f9d393ef8f7aa12ac`.
- Added per-file revisions and explicit reason codes for file revision staleness, patch-context mismatch, and workspace-generation change; exact connection-instance binding is preserved.
- Workspace tests (154), full Gradle checks, Go tests, and Go vet passed. Sequential multi-file and same-file old/new revision tests pass.
- Built, verified, prepared, and activated signed `0.1.0-dev-local-5c12`; active pointer retains `0.1.0-dev-local-5c11` as previous.
- Real noninteractive Codex loaded the preserved TOML, discovered 11 Synesis tools, executed ensure/read/read/apply/apply/run/get, passed `run-tests.cmd`, and returned no pending action. Fixture control checkout remained clean; no process termination or forced worktree removal was used.

# 2026-07-26 — SYN-014E Slice 5C.9 Antigravity discovery preflight

- Resolved the installed Antigravity surface: version 2.3.1 at the local Windows application path; production MCP configuration is `%USERPROFILE%\\.gemini\\antigravity\\mcp_config.json`, with the matching `%USERPROFILE%\\.gemini\\config\\mcp_config.json` mirror.
- Verified the sole Synesis entry uses the stable `synesis.cmd` launcher and exact `mcp --provider antigravity` arguments, with no worker path, fixture path, credentials, or model override. No migration was needed and no unrelated settings were changed.
- Gradle checks and Go tests/vet passed. Fixture control checkout remains clean; signed `0.1.0-dev-local-5c12` remains active with `5c11` previous. Read-only cleanup/reconciliation found no executable actions or ambiguous active leases.
- Real Antigravity GUI discovery and tool execution remain unproven pending the operator's required close/reopen/open-workspace action. No production Synesis defect has been demonstrated.

# 2026-07-26 — SYN-014E Claude Code MCP provider support

- Renamed the canonical provider ID to `claude` and retained `claude-code` as a registry input alias. Claude Code hook configuration remains `.claude/settings.json`.
- Added project-scoped `.mcp.json` management with append/update-only `mcpServers.synesis`, launcher command preservation, `mcp --provider claude` arguments, idempotent reinstall, and unrelated-entry-preserving uninstall. Claude Desktop configuration was not added.
- Focused `ProviderApplicationServiceTest`, provider/module checks, root Gradle `check`, and bootstrap `go test ./...`/`go vet ./...` pass. Existing 11-tool MCP surface is unchanged.

# 2026-07-26 — SYN-015 STRUCT-1A foundational package completion

- Paused `SYN-014E` before production edits and promoted `SYN-015` as the sole validator-recognized ACTIVE primary task for the staged package-structure refactor.
- Completed `STRUCT-1A` only: reorganized packages within `:project-record`, `:coordination`, and `:link`, moved `DemoCli` to `org.synesis.link.cli`, and updated the `link` Gradle main-class reference.
- Required validation passed: `.\gradlew.bat :project-record:check --no-daemon`, `.\gradlew.bat :coordination:check --no-daemon`, `.\gradlew.bat :link:check --no-daemon`, `.\gradlew.bat check --no-daemon`, `go test -count=1 ./...`, and `go vet ./...`.
- Committed the slice as `Reorganize Synesis foundational packages` at `376f2d2ce6003b32d28994b19b6728926ab0af6e`.
- Stopped before `STRUCT-1B`; the only remaining work in this session is durable-state reconciliation and checkpoint creation for the completed slice.

# 2026-07-26 — SYN-015 STRUCT-1B activation

- Sealed the STRUCT-1A durable-state reconciliation in `95f696cc6442b426b28d7e2f2d7b7dd54a43b541`.
- Confirmed a clean worktree and passing `:workspace:check`, `:cli:check`, and `:mcp:check` preflight from that commit.
- Activated `STRUCT-1B — Workspace packages` as the sole active subtask under `SYN-015`; `STRUCT-1C` and `STRUCT-1D` remain inactive.
- No workspace production edits have started. The next action is the required workspace FQN reference inventory.

# 2026-07-26 — SYN-015 STRUCT-1B workspace package completion

- Reorganized only the `:workspace` module: project/bootstrap ownership, provider-specific adapters, JSON/configuration, lifecycle cleanup/reconciliation/repair/lease, and responsibility-specific infrastructure packages.
- Removed production references to the old `workspace.integration`, flat lifecycle, guardrail, provider JSON, and migration Codex TOML package names; aligned moved tests with production packages.
- Added narrow workspace architecture tests for removed integration ownership, delivery isolation, provider-neutral contracts, and production/test separation.
- Required validation passed: `:workspace:check`, `:coordination:check`, `:cli:check`, `:mcp:check`, root `check`, `go test -count=1 ./...`, `go vet ./...`, and focused `McpTool11Test`.
- Committed as `Reorganize Synesis workspace packages` at `b67ac1c`; stopped before `STRUCT-1C`.

# 2026-07-26 — SYN-015 STRUCT-1B ownership correction and final checkpoint

- The initial workspace move exposed an accidental `application -> provider -> project -> application` package cycle.
- Restored `ProjectApplicationService` to `workspace.application` and moved application-facing agent services and `TranslatedOutcome` into `workspace.application`; retained agent response/value records in `workspace.agent`.
- Updated the required CLI FQNs and architecture/test package references. No new interface or durable/public format was introduced.
- Verified `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go tests, Go vet, focused `McpTool11Test`, stale FQN scan, and an acyclic production workspace package graph.
- Committed the correction as `248889a`; created checkpoint `CP-0214`; stopped before `STRUCT-1C`.

# 2026-07-26 — SYN-015 STRUCT-1C MCP package completion

- Activated STRUCT-1C after baseline Gradle and Go validation passed.
- Moved `McpProtocolHandler` to `org.synesis.mcp.application` and `McpStdioServer` to `org.synesis.mcp.transport.stdio`; retained `SynesisMcpServer` at the stable root package.
- Added narrow MCP package architecture tests and protocol/transport package markers; moved MCP tests to mirror the application package.
- Verified `:mcp:check`, focused persistent MCP tests, `:cli:check`, root `check`, and stale production FQN scans.
- Committed as `5cb0656`; STRUCT-1D is now the sole active subtask.

# 2026-07-26 — SYN-015 STRUCT-1D CLI package completion

- Moved command implementations into identity, sync, provider, hook, project, workspace, coordination, prediction, ownership, task, speculation, and lifecycle packages; retained cross-cutting root command wiring.
- Moved representative CLI tests to mirror production command families and added CLI package architecture checks.
- Verified CLI/MCP/root Gradle checks, launcher help/version/init/doctor/provider/workspace/lifecycle acceptance, and stale command-FQN scanning.
- Committed as `958a039`; QUALITY-DEDUP is now the sole active subtask.

# 2026-07-26 — SYN-015 deduplication completion

- Audited repeated infrastructure and lifecycle logic. One strong identical group was confirmed: SHA-256 UTF-8 plan hashing duplicated in cleanup, reconciliation, and repair plan stores.
- Extracted lifecycle-owned `PlanIntegrity` with direct coverage; no generic utility or behavior rewrite introduced.
- Verified `:workspace:check`; committed as `98755b3`; QUALITY-WARNINGS is now the sole active subtask.

# 2026-07-26 — SYN-015 warning cleanup completion

- Warning-mode Gradle audit identified four execution-time `project.file(...)` deprecations in verification tasks.
- Replaced those lookups with `layout` providers without changing the files scanned or runtime behavior.
- Verified root `check --warning-mode all` with zero warning lines; committed as `98cda05`; QUALITY-GOD is now the sole active subtask.

# 2026-07-26 — SYN-015 god-class split completion

- Ranked oversized classes by size and responsibility concentration; selected `ProviderApplicationService` as the strongest safe candidate.
- Extracted MCP configuration persistence into `ProviderMcpConfigurationService` while preserving the stable facade and provider result strings.
- Verified `:workspace:check` and focused provider tests; committed as `04977b9`; final repository validation is next.

# 2026-07-26 — SYN-015 final validation completion

- Full Gradle module matrix and root `check` passed; Go tests and vet passed.
- Focused MCP server/tool tests passed with exactly 11 tools; stale production FQN and production-to-test scans were clean; no module dependency changes were found.
- CLI help/version and command-family help passed; the disposable CLI MCP launcher retains its pre-existing missing MCP runtime classpath limitation.
- Working tree was clean before this final durable checkpoint; `SYN-015` is complete and `SYN-014E` remains paused.
# 2026-07-26 — SYN-016 coordination domain organization

- Reorganized the flat coordination domain into `capability`, `task`, `ownership`, `prediction`, `integration`, `speculation`, and `command` packages.
- Updated production/test references and mirrored coordination tests; added a package ownership architecture test without adding package-info files.
- Verified `:coordination:check`, root `check`, Go tests/vet, stale-FQN scans, and no-flat-package scans.
- Committed as `195fc95`; SYN-016 is complete and `SYN-014E` remains paused.
# 2026-07-27 — SYN-018 repository hygiene

- Promoted `SYN-018` as the sole active task; `SYN-014E` remains paused.
- Inventoried 353 Markdown files (228 checkpoints, 32 ADRs, 16 evidence files)
  and 12 scripts/launchers. Preserved all historical evidence and found no safe
  duplicate script entrypoint to consolidate.
- Updated the README, maintained navigation, provider commands/paths, current
  state, generated `AGENTS.md` instructions, and the durable inventory. The
  generated template source is `ProjectApplicationService`.
- Added root `repositoryHygieneCheck` for maintained links, script references,
  machine paths, canonical provider commands, duplicate script names, and MCP
  tool-count claims. It passes on 39 maintained Markdown files.
- Focused MCP tests and exact 11-tool confirmation, Go test/vet, CLI help/version,
  provider list, and generated-init instructions pass. Sequential root check
  still fails only at the pre-existing workspace architecture-test assertion.
- Commits: `4b7f530` documentation; `59f7c63` hygiene checks. No script cleanup
  commit was needed. Exact continuation: preserve the final checkpoint and
  leave the architecture-test mismatch for a separately authorized task.
# 2026-07-27 — Root AGENTS.md current-boundary correction

- Corrected the handwritten repository `AGENTS.md`, which still described only
  the Link-stage repository. It now names the current CLI, workspace,
  coordination, MCP, and provider boundaries, the exact 11-tool surface,
  revision-bearing MCP reads/patches, canonical provider IDs, and deferred
  remote-networking claims.
- Corrected startup references to the durable files under `docs/agent/`.
- The initialized-project generated template remains owned by
  `ProjectApplicationService` and was not conflated with the repository
  contract.
# 2026-07-27 — SYN-019 workspace architecture-test closure

- Reproduced `WorkspaceApplicationPackageArchitectureTest` failure.
- Inspected the production root and callers: only `ProjectApplicationService`
  remains directly under `org.synesis.workspace.application`; the other names
  in the test allowlist already live in `application.constraint` or
  `application.provider` and have updated callers.
- Narrowed the architecture-test allowlist to `ProjectApplicationService` only;
  no production type moved and no behavior or public surface changed.
- `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go test/vet,
  focused MCP 11-tool tests, CLI help/version, provider list, init, and hygiene
  checks pass before the unrelated README edit appeared. That edit triggers a
  false positive in the existing hygiene count regex (`%20tools-11`) and was
  preserved outside this task. Commit: `a87d3d8`.
## 2026-07-27 — Bootstrap versioned activation portability correction

- Checkpoint: CP-0235
- Active task: SYN-019, with explicit user authorization for one narrow Go
  bootstrap portability correction.
- Completed work: Versioned activation now moves the validated payload into its
  final version directory before applying immutable permissions. If hardening
  fails, the partially hardened payload is made removable and deleted.
- Verification: `go test -count=1 ./...`, `go vet ./...`, `git diff --check`, and
  all six CI target cross-builds pass. The original failure is not reproducible
  on the local Windows host.
- Failed attempts: None.
- Remaining work: Review and commit disposition; do not modify the unrelated
  README edit.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
## 2026-07-27 — Deferred register refinement

- Active task: SYN-019; documentation-only deferred-register cleanup authorized
  by the user.
- Completed work: Reduced the active register to nine realistic capabilities,
  added contract invalidation, out-of-band mutation enforcement, and wait-for
  deadlock detection, merged connectivity capabilities, archived historical
  IDs SL-D-001 through SL-D-030, and created the network validation matrix.
- Product boundary: no Synesis-hosted server, third-party rendezvous, STUN,
  TURN, relay infrastructure, or production peer discovery dependency; no
  harness intelligence or automatic harness-code rewriting claim.
- Verification: deferred validator PASS (9 entries); `./gradlew.bat check
  --no-daemon --dependency-verification=strict` PASS; bootstrap Go test and vet
  PASS; repository hygiene and maintained Markdown link checks PASS.
- Remaining work: Review, checkpoint, and commit this documentation slice.

## 2026-07-27 — Deferred coordination correctness features

- Active task: SYN-019; documentation-only deferred-feature addition.
- Completed work: Reused and expanded `SL-D-037`–`SL-D-039` with explicit
  contract revision/dependency invalidation, out-of-band mutation detection and
  integration enforcement, and wait-for dependency detection without claiming
  implementation. No duplicate IDs or separate parking-lot documents were
  created.
- Product boundary: Synesis communicates invalidation and enforcement evidence
  but does not rewrite, regenerate, or repair harness code; no portable
  filesystem prevention claim is made; lease expiry is not treated as deadlock
  resolution.
- Verification: deferred validator PASS; Gradle strict check PASS; bootstrap
  Go test and vet PASS; no production, CLI, or MCP changes.
- Remaining work: Create CP-0241 and commit this documentation slice.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-07-29 — SYN-025 provider collaboration roadmap completion

- Active task: SYN-025 provider collaboration acceptance and evidence.
- Completed work: Reconciled the final MCP parity slice, direct Codex/Claude/
  Antigravity process evidence, handoff, release, deleted-chat recovery, and
  JSON-safe discovery projections. Marked SYN-025 DONE at CP-0292.
- Provider boundary: direct MCP configuration, connections, mutations, claims,
  handoff, release, and recovery are evidenced for all three providers where
  available. Antigravity model-driven prompting and native-hook maturity remain
  explicitly unclaimed after the recorded harness limitation.
- Verification: `./gradlew.bat check :cli:installDist --no-daemon
  --dependency-verification=strict` BUILD SUCCESSFUL (50 actionable tasks);
  bootstrap `go test -count=1 ./...`; `go vet ./...`; task-tracker `python -m
  pytest -q` (45 passed); deferred validator; and `git diff --check` PASS.
- Remaining work: No collaboration roadmap task remains active. Do not publish,
  push, or claim native provider enforcement without new evidence.
## 2026-08-02 — SYN-036 promotion

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Reconciled SYN-035 as DONE at CP-0399 after the ten-tool lifecycle surface,
  strict schemas, managed manual, Codex ordinary completion evidence, full
  sequential Gradle verification, bootstrap Go tests/vet, and provider
  limitation evidence were recorded.
- Promoted SYN-036 as the sole active task. No production changes were made
  during promotion.
- Exact continuation: inspect the current MCP catalog and administrative-state
  abstractions, then implement task 1.
## 2026-08-02 — SYN-036 task 1 complete

- Completed the authoritative ten-tool MCP catalog foundation in `mcp-contract`.
- Removed the duplicate tools/list schema builder from `McpProtocolHandler`;
  exact schemas now come from one descriptor source.
- Added non-circular wire compatibility, catalog-content, and guidance-artifact
  identities, managed-manual/provider freshness diagnostics, and canonical
  Git-common-directory administrative-state resolution.
- Verification: `:mcp-contract:check`, `:workspace:check`, and `:mcp:check`
  passed sequentially; commit `71b33c5`.
- Next slice: managed baseline classification and transaction-owned ignored
  managed paths.

## 2026-08-03 — SYN-036 task 8 complete

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Completed work: Repair joining now requires an immutable snapshot with the
  expected Synesis snapshot ref and `REPAIR_REQUIRED` completion state. The
  authenticated target lane is materialized from the current control HEAD and
  the original immutable conflict, while dirty target content fails closed.
  `ProjectAppendLock` covers current-head capture, Git materialization, and the
  signed source-release/target-announcement event, so the reserved scope has no
  unowned interval. Repair payloads carry snapshot, control-head, and source/
  target epoch metadata; replay validates the same lineage, and repeated joins
  are idempotent.
- Verification: focused coordination/workspace repair and integration-queue
  tests pass; full `:coordination:test` passes; strict
  `:coordination:javadoc :workspace:javadoc` passes; `git diff --check` passes.
  A broad `:workspace:test` run previously timed out without a completed
  failure report and remains recorded as a runner limitation, not product
  evidence.
- Remaining work: SYN-036 task 9 prerelease migration and legacy cleanup, then
  task 10 real Codex plus Antigravity acceptance. Do not claim provider
  autonomy beyond recorded MCP evidence.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`
