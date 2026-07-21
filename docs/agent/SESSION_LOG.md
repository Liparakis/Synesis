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
