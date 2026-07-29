# Tasks

Allowed statuses: `BLOCKED`, `READY`, `ACTIVE`, `VERIFYING`, `DONE`, `DEFERRED`.

## SL-SETUP-001

- ID: SL-SETUP-001
- Priority: P0
- Title: Install durable agent-memory structure
- Status: DONE
- Purpose: Install resumable execution state.
- Dependencies: none
- Acceptance criteria: Required files, startup, scripts, fixtures, resume, checkpoint, doctor, and documentation agreement.
- Required tests: persistence validator, resume, doctor, checkpoint.
- Required documentation: root and `docs/agent/`.
- Evidence: `docs/agent/checkpoints/CP-0001.md` through `CP-0004.md`; `STATE.md`.

## SL-SETUP-002

- ID: SL-SETUP-002
- Priority: P0
- Title: Install complete Synesis Link v1 contract
- Status: DONE
- Purpose: Replace the placeholder with the complete implementation contract.
- Dependencies: SL-SETUP-001
- Acceptance criteria: Contract revision 1 ACTIVE, durable files reconciled, architecture and product task graph created.
- Required tests: Resume and checkpoint validation; contract completeness review.
- Required documentation: contract, goal, state, task graph, architecture ADRs.
- Evidence: `CONTRACT.md` revision 1 ACTIVE; `docs/adr/0001-modular-monolith-and-boundaries.md`; `docs/adr/0002-quic-implementation.md`; CP-0005.

## SL-001

- ID: SL-001
- Priority: P0
- Title: Contract, architecture, and build
- Status: DONE
- Purpose: Create the smallest buildable Java 25 Gradle project with strict verification and a first passing test.
- Dependencies: SL-SETUP-001, SL-SETUP-002
- Acceptance criteria: Gradle project, wrapper, Java 25 toolchain, strict compiler/Javadoc/test configuration, dependency verification baseline, and first passing test.
- Required tests: first unit test, compile, test, Javadoc, dependency verification.
- Required documentation: README, build verification notes, package-info for every Java package.
- Evidence: PASS — `gradlew.bat clean check --dependency-verification=strict`; Gradle Wrapper; `SynesisLinkTest`.

## SL-002

- ID: SL-002
- Priority: P0
- Title: Node identity
- Status: DONE
- Purpose: Generate, load, store, sign, verify, and derive stable node IDs.
- Dependencies: SL-001
- Acceptance criteria: Contract identity requirements and mandatory identity tests pass.
- Required tests: identity generation, persistence, signing, verification, safe logging.
- Required documentation: identity ADR and API Javadocs.
- Evidence: PASS — `NodeIdentityTest`; `docs/adr/0003-ed25519-node-identity.md`; `gradlew.bat clean check --dependency-verification=strict`.

## SL-003

- ID: SL-003
- Priority: P0
- Title: Candidate descriptors
- Status: DONE
- Purpose: Model, canonicalize, sign, verify, expire, rank, and limit direct-connectivity candidates.
- Dependencies: SL-002
- Acceptance criteria: Descriptor and golden-vector requirements pass.
- Required tests: canonical equivalence, tamper, expiry, normalization, ranking, provider bounds.
- Required documentation: wire format and test vectors.
- Evidence: PASS — `CandidateDescriptorTest`; `docs/protocol/WIRE_FORMAT.md`; `gradlew.bat clean check --dependency-verification=strict`.

## SL-004

- ID: SL-004
- Priority: P0
- Title: First real local QUIC connection
- Status: DONE
- Purpose: Prove two local processes can establish and close a bounded QUIC connection.
- Dependencies: SL-001, SL-003
- Acceptance criteria: Internal adapter, listener, connector, ALPN, deterministic shutdown, and real local integration.
- Required tests: two-process local QUIC connection.
- Required documentation: transport ADR update and platform notes.
- Evidence: PASS — `NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses`; `gradlew.bat clean check --dependency-verification=strict`; ADR-0002.

## SL-005

- ID: SL-005
- Priority: P0
- Title: Identity binding and protocol negotiation
- Status: DONE
- Purpose: Authenticate the expected node and establish PeerSession only after negotiation.
- Dependencies: SL-002, SL-004
- Acceptance criteria: replay, substitution, downgrade, and incompatibility behavior is deterministic.
- Required tests: handshake and version tests.
- Required documentation: protocol and security updates.
- Evidence: PASS — repeated `NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses`; transported version offer/selection; `NettyQuicLoopbackTest.rejectsWrongIdentityAndIncompatibleVersionBeforeSessionExposure`; `SessionAuthenticatorTest`; strict clean check.

## SL-006

- ID: SL-006
- Priority: P0
- Title: Control path and graceful close
- Status: DONE
- Purpose: Add bounded control framing, goodbye, close reasons, and isolated progress.
- Dependencies: SL-005
- Acceptance criteria: control path remains live during data traffic and closes safely.
- Required tests: framing, limits, goodbye, malformed input.
- Required documentation: wire format and state-machine updates.
- Evidence: PASS — `ControlFrameTest`; local control-ready, duplicate-stream, large-stream isolation, and graceful-close integration; repeated process graceful close; `gradlew.bat clean check --dependency-verification=strict`.

## SL-007

- ID: SL-007
- Priority: P0
- Title: Heartbeat and liveness
- Status: DONE
- Purpose: Implement LIVE, SUSPECT, EXPIRED, recovery, cancellation, and exactly-once transitions.
- Dependencies: SL-006
- Acceptance criteria: bounded loss detection without false instant-disconnect claims.
- Required tests: deterministic liveness and fault tests.
- Required documentation: timing and liveness bounds.
- Evidence: PASS — `HeartbeatMessageTest`, `LivenessTrackerTest`, local QUIC heartbeat exchange, two-process healthy heartbeat exchange, strict clean verification, fixture validation, and doctor.

## SL-008

- ID: SL-008
- Priority: P1
- Title: Candidate providers and racing
- Status: DONE
- Purpose: Add justified providers, bounded racing, cancellation, cleanup, and diagnostics.
- Dependencies: SL-003, SL-004
- Acceptance criteria: failed providers do not block successful candidates; unsafe/duplicate candidates are normalized; compatible pairs rank deterministically; races are bounded and cancellable; only an authenticated control-ready expected-identity session wins; losers are cleaned up.
- Required tests: provider timeout/cancellation/race tests; local and two-process QUIC candidate-pair integration.
- Required documentation: candidate provider limits, protocol boundary, threat model, ADR-0006, and operations limitations.
- Evidence: PASS — `CandidateNormalizationTest`, `CandidateGathererTest`, `CandidateRacerTest`, local and two-process QUIC harnesses selecting through bounded candidate pairs, and `gradlew.bat clean check --dependency-verification=strict`.

## SL-009

- ID: SL-009
- Priority: P0
- Title: Reconnect and path behavior
- Status: DEFERRED
- Purpose: Create new authenticated sessions, epochs, stale rejection, and path-change reporting.
- Dependencies: SL-007, SL-008
- Acceptance criteria: old sessions and streams cannot affect a new session.
- Required tests: reconnect, migration, rebinding where supported.
- Required documentation: state-machine and operations updates.
- Evidence: Deferred after CP-0030; reconnect/path behavior is intentionally postponed until after the first physical cooperation demonstration. See `docs/agent/DEFERRED.md` entry SL-D-036 and `docs/operations/NETWORK_VALIDATION_MATRIX.md`.

## SL-ARCH-001

- ID: SL-ARCH-001
- Priority: P0
- Title: Move Link into the Synesis root module layout
- Status: DONE
- Purpose: Make the repository root Synesis and place the existing Link transport/session implementation in the `link/` Gradle subproject without inventing wider Synesis functionality.
- Dependencies: SL-001 through SL-DEMO-001 automated readiness
- Acceptance criteria: root `clean check` delegates to `:link:check`; Link source, tests, module build, and module lockfile live under `link/`; root docs/scripts remain runnable; strict dependency verification passes; no placeholder sibling modules are added.
- Required tests: root strict clean check, root resume/doctor/fixture/deferred validators, and Link CLI help.
- Required documentation: ADR-0008, architecture baseline, contract, goal, state, current task, next session, README, and command-path updates.
- Evidence: PASS — root `projects` discovery, root `clean check --dependency-verification=strict`, `:link:demoCli --args=--help`, resume, doctor, fixture validator, and deferred validator after migration; ADR-0008.

## SL-DEMO-001

- ID: SL-DEMO-001
- Priority: P0
- Title: First physical cooperation demonstration readiness
- Status: VERIFYING
- Purpose: Add only the bounded demo application request/result path, reproducible safe CLI operation, durable deferral enforcement, and physical-validation evidence capture required to demonstrate authenticated cooperative behavior.
- Dependencies: SL-007, SL-008
- Acceptance criteria: deferred register is validated; one bounded authenticated `synesis-demo-work/1` request/result succeeds locally and in two processes; safe CLI/demo instructions exist; physical two-machine evidence is either recorded as `TWO_MACHINE_VERIFIED` or the task remains blocked without overstated claims; no deferred networking or higher-level Synesis semantics are implemented.
- Required tests: demo codec bounds/correlation, pre-auth/pre-ready rejection, local QUIC request/result, two-process request/result, wrong-identity rejection, cleanup, and durable-register fixtures.
- Required documentation: `DEFERRED.md`, `DEMO_GAP_ANALYSIS.md`, `FIRST_DEMO.md`, operations/security updates, release-readiness notes, and dependency-verification metadata for main and test classpaths.
- Evidence: automated PASS — deferred validator, `DemoWorkProtocolTest`, `DemoWorkBindingTest`, local/two-process request/result, `DemoCliTest`, CLI help, and strict clean check. Physical Scenario A normal operation is `TWO_MACHINE_VERIFIED` in `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`; abrupt-loss and wrong-identity physical validation remain pending.

## SL-012

- ID: SL-012
- Priority: P0
- Title: Zero-configuration terminal onboarding
- Status: VERIFYING
- Purpose: Add automatic local identity bootstrap and signed single-use terminal invitations above the existing Link transport while preserving the diagnostic `DemoCli` path.
- Dependencies: SL-005, SL-006, SL-007, SL-008
- Acceptance criteria: `host` binds before invitation creation; `join <link>` verifies a bounded signed invitation; identity creation/reuse is automatic; capability admission is single-use with bounded reservation; existing mutual identity binding remains mandatory; control readiness, liveness, demo work, and graceful close complete through the existing path; QR and link encode the same invitation; no physical two-machine claim is made until executed.
- Required tests: identity bootstrap/reuse/corruption, invitation canonical encoding/signature/tamper/expiry/size/version/missing fields, reservation timeout/replay/concurrency, expected-peer mismatch, QR input identity, two-profile two-process onboarding, and all existing Link/DemoCli tests.
- Required documentation: onboarding protocol/wire format, threat model, CLI operations, ADR for invitation/transcript changes, test matrix, state, and checkpoint evidence.
- Evidence: implementation compiled; focused invitation/bootstrap/admission/QR
  tests PASS; strict full verification and two-profile two-process onboarding
  PASS, including the cleanup narrow-terminal rerun and unsupported-output
  charset QR skip test. Invitation bytes,
  handshake semantics, identity behavior, admission/replay semantics, and
  physical claim boundaries are unchanged. Physical two-machine onboarding is
  not claimed.

## SL-013

- ID: SL-013
- Priority: P0
- Title: Standalone Synesis CLI and development distribution
- Status: DONE
- Purpose: Move terminal command ownership and Gradle development distribution
  into an outer `cli` module while Link retains onboarding/network orchestration
  behind one minimal public façade.
- Dependencies: SL-012 implementation and documented onboarding validation.
- Acceptance criteria: `:cli` owns Picocli, command adapters, terminal output,
  exit mapping, and Gradle Application distributions; `link` has no Picocli or
  CLI dependency; Link exposes only the typed onboarding façade; host, join,
  identity show, and doctor work through generated launchers with stable labels
  and numeric exits; QR rendering remains byte-identical to the exact
  invitation link; strict verification and isolated launcher onboarding pass;
  physical launcher onboarding remains explicitly unclaimed and is outside
  this frozen development-distribution baseline.
- Required tests: CLI parsing, command adapters, read-only readiness inspection,
  generated launcher smoke tests, local generated host/join, and existing Link
  protocol tests. Physical generated-launcher evidence is explicitly unclaimed
  by the frozen baseline.
- Required documentation: ADR-0010, distribution and physical CLI evidence,
  README/demo/operations command updates, and durable state reconciliation.
- Evidence: `:cli:installDist`, strict root check, generated launcher smoke,
  generated two-profile onboarding, façade tests, parsing tests, doctor tests,
  and `:link:dependencies` Picocli boundary PASS. Frozen at CP-0054; physical
  launcher evidence remains unclaimed and is not a completion claim.

## SL-014

- ID: SL-014
- Priority: P0
- Title: Bounded authenticated Link application-stream seam
- Status: DONE
- Purpose: Expose one transport-neutral, bounded application-stream binding
  above Link so a future higher-level module can exchange bytes only after an
  authenticated control-ready session exists.
- Dependencies: SL-013 frozen completion; ADR-0011 approval; ADR-0012.
- Acceptance criteria: the Link API exposes authenticated remote identity and
  readiness; pre-ready, over-limit, terminal-session, and cleanup behavior is
  deterministic; two isolated processes exchange bounded bytes over the
  authenticated stream; Link retains framing, limits, deadlines, liveness, and
  cleanup ownership; no project/record/sync vocabulary or `:cli` dependency is
  introduced.
- Required tests: pre-ready rejection, frame-size/bounds rejection, terminal
  session rejection, stream cleanup on success/failure/cancellation, and
  two-profile byte exchange with remote identity assertions.
- Required documentation: ADR-0012, protocol/state/security boundary notes,
  test matrix, evidence, and checkpoint state.
- Evidence: `docs/evidence/APPLICATION-STREAM-SEAM-2026-07-21.md`; focused
  seam tests and `gradlew.bat clean check --dependency-verification=strict`
  PASS.

## SL-015

- ID: SL-015
- Priority: P0
- Title: Review SYN-001 activation after Link seam verification
- Status: DONE
- Purpose: Review the verified SL-014 boundary and decide whether the blocked
  higher-level record task may be explicitly promoted.
- Dependencies: SL-014 DONE; SL-015 review gate completed; CP-R2 active.
- Acceptance criteria: SL-014 evidence and ADR-0012 were reviewed; the user
  explicitly approved promotion of SYN-001; no record storage, sync, project
  terminology, or `:cli` change occurred in this gate.
- Required tests: resume, fixture, deferred-register, and doctor validators.
- Required documentation: review decision, task-state reconciliation, and a
  checkpoint recording the choice.
- Evidence: user approval recorded in `SESSION_LOG.md`; CP-R2 is the active
  SYN-001 implementation checkpoint.

## SL-010

- ID: SL-010
- Priority: P0
- Title: Hardening
- Status: READY
- Purpose: Complete limits, malformed-input handling, fault injection, leak checks, and threat reconciliation.
- Dependencies: SL-009
- Acceptance criteria: mandatory security, resource, and lifecycle tests pass.
- Required tests: fuzz/property, saturation, repeated-cycle, leak tests.
- Required documentation: threat model reconciled with implementation.
- Evidence: pending.

## SYN-010A

- ID: SYN-010A
- Priority: P0
- Title: Public GitHub developer-preview preparation
- Status: VERIFYING
- Purpose: Audit and prepare the existing repository for safe public visibility without redesigning the product, publishing release assets, or selecting a license autonomously.
- Dependencies: SYN-009C DONE at CP-0110; explicit user-supplied SYN-010A goal.
- Acceptance criteria: complete current/history secret scan, safe ignore rules, accurate preview README, SECURITY.md and CONTRIBUTING.md, workflow security audit, repository metadata review, full verification, and a clean preparation commit; public publication only after an intentional license decision and explicit external gates.
- Required tests: secret scanner or documented equivalent, focused history/path searches, strict Java build, Go test/vet, repository validators, workflow syntax/security review, and clean-tree confirmation.
- Required documentation: `docs/agent/SYN_010A_PUBLICATION_AUDIT.md`, `docs/legal/LICENSE_DECISION_REQUIRED.md`, README/public-preview docs, and durable state updates.
- Scope boundary: no new product features, protocol changes, release assets, production release, license selection, history rewrite, public push, or external announcement.
- Evidence: `docs/agent/SYN_010A_PUBLICATION_AUDIT.md`; `docs/legal/LICENSE_DECISION_REQUIRED.md`; README, SECURITY.md, CONTRIBUTING.md, and `.gitignore` preparation; validators PASS. Owner selected AGPL-3.0-only and the complete `LICENSE` is present. `gradlew` mode `100755` fixes the reported Linux CI permission failure; Linux/macOS Netty QUIC checksum metadata, Unix launcher test selection, OS-native Claude path fixtures, platform-aware QUIC TLS setup, bundle smoke executable-mode handling for both launcher and bundled Java, Unix process-test argument handling, the CI-stable abrupt process-loss test policy, Node 24-compatible workflow action versions with Microsoft OpenJDK 25 for Windows ARM64, and quoted Windows Gradle version arguments are repaired; native strict and targeted checks pass. Publication remains unperformed pending explicit push authorization, author-metadata review, and target confirmation.

## SYN-010B

- ID: SYN-010B
- Priority: P0
- Title: Simplify CI artifact output into one release candidate
- Status: VERIFYING
- Purpose: Expose one aggregated `synesis-release-candidate` Actions artifact for ordinary workflow runs while preserving all six platform bootstrappers, six Java bundles, native smoke validation, manifest/signature/checksum verification, and future platform-specific release assets.
- Dependencies: SYN-009C DONE at CP-0110; explicit user-supplied SYN-010B goal; SYN-010A VERIFYING.
- Acceptance criteria: internal matrix artifacts are clearly named and short-lived; aggregation downloads all required inputs into a clean deterministic layout; exactly one final Actions artifact is uploaded; required files, versions, platforms, checksums, signatures, archive safety, duplicates, and unexpected files are rejected; install scripts and concise README are included; no release is published.
- Required tests: local aggregation success and rejection cases for missing files, duplicates, checksum/signature/version/layout/archive-safety failures; Java strict build; Go test/vet; six cross-compiles; native bundle smoke; workflow YAML validation; clean-tree confirmation.
- Required documentation: aggregation implementation note, `docs/release/cross-platform-release.md`, `docs/release/artifact-matrix.md`, `docs/installation/bootstrap-install.md`, `docs/development/current-state.md`, and durable state updates.
- Scope boundary: no Java application, provider, synchronization, protocol, Go bootstrap behavior, installation-directory, signing-key, release-tag, GitHub Release, domain, or marketing changes.
- Evidence: pending; start from repository CP-0123 rather than the stale pasted CP-0110 reference.

## SYN-011

- ID: SYN-011
- Priority: P0
- Title: Antigravity real hook enforcement investigation and health gate
- Status: VERIFYING
- Purpose: Investigate the failed real Antigravity protected-edit run and make the smallest evidence-backed correction required for honest provider health reporting and verified hook enforcement.
- Dependencies: SYN-008 DONE; SYN-009B DONE; failed real Antigravity evidence supplied by the user; real Antigravity CLI 1.0.16 available locally.
- Acceptance criteria: preserve pre-change evidence; capture the real tool name and sanitized payload shape; verify the generated command and exact matcher; run the manual payload through the exact configured command with stdout, stderr, and exit code; inspect available Antigravity diagnostics; restore the protected file; prevent Antigravity `HEALTHY` from synthetic checks alone; add a regression fixture for the verified real shape; rerun direct, manual, provider, real protected-edit, hash, unrelated-edit, and real-replan checks; remain DEGRADED or UNVALIDATED unless the real protected edit is denied and replanning succeeds.
- Required tests: focused Antigravity adapter/provider tests, generated hook process test, and real Antigravity CLI protected-edit/replan evidence where the local installation supports it.
- Required documentation: `docs/validation/antigravity-real-agent-experiment.md`, `docs/agent/STATE.md`, `docs/agent/CURRENT.md`, `docs/agent/FAILED_ATTEMPTS.md`, `docs/agent/TEST_MATRIX.md`, and a checkpoint.
- Scope boundary: no new architecture, no unrelated provider or protocol work, no remote publication, no prompt/content/credential capture, and no compatibility claim beyond the verified Antigravity contract.
- Evidence: `docs/evidence/antigravity-real-investigation-2026-07-22/report.md`;
  real protected enforcement remains unresolved and the provider is
  DEGRADED/UNVALIDATED.

## SYN-009D

- ID: SYN-009D
- Priority: P0
- Title: Replace versioned installation with a stable flat layout
- Status: DONE
- Purpose: Make one OS-conventional `Synesis` root the only persistent
  application installation, with sibling staging and temporary rollback during
  signed install/update activation.
- Dependencies: SYN-009C DONE at CP-0110; SYN-009D activation supplied
  by the user; SYN-011 evidence preserved and moved to VERIFYING.
- Acceptance criteria: Windows/Linux/macOS stable roots are resolved; bundles
  install directly under the stable root; signed manifest and SHA-256 checks,
  safe extraction, validation, atomic activation, rollback, legacy migration,
  user PATH ownership, doctor, uninstall, and project preservation are
  verified; no persistent `versions/`, `current`, versioned launcher, or
  provider command path remains in normal source/docs; provider launchers use
  PATH or the stable fallback only.
- Required tests: fresh install/update/rollback, legacy migration/rollback,
  stable launcher/version/doctor, uninstall/project preservation, staging and
  rollback cleanup, Windows user PATH idempotence/removal preservation,
  provider stable-launcher resolution, archive/signature/security regressions,
  and native Windows smoke where available.
- Required documentation: ADR-0026, installation/bootstrap/update,
  provider-management, release, doctor, capability matrix, and durable state.
- Scope boundary: no new product behavior, protocol changes, remote
  publication, release signing-key changes, or deletion of external project
  data.
- Evidence: implementation and verification completed at CP-0131: Go tests/vet,
  Windows/Linux/macOS cross-builds, strict Java verification, disposable
  migration/rollback tests, native Windows archive smoke, and local Windows
  migration all pass; stable-root doctor and PATH resolution were inspected.

## SYN-012

- ID: SYN-012
- Priority: P0
- Title: Public speculative capability coordination CLI
- Status: DONE
- Purpose: Expose the bounded coordination MVP through the public `synesis`
  CLI for an external initialized project, including semantic ownership, live
  ordered events, capability prediction, logical actor authorization, isolated
  speculation, and auditable retirement.
- Dependencies: SYN-009D DONE; SYN-011 remains VERIFYING; explicit user
  activation of the historical CAF slice; existing Link identity, SDR2 records,
  ScopeMatcher, provider hooks, and unified CLI.
- Acceptance criteria: required public command trees and help are present; two
  independent CLI profiles coordinate through a deterministic loopback
  coordinator against an external project; semantic ownership conflict
  detection, precise prediction contracts, logical requester/owner
  authorization, live ordered delivery with replay, implementation publication,
  isolated speculation, requester validation, and clean retirement/invalidation
  are proven by a real two-process evidence run.
- Required tests: domain/state transitions, signed bounded protocol, durable
  event replay, reconnect, duplicate delivery, stale supervisors, provider
  outcomes, worktree isolation, speculative overlay and merge gate, and the
  full two-agent acceptance demonstration.
- Required documentation: speculative architecture, supervisor protocol,
  state machines, MVP plan, ADR-0027, ADR-0028, CLI/provider/Git documentation, roadmap,
  capability matrix, evidence report, and durable state/checkpoint updates.
- Scope boundary: no global AI agent, arbitrary remote execution, shared
  working tree, federation, GUI, Obsidian authority, or production claims for
  providers and physical remote machines without evidence.
- Implementation note: production code is authorized only within this task;
  the existing Link transport/session behavior remains unchanged.
- Evidence: `docs/evidence/speculative-coordination-real-cli-2026-07-23/report.md`
  and `scripts/run-speculative-coordination-real.ps1`; an external project,
  coordinator, requester, owner, and supervisor CLI process passed the flow.

## SYN-013

- ID: SYN-013
- Priority: P0
- Title: Zero-touch autonomous harness collaboration plan
- Status: DONE
- Purpose: Produce the implementation-ready architecture and staged delivery
  plan for ambient Codex/Antigravity collaboration after one `synesis init`.
- Dependencies: SYN-012 DONE at CP-0144; SYN-009B.1 and SYN-011 remain
  VERIFYING; existing provider evidence and failed real-hook investigations.
- Acceptance criteria: architecture, bootstrap protocol, workspace broker,
  lifecycle, AGENTS.md contract, MVP plan, ADR, provider maturity, migration,
  security/failure model, file map, acceptance script, and durable state are
  reconciled; the plan explicitly gates release on real provider workspace
  transition and mutation-interception evidence.
- Required tests: documentation validators, deferred-register validation,
  strict repository check, and plan consistency review. No production code is
  permitted in this planning task.
- Required documentation: the seven SYN-013 architecture/plan documents and
  ADR-0029.
- Scope boundary: no hidden runtime, provider behavior, worktree broker, or
  autonomous coordination implementation in this task.
- Evidence: CP-0150 planning validation; `docs/architecture/agents-md-contract.md` and
  `docs/plans/zero-touch-collaboration-mvp.md`.

## SYN-013A

- ID: SYN-013A
- Priority: P0
- Title: Bootstrap the project AGENTS.md contract during init
- Status: DONE
- Purpose: Implement the already-approved AGENTS.md bootstrap contract without
  adding provider automation or changing coordinator behavior.
- Dependencies: SYN-013 plan package and ADR-0029.
- Acceptance criteria: fresh init creates root AGENTS.md with the marked
  Synesis section; repeated init is idempotent; an existing file preserves all
  unrelated text; an existing managed section is replaced deterministically;
  malformed marker usage fails closed without overwriting user content.
- Required tests: focused ProjectApplicationService tests plus the affected
  workspace/CLI verification.
- Scope boundary: only AGENTS.md bootstrap/update behavior and its tests;
  no provider hooks, hidden runtime, worktree broker, or autonomous
  coordination behavior.
- Evidence: PASS — `ProjectApplicationServiceTest`, `:workspace:test`, and the
  bundled CLI smoke recorded in `CURRENT.md`; malformed markers fail closed.

## SYN-013B

- ID: SYN-013B
- Priority: P0
- Title: Implement provider session binding and fail-closed workspace routing
- Status: DONE
- Purpose: Bind Codex and Antigravity provider actions to distinct,
  project-scoped durable session, supervisor, and worker identities without
  manual ceremony, while preventing mutations in the control checkout.
- Dependencies: SYN-013A DONE; SYN-013 plan/ADR-0029; project-local identity,
  provider adapters, and loopback coordination protocol.
- Acceptance criteria: project/node identity remains stable; explicit provider
  instance evidence resumes idempotently; independent provider/session keys
  receive distinct actors; a valid Git project receives a distinct durable
  session worktree outside the control checkout; unborn repositories follow a
  documented init policy; hooks fail closed on missing interception, missing
  workspace transition, control-checkout cwd, or binding mismatch; provider
  status reports binding/workspace/interception state; legacy projects migrate
  without reinitialization; actor/authority spoofing remains rejected.
- Required tests: binding persistence/idempotency, provider/project/node
  mismatch, stale/revoked/partial records, valid and unborn Git HEAD policy,
  worktree allocation and resume, registration/common-dir/branch invariants,
  control-checkout denial, Codex and Antigravity hook bootstrap, provider
  lifecycle/status, migration, installed bundle, and full repository
  verification.
- Scope boundary: no claim of real provider readiness until an actual provider
  hook invocation proves interception and workspace routing; no remote
  coordination, and no provider credential/conversation persistence.
- Evidence: DONE at CP-0157. WorkspaceMutationBroker enforcing all 5 workspace mutation invariants; 42-task root check passes. SYN-013C sub-slice completed at CP-0165 (commit `198f3e9`).

## SYN-013C

- ID: SYN-013C
- Priority: P0
- Title: Stage 1 — Simplify agent-facing responses and AGENTS.md contract
- Status: DONE
- Purpose: Establish a safe, minimal agent response envelope, a central
  internal-to-agent outcome translator, and a concise AGENTS.md behavioral
  contract. No MCP server.
- Dependencies: SYN-013B DONE
- Acceptance criteria: `AgentResponse` envelope emits only safe public fields;
  `AgentOutcomeTranslator` maps all 10 internal Decision outcomes plus
  exceptions without leaking IDs, hashes, or raw exception strings; CLI
  `--output agent` emits concise JSON; AGENTS.md managed section uses the
  canonical 4-bullet text; all `:workspace:check` tests pass; root `check`
  (42 tasks) passes.
- Required tests: `AgentResponseTest`, `AgentOutcomeTranslatorTest`, updated
  `ProjectApplicationServiceTest` and `WorkspaceMutationBrokerTest`.
- Required documentation: updated STATE.md, CURRENT.md, NEXT_SESSION.md.
- Evidence: DONE at CP-0165. Commit `198f3e9` on master. All 65
  `:workspace:check` tests pass. Root `check` (42 tasks) passes.

## SYN-013D

- ID: SYN-013D
- Priority: P0
- Title: Stage 2A — Minimal stdio MCP server (5 safe workspace tools) & Stage 2B Collaboration Loop
- Status: DONE
- Purpose: Introduce stdio MCP server, 10 registered MCP tools, and multi-agent collaboration loop.
- Dependencies: SYN-013C DONE
- Acceptance criteria: `:mcp` subproject compiles and passes `check`; autonomous collaboration loop verified across Codex and Antigravity; all 10 registered MCP tools functional.
- Evidence: DONE at CP-0186.

## SYN-014A

- ID: SYN-014A
- Priority: P0
- Title: Post-MVP Hardening Slice 1: Read-only lifecycle inventory and cleanup dry-run
- Status: DONE
- Purpose: Implement read-only lifecycle resource discovery, path safety verification, retention classification, cleanup eligibility evaluation, cleanup plan generation, and `synesis cleanup --dry-run` CLI command.
- Dependencies: SYN-013D DONE at CP-0186.
- Acceptance criteria: read-only inventory discovery, path verifier, retention policy, cleanup plan model, `synesis cleanup --dry-run` command, zero runtime mutations, passing unit/integration tests and clean repository check.
- Required tests: `:workspace:check`, `:cli:check`, `:mcp:check`, full repository check `.\gradlew.bat check --no-daemon`.
- Scope boundary: Read-only at runtime. Zero deletion, zero process termination, zero Git mutations.
- Evidence: `.\gradlew.bat check --no-daemon` BUILD SUCCESSFUL; FullMutationSafetyTest PASS; CleanupEligibilityServiceTest PASS; CleanupCommandTest PASS; ProcessInspectorTest PASS; LifecyclePathVerifierTest PASS.

## SYN-014B

- ID: SYN-014B
- Priority: P0
- Title: Post-MVP Hardening Slice 2: Controlled lifecycle cleanup execution
- Status: DONE
- Purpose: Implement immutable plan persistence, staleness verification, project execution lock, execution journal, safe worktree removal, temporary file deletion, orphan quarantine, and CLI command options (`--prepare`, `--show-plan`, `--execute`).
- Dependencies: SYN-014A DONE at CP-0187.
- Acceptance criteria: immutable plan store, project execution lock, execution journal, safe worktree removal via git worktree remove without --force, exact file deletion, quarantine with atomic move, `synesis cleanup --prepare / --show-plan / --execute`, unit/integration/mutation tests passing, full `.\gradlew.bat check --no-daemon` passing.
- Scope boundary: No force worktree deletion, no recursive force deletion, no process termination, no Git worktree prune, no MCP tool changes.
- Evidence: `.\gradlew.bat check --no-daemon` BUILD SUCCESSFUL; PlanPersistenceTest PASS; StalePlanTest PASS; WorkerCleanupTest PASS; ValidationAndIntegrationCleanupTest PASS; TemporaryFileCleanupTest PASS; QuarantineTest PASS; LockAndConcurrencyTest PASS; JournalAndRestartTest PASS; NoForceAndSafetyArchitectureTest PASS; Slice2MutationBoundaryTest PASS.

- [x] **SYN-014C** — Post-MVP Hardening Slice 3: Crash reconciliation and task cancellation `[DONE]`
- [x] **SYN-014D** — Post-MVP Hardening Slice 4: Doctor diagnostics and safe administrative repair `[DONE]`
- [x] **SYN-015** — Reorganize Synesis package structure `[DONE]`
- [x] **SYN-016** — Organize coordination domain packages `[DONE]`
- [x] **SYN-017** — Organize workspace application packages `[DONE]`
- [x] **STRUCT-1A** — Foundational packages `[DONE]`
- [x] **STRUCT-1B** — Workspace packages `[DONE]`
- [x] **STRUCT-1C** — MCP packages `[DONE]`
- [x] **STRUCT-1D** — CLI packages `[DONE]`
- [x] **QUALITY-DEDUP** — Evidence-based deduplication `[DONE]`
- [x] **QUALITY-WARNINGS** — Legitimate warning cleanup `[DONE]`
- [x] **QUALITY-GOD** — Focused god-class splitting `[DONE]`
- [/] **SYN-014E** — Post-MVP Hardening Slice 5: Versioned installation, atomic activation, and migration `[READY]`

## SYN-015

- ID: SYN-015
- Priority: P0
- Title: Reorganize Synesis package structure
- Status: DONE
- Purpose: Improve package organization and dependency direction in staged, independently validated slices without changing runtime behavior or public surfaces.
- Dependencies: clean working tree; existing module boundaries preserved.
- Acceptance criteria: staged structure, evidence-based deduplication, legitimate warning cleanup, and focused god-class splitting complete with clean validation, zero stale production references, preserved surfaces, and checkpoint evidence.
- Required tests: per-subtask Gradle verification, root `check`, bootstrap Go test/vet, MCP 11-tool verification where required, and stale-reference searches.
- Scope boundary: no module moves, no new Gradle dependencies, no Go bootstrap edits, no CLI/MCP surface changes, and no provider/schema/reason-code/event-format changes.
- Evidence: `STRUCT-1A` completed at commit `376f2d2ce6003b32d28994b19b6728926ab0af6e`; `STRUCT-1B` completed across `b67ac1c` and corrective commit `248889a`; `STRUCT-1C` at `5cb0656`; `STRUCT-1D` at `958a039`; deduplication at `98755b3`; warning cleanup at `98cda05`; god-class split at `04977b9`; final checkpoint `CP-0221`.

### STRUCT-1A

- ID: STRUCT-1A
- Priority: P0
- Title: Foundational packages
- Status: DONE
- Purpose: Reorganize foundational packages inside `:project-record`, `:coordination`, and `:link` only.
- Dependencies: SYN-015 ACTIVE; clean working tree; durable state reconciled before production edits.
- Acceptance criteria: `project-record` split into `domain`, `persistence`, `security`, `sync`, `sync.protocol`, and `guardrail`; `coordination` split into `domain`, `application`, `persistence`, and `transport.http`; `link` split into `transport.quic`, `transport.control`, `onboarding`, and `cli`; `DemoCli` moved to `org.synesis.link.cli`; Gradle main-class string updated; zero stale production references to moved foundational-package FQNs remain.
- Required tests: `.\gradlew.bat :project-record:check --no-daemon`; `.\gradlew.bat :coordination:check --no-daemon`; `.\gradlew.bat :link:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `bootstrap\go test -count=1 ./...`; `bootstrap\go vet ./...`.
- Scope boundary: no cross-module type moves, no new abstractions except minimal access-preservation changes forced by package movement, no behavioral rewrites.
- Evidence: PASS — `.\gradlew.bat :project-record:check --no-daemon`; `.\gradlew.bat :coordination:check --no-daemon`; `.\gradlew.bat :link:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `go test -count=1 ./...`; `go vet ./...`; commit `376f2d2ce6003b32d28994b19b6728926ab0af6e`.

### STRUCT-1B

- ID: STRUCT-1B
- Priority: P0
- Title: Workspace packages
- Status: DONE
- Purpose: Reorganize the `:workspace` module into application, provider, lifecycle, project, and responsibility-specific infrastructure packages.
- Dependencies: STRUCT-1A DONE; clean working tree; durable state activated before production edits.
- Acceptance criteria: approved workspace package map applied; provider-specific adapters moved under provider packages; no `workspace.infrastructure.workspace`; only the proven package cycles are resolved; any new interface/value type remains within the allowed narrow exceptions; architecture tests enforce the new dependency direction.
- Required tests: `.\gradlew.bat :workspace:check --no-daemon`; `.\gradlew.bat :cli:check --no-daemon`; `.\gradlew.bat :mcp:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `bootstrap\go test -count=1 ./...`; `bootstrap\go vet ./...`.
- Scope boundary: no CLI/MCP/package work outside what workspace references require to compile and verify; no behavior changes.
- Evidence: PASS — preflight clean at `95f696cc6442b426b28d7e2f2d7b7dd54a43b541`; `:workspace:check`; `:coordination:check`; `:cli:check`; `:mcp:check`; root `check`; `go test -count=1 ./...`; `go vet ./...`; focused `McpTool11Test`; stale FQN scan; acyclic production package graph; commits `b67ac1c` and `248889a`.

### STRUCT-1C

- ID: STRUCT-1C
- Priority: P0
- Title: MCP packages
- Status: DONE
- Purpose: Separate MCP protocol, stdio transport, and application routing packages while preserving the stable server entrypoint where practical.
- Dependencies: STRUCT-1B DONE.
- Acceptance criteria: `org.synesis.mcp.protocol`, `org.synesis.mcp.transport.stdio`, and `org.synesis.mcp.application` implemented; only necessary main-class and process-test references updated; a real MCP wire sequence completes `initialize`, `tools/list`, and exact 11-tool confirmation.
- Required tests: `.\gradlew.bat :mcp:check --no-daemon`; `.\gradlew.bat :cli:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `bootstrap\go test -count=1 ./...`; `bootstrap\go vet ./...`.
- Scope boundary: no MCP schema/tool-count changes and no unrelated CLI/package cleanup.
- Evidence: PASS — `:mcp:check`; focused persistent `McpServerTest` and `McpTool11Test`; `:cli:check`; root `check`; stale production FQN scan; commit `5cb0656`.

### STRUCT-1D

- ID: STRUCT-1D
- Priority: P0
- Title: CLI packages
- Status: DONE
- Purpose: Split the flat CLI command package by command family and align tests to production package ownership.
- Dependencies: STRUCT-1C DONE.
- Acceptance criteria: command-family packages applied; `SynesisCli` remains in `org.synesis.cli`; CLI tests mirror the production package they exercise; CLI help and provider commands remain unchanged.
- Required tests: `.\gradlew.bat :cli:check --no-daemon`; `.\gradlew.bat :mcp:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `bootstrap\go test -count=1 ./...`; `bootstrap\go vet ./...`.
- Scope boundary: no surface changes, no new dependencies, and no unrelated implementation cleanup.
- Evidence: PASS — `:cli:check`; `:mcp:check`; root `check`; launcher help/version/init/doctor/provider/workspace/lifecycle acceptance; stale command-FQN scan; commit `958a039`.

### QUALITY-DEDUP

- ID: QUALITY-DEDUP
- Priority: P1
- Title: Evidence-based deduplication
- Status: DONE
- Purpose: Remove only genuinely identical duplicated infrastructure or lifecycle logic with narrow ownership and direct tests.
- Dependencies: STRUCT-1D DONE; clean working tree; durable state activated before production edits.
- Acceptance criteria: duplication audit recorded; only behavior-identical groups extracted; no generic dumping-ground abstraction; affected and full tests pass.
- Scope boundary: no warning cleanup, god-class splitting, feature work, or behavior changes.
- Evidence: PASS — audit identified identical SHA-256 UTF-8 plan hashing in cleanup, reconciliation, and repair; extracted lifecycle-owned `PlanIntegrity` with direct test; `:workspace:check`; commit `98755b3`.

### QUALITY-WARNINGS

- ID: QUALITY-WARNINGS
- Priority: P1
- Title: Legitimate warning cleanup
- Status: DONE
- Purpose: Resolve verified compiler, static-analysis, and IDE warnings without broad suppression.
- Dependencies: QUALITY-DEDUP DONE.
- Evidence: PASS — warning-mode Gradle audit found four execution-time `project.file(...)` deprecations across verification tasks; replaced them with `layout` providers; root `check --warning-mode all` passed with zero warning lines; commit `98cda05`.

### QUALITY-GOD

- ID: QUALITY-GOD
- Priority: P1
- Title: Focused god-class splitting
- Status: DONE
- Purpose: Split only the strongest evidence-based oversized orchestration classes while preserving stable facades and behavior.
- Dependencies: QUALITY-WARNINGS DONE; clean working tree; durable state activated before production edits.
- Evidence: PASS — ranked candidates by size and responsibility concentration; extracted MCP configuration persistence from `ProviderApplicationService` into lifecycle-owned `ProviderMcpConfigurationService` while preserving the public facade; `:workspace:check` and focused provider tests passed; commit `04977b9`.

## SYN-016

- ID: SYN-016
- Priority: P1
- Title: Organize coordination domain packages
- Status: DONE
- Purpose: Replace the oversized flat `org.synesis.coordination.domain` package with responsibility-based packages inside `:coordination` only.
- Dependencies: SYN-015 DONE; clean production baseline; existing module boundaries preserved.
- Acceptance criteria: capability, task, ownership, prediction, integration, speculation, and command responsibilities have explicit package ownership; zero stale production FQNs remain; no serialized names, event formats, reason codes, CLI/MCP surfaces, or module dependencies change.
- Required tests: `:coordination:check`, root `check`, focused coordination tests, stale-FQN scan, package-cycle scan, and Go tests/vet.
- Scope boundary: no implementation rewrites, no cross-module moves, no new Gradle dependencies, no deduplication, warning cleanup, or god-class work.
- Package map: `org.synesis.coordination.domain.capability`, `.task`, `.ownership`, `.prediction`, `.integration`, `.speculation`, and `.command`.
- Evidence: PASS — moved 35 domain types and four mirrored tests; added responsibility-boundary architecture coverage without package-info files; `:coordination:check`, root `check`, Go tests/vet, stale-FQN scan, and no-flat-package scan passed; commit `195fc95`.

## SYN-017

- ID: SYN-017
- Priority: P1
- Title: Organize workspace application packages
- Status: VERIFYING
- Purpose: Replace the flat `org.synesis.workspace.application` package with responsibility-based application packages supported by the existing workspace services.
- Dependencies: SYN-016 DONE; clean working tree; durable state reconciled before production edits.
- Acceptance criteria: application services are grouped by responsibility without moving types between Gradle modules; zero stale production FQNs remain; no runtime behavior, public method signatures, CLI/MCP surfaces, provider identifiers, schemas, reason codes, event formats, or durable identifiers change; no package-info files are added.
- Required tests: affected workspace tests after each coherent move; `:workspace:check`; `:cli:check`; `:mcp:check`; root `check`; bootstrap Go tests/vet; stale-reference and package-cycle scans.
- Scope boundary: workspace module only; no deduplication, warning cleanup, god-class splitting, implementation rewrite, new Gradle dependency, Go bootstrap change, or automatic progression to another task.
- Evidence: prior package work was committed through `a67dd00`; the hygiene preflight exposed a current architecture-test assertion mismatch and parallel Gradle result-file race, so this task remains VERIFYING rather than being reactivated or silently declared DONE.

## SYN-014C

- ID: SYN-014C
- Priority: P0
- Title: Post-MVP Hardening Slice 3: Crash reconciliation and task cancellation
- Status: DONE
- Purpose: Implement provider-session lease evidence, strong process verification, suspected-stale grace periods, immutable reconciliation plans, explicit reconciliation execution, safe interrupted integration recovery, durable session abandonment, ambient `synesis.cancel_task` MCP tool (tool #11), dependency invalidation, ownership release, and CLI command `synesis reconcile`.
- Dependencies: SYN-014B DONE at CP-0188.
- Acceptance criteria: session lease store, reconciliation plan/lock/journal, `synesis reconcile`, `synesis.cancel_task` MCP tool, unit/integration/MCP tests passing, full `.\gradlew.bat check --no-daemon` passing.
- Scope boundary: No process termination, no worktree deletion during reconciliation/cancellation, no snapshot deletion, no doctor repair, no MCP surface breaking changes.
- Evidence: `.\gradlew.bat check --no-daemon` BUILD SUCCESSFUL at CP-0189.

## SYN-014D

- ID: SYN-014D
- Priority: P0
- Title: Post-MVP Hardening Slice 4: Doctor diagnostics and safe administrative repair
- Status: DONE
- Purpose: Implement unified read-only DoctorService diagnostics (`synesis doctor`), 38 finding codes, severity/status/confidence models, actionable recommendations, read-only guarantees, and a separate, reviewable, narrowly scoped repair-plan system (`synesis repair`) with immutable persisted plans, project repair lock, execution journal, backup & rollback support, and safe administrative repairs.
- Dependencies: SYN-014C DONE at CP-0189.
- Acceptance criteria: DoctorService read-only by construction, DoctorCommand read-only, RepairPlan/Store/Lock/Journal, RepairBackupService, rollback support, 11 MCP tools unchanged, comprehensive test suite passing, full `.\gradlew.bat check --no-daemon` passing.
- Scope boundary: No installer/updater changes, no process termination, no provider config modification, no event log rewriting, no snapshot deletion, no worktree force deletion, no new MCP tools.
- Evidence: `.\gradlew.bat check --no-daemon` BUILD SUCCESSFUL at CP-0190; DoctorServiceTest PASS; RepairPlanTest PASS; DoctorCommandTest PASS; RepairCommandTest PASS.

## SYN-014E

- ID: SYN-014E
- Priority: P0
- Title: Post-MVP Hardening Slice 5C.1: Real-provider acceptance and evidence correction
- Status: READY
- Purpose: Commit the intentional CLI edits, correct update/rollback preservation evidence boundaries, and complete real-provider acceptance where provider access permits.
- Dependencies: SYN-014D DONE at CP-0190; existing SYN-009C/SYN-009D bootstrap evidence.
- Acceptance criteria: five CLI edits committed; update-only and rollback-only history/snapshot comparisons are explicit and pass; real Codex and Antigravity sessions are validated where available; MCP surface remains unchanged; no false collaboration claim is made. Current slice additionally requires canonical `claude` provider installation for Claude Code project `.mcp.json`, `claude-code` input compatibility, unrelated configuration preservation, and no Claude Desktop changes.
- Required tests: signed bundle verification, old-process coexistence, rollback/reactivation, invalid-bundle rejection, provider/project preservation, real-provider MCP tool discovery, Doctor read-only, and applicable Gradle/bootstrap checks.
- Scope boundary: no new MCP tools/schemas, process termination, old-version deletion, remote update service, event-log rewriting, snapshot deletion, provider credential fabrication, or public release work.
- Evidence: CLI edits committed at `eef0fd89b5d89822f567110f048cd3dcb65a3b25`; signed A/B lifecycle, invalid-bundle rejection, and 11-tool MCP invariance remain verified; update-only and rollback-only event/snapshot/identity comparisons pass with empty pre-existing coordination sets; Codex `0.140.0` is blocked by external ChatGPT-account model access; Antigravity real MCP discovery/session is unproven; no collaboration claim is made.

## SYN-001

- ID: SYN-001
- Priority: P0
- Status: DONE
- Purpose: Prove that two isolated configured profiles can authenticate,
  publish, persist, inspect, and synchronize exactly one signed decision
  record above Link, while detecting duplicates, conflicts, and stale state.
- Dependencies: frozen SL-013/CP-0054 baseline; approved ADR-0011; and
  separately verified SL-014 transport-neutral bounded Link application-stream
  seam. The existing fixed demo stream is not a substitute.
- Acceptance criteria: the complete criteria in
  `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md` are met, including
  stable identity/version/provenance/owner/status/evidence, authenticated
  transfer, deterministic duplicate/conflict/staleness results, durable local
  revision storage, and readable safe inspection.
- Required tests: canonical record/signature/store/conflict tests; Link-seam
  authentication/bounds/cleanup tests; isolated two-profile publish/sync test;
  CLI-inspection test; unchanged Link/CLI regressions.
- Required documentation: accepted ADR-0011, record protocol/storage/threat
  documentation, test matrix, deferred reconciliation, and sanitized evidence.
- Scope completed: CP-R4 project configuration and explicit peer allowlists,
  bounded SYNC_REQUEST/RECORD/RESULT/ERROR messages, one-shot authenticated
  publish/sync, deterministic duplicate/stale/conflict/rejected/applied/
  unknown outcomes, and valid divergent-record quarantine. No background
  behavior, retries, discovery, membership, extra record types, physical
  claims, or `:cli` changes.
- Evidence: `docs/evidence/DECISION-RECORD-CP-R4-2026-07-21.md`; focused
  CP-R4 tests and full strict root verification PASS. Closed at CP-R4.
- CP-R5 physical two-profile record transfer is a validation claim boundary in
  `docs/operations/NETWORK_VALIDATION_MATRIX.md`; historical ID `SL-D-028` is
  preserved in the archive.

## SYN-001-CP-R5

- ID: SYN-001-CP-R5
- Priority: P1
- Title: Physical two-profile decision-record transfer claim
- Status: DEFERRED
- Purpose: Validate the existing CP-R4 one-shot decision exchange across two
  physical machines only if a real two-machine claim becomes necessary.
- Dependencies: SYN-001 CP-R4 DONE; explicit operator demand for a physical
  record-transfer claim; network/security evidence.
- Acceptance criteria: two physical profiles complete the existing CP-R4
  scenarios with sanitized evidence, or the claim remains explicitly absent.
- Required tests: physical initial publish, duplicate retry, successor,
  stale, conflict, and cleanup; no protocol expansion is implied.
- Required documentation: network validation matrix, physical evidence, threat review, and
  checkpoint state.
- Evidence: deferred; no physical record-transfer claim is made.

## SYN-002

- ID: SYN-002
- Priority: P1
- Title: Minimal searchable project view over signed decisions
- Status: DONE
- Purpose: Define and, only after review, expose a bounded read-only view of
  existing verified decision heads so a human can find and compare shared
  project truth without adding another record type or protocol.
- Dependencies: SYN-001 CP-R4 DONE; frozen Link seam; frozen
  `:project-record` storage and signature rules.
- Acceptance criteria: bounded query grammar, deterministic result order,
  corruption/failure behavior, verified-head-only reads, safe rendering, and
  no-mutation behavior are implemented and verified inside `:project-record`.
  The task is closed after CP-0075 verification review; no further production
  scope is open.
- Required tests: query bounds/encoding, deterministic matching and ordering,
  verified-head-only results, corruption fail-closed behavior, no-mutation
  checks, conflicts, stale revisions, temporary files, and restart-equivalent
  results.
- Required documentation: ADR-0013, phase-map update, test matrix, deferred
  reconciliation, and a planning checkpoint.
- Scope boundary: no new signed record type, wire message, sync behavior,
  background process, Link change, `:cli` change, index persistence, or
  Obsidian integration.
- Evidence: `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`; focused
  `DecisionSearchTest` and full strict verification PASS.

## SYN-003

- ID: SYN-003
- Priority: P0
- Title: Workspace bootstrap and first two-person decision demo
- Status: DONE
- Purpose: Provide the smallest operator-owned composition layer that creates
  isolated profiles, local signed decisions, and one-shot sync using the
  existing frozen Link and project-record APIs.
- Dependencies: SYN-002 DONE; frozen SL-013/CP-0054, SL-014, and
  `:project-record` CP-R4 boundary.
- Acceptance criteria: CP-W1 adds a JDK-only `:workspace` launcher with bounded
  profile handling, isolated `<profile>/link`, `<profile>/project.conf`, and
  `<profile>/records` layout, identity inspection, atomic one-peer project
  creation with overwrite/mismatch refusal, and revision-1 signed decision
  creation with exactly one evidence reference. Output is stable and safe and
  includes `NODE_ID`, `PROJECT_ID`, `RECORD_ID`, and `DIGEST`.
- Required tests: profile isolation/restart, argument bounds, identity reuse,
  project overwrite and mismatch refusal, atomic config persistence, signed
  decision/evidence validation, stable output, and sensitive-output redaction.
- Required documentation: ADR-0014, first two-person demo script, CP-W1
  evidence, phase-map update, test matrix, and durable state files.
- Scope boundary: no retries, reconnect, discovery, membership, new record
  type, Link or CLI production changes, background behavior, workers, leases,
  autonomy,
  federation, Obsidian, or physical-machine claim.
- Implementation checkpoints: CP-W1 is local bootstrap and decision creation;
  CP-W2 is the separately approved authenticated host/join and sync slice.
- Evidence: `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`,
  `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`, and
  `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`; focused workspace tests and
  full strict root verification PASS. Closed at CP-W3.
- CP-W2 acceptance: `sync host` uses the sole configured peer; `sync join`
  authenticates and pins the expected host before creating B's configuration,
  performs exactly one CP-R4 sync, treats only APPLIED and DUPLICATE as
  success, and returns nonzero for UNKNOWN, REJECTED, REMOTE_STALE, CONFLICT,
  authentication, configuration, invitation, or transport failures.

## SYN-PRODUCT-REVIEW

- ID: SYN-PRODUCT-REVIEW
- Priority: P0
- Title: Product review and future planning through CP-0079
- Status: DONE
- Purpose: Evaluate the product value of Synesis through CP-0079, identify friction points, outline future milestones, and recommend the next step.
- Dependencies: SYN-003 DONE
- Acceptance criteria: A complete product review document under `docs/agent/PRODUCT_REVIEW.md` is committed and checkpointed.
- Required tests: resume, doctor, checkpoint, fixture validators.
- Required documentation: `docs/agent/PRODUCT_REVIEW.md`, tasks, state, current, next session.
- Evidence: `docs/agent/PRODUCT_REVIEW.md` and checkpoint CP-0080.

## SYN-004

- ID: SYN-004
- Priority: P0
- Title: Minimal guided workspace demo flow
- Status: DONE
- Purpose: Reduce the two-person workspace demo to the fewest safe operator steps while preserving host Node ID pinning and cryptographic identity verification.
- Dependencies: SYN-PRODUCT-REVIEW DONE, SYN-003 DONE
- Acceptance criteria: Update `:workspace` CLI commands: `sync host` takes optional `--project` and `--record` arguments and outputs a parameterized invitation link; `sync join` accepts a single invitation link, parses the project/record/host parameters, verifies the local configuration, pins the connection to the host Node ID, and runs sync. Clean error exit code `10` is accompanied by stderr contextual `HINT:` messages. No change to wire protocol formats or storage.
- Required tests: Unit tests for URI validation and query param extraction; integration process tests verifying the single-link flow, wrong host pinning rejection, and contextual next-action hints.
- Required documentation: Design document `docs/agent/SYN_004_DESIGN.md`, updated demo flow document `docs/demo/FIRST_TWO_PERSON_PROJECT_DEMO.md`, and CP-W4 evidence.
- Evidence: CP-0083; WorkspaceSyncProcessTest; full strict check PASS.

## SYN-005

- ID: SYN-005
- Priority: P0
- Title: Project-wide reconciliation over one authenticated session
- Status: DONE
- Purpose: Design and implement the smallest bounded bidirectional reconciliation protocol over a single Link session to synchronize all missing or divergent verified record heads.
- Dependencies: SYN-004 DONE
- Acceptance criteria: Design and implement PRP1 protocol over the existing authenticated Link stream seam. Exchange inventories, transfer contiguous missing revisions, prevent deletion/overwriting of divergent heads, quarantine conflict heads, verify every revision independently before storage, enforce bounds on size/records, and report per-record and project-level outcomes. Integrated `check-action` command for action-time constraint enforcement. Clean exit codes with contextual stderr `HINT=` messages.
- Required tests: Unit tests for PRP1 codec and reconciliation logic; integration process tests verifying project-wide convergence, conflict quarantining, corruption detection, and action-time checking (`projectReconciliationAndCheckActionWorkflow`).
- Required documentation: Design document `docs/agent/SYN_005_DESIGN.md`, ADR-0015, `docs/development/current-state.md`, and checkpoint CP-0091.
- Evidence: PASS — `ReconciliationMessageTest`, `ProjectReconciliationSyncProcessTest`, `WorkspaceSyncProcessTest`, and `gradlew.bat clean check --dependency-verification=strict`. Closed at CP-0091.

## SYN-006

- ID: SYN-006
- Priority: P0
- Title: Constraint Hardening and First Enforceable Harness Integration
- Status: DONE
- Purpose: Introduce typed project constraints, deterministic scope matching, portable Gradle settings, and a Claude Code pre-tool execution hook adapter.
- Dependencies: SYN-005 DONE
- Acceptance criteria: Implement ProjectConstraint typed model with LEGACY_INFERRED fallback, ScopeMatcher path normalization and wildcard matching engine, portable 2GB heap Gradle default with test fork controls, and ClaudeCodeHookAdapter pre-tool execution hook integration.
- Required tests: ScopeMatcherTest, ProjectConstraintTest, ClaudeCodeHookAdapterTest, WorkspaceSyncProcessTest.
- Required documentation: ADR-0016, current-state.md, and checkpoint CP-0092.
- Evidence: PASS — `ScopeMatcherTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`, `gradlew.bat clean check --dependency-verification=strict`.

## SYN-007

- ID: SYN-007
- Priority: P0
- Title: Clean Typed Constraint Model and Baseline-vs-Synesis Validation
- Status: DONE
- Purpose: Remove unreleased legacy constraint inference, introduce SDR2 canonical record versioning with explicit typed constraint payloads, make adapter warnings observable, and build an automated baseline vs. Synesis experiment.
- Dependencies: SYN-006 DONE
- Acceptance criteria: Remove LEGACY_INFERRED and title-prefix fallback; evolve DecisionRecord to SDR2 (0x53445232); implement explicit RecordType and ConstraintPayload; enhance ClaudeCodeHookAdapter with WARNING and UNSUPPORTED diagnostics; create automated experiment script scripts/run-synesis-guardrail-experiment.ps1 and docs/validation/baseline-vs-synesis-experiment.md.
- Required tests: DecisionRecordTest, ProjectConstraintTest, ClaudeCodeHookAdapterTest, WorkspaceSyncProcessTest, run-synesis-guardrail-experiment.ps1.
- Required documentation: ADR-0017, baseline-vs-synesis-experiment.md, current-state.md, and checkpoint CP-0093.
- Evidence: PASS — `DecisionRecordTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`, `run-synesis-guardrail-experiment.ps1`, `gradlew.bat clean check --dependency-verification=strict`.

## SYN-007.1

- ID: SYN-007.1
- Priority: P0
- Title: Real Claude Code PreToolUse Contract Conformance
- Status: DONE
- Purpose: Align ClaudeCodeHookAdapter with official Claude Code v2.1+ PreToolUse hook contract (permissionDecision: deny, exit code 0, absolute-to-relative path resolution, additionalContext for warnings).
- Dependencies: SYN-007 DONE
- Acceptance criteria: Update ClaudeCodeHookAdapter and WorkspaceCli to exit code 0 on JSON denial responses; implement resolveRelativePath converting absolute CWD/path inputs to project-relative scopes; add docs/integration/claude-code-hook.json; verify supersession filtering in ProjectConstraint; update automated experiment script scripts/run-synesis-guardrail-experiment.ps1.
- Required tests: ClaudeCodeHookAdapterTest, ProjectConstraintTest, WorkspaceSyncProcessTest, run-synesis-guardrail-experiment.ps1.
- Required documentation: ADR-0018, current-state.md, and checkpoint CP-0094.
- Evidence: PASS — `ClaudeCodeHookAdapterTest`, `ProjectConstraintTest`, `WorkspaceSyncProcessTest`, `run-synesis-guardrail-experiment.ps1`, `gradlew.bat clean check --dependency-verification=strict`.




## SYN-008

- ID: SYN-008
- Priority: P0
- Title: Antigravity PreToolUse Adapter and Real-Agent Validation
- Status: DONE
- Purpose: Add AntigravityHookAdapter reusing ActionGuardrail, expose via hook antigravity CLI subcommand, run automated experiment proving guardrail denial with official Antigravity PreToolUse payload shape.
- Dependencies: SYN-007.1 DONE
- Acceptance criteria: ActionGuardrail harness-neutral evaluator extracted; AntigravityHookAdapter processes toolCall.name/toolCall.args.TargetFile/workspacePaths; force_ask for WARN; deny for BLOCK; ask for ALLOWED; deny for invalid/missing TargetFile; ask+diagnostic for unsupported tools; resolveRelativePath and selectProjectRoot boundary-verified; automated experiment passes with p50/p95 latency; ADR-0019; docs/integration/antigravity-hook.md and antigravity-hooks.json; docs/validation/antigravity-real-agent-experiment.md; checkpoint CP-0095.
- Required tests: AntigravityHookAdapterTest, ClaudeCodeHookAdapterTest, ActionGuardrailTest, run-antigravity-guardrail-experiment.ps1.
- Required documentation: ADR-0019, antigravity-hook.md, antigravity-hooks.json, antigravity-real-agent-experiment.md, current-state.md, CP-0095.
- Evidence: PASS — BUILD SUCCESSFUL in 2m 4s (39 tasks); run-antigravity-guardrail-experiment.ps1 SYNESIS_ACTION_RESULT=BLOCKED, GUARDRAIL_LATENCY_P50_MS=181, GUARDRAIL_LATENCY_P95_MS=196, SYNESIS_FALSE_POSITIVE_COUNT=0.

## SYN-009A

- ID: SYN-009A
- Priority: P0
- Title: Unified CLI, application services, project initialization, and local state layout
- Status: DONE
- Purpose: Make `synesis` the sole public CLI, extract workspace application services, and establish safe discovered `.synesis` project state for the SYN-009 roadmap.
- Dependencies: SYN-008 DONE; CP-0095; existing `:link`, `:project-record`, `:workspace`, and `:cli` modules.
- Acceptance criteria: `:cli` owns the public command tree and composition; `:workspace` is a library without an application launcher; workspace business logic is exposed through structured application services without Picocli or direct console output; project discovery and `synesis init` create and validate the bounded `.synesis` layout; ordinary commands default to `.synesis/local/profile` with an explicit advanced profile override; existing host/join, decision/constraint, guardrail, and hook behavior remains covered; package and dependency checks pass.
- Required tests: service results, project discovery/init conflicts and secrets, unified command reachability and exit/output contracts, package-boundary checks, launcher retirement, and current module tests. The unreleased legacy process harness is intentionally removed rather than retained as a compatibility requirement.
- Required documentation: implementation note, ADR-0020, package boundaries, project layout, command reference, current state, checkpoints CP-0096 and CP-0099, and durable task state.
- Scope boundary: provider lifecycle, expanded doctor, portable ZIP, version injection, protocol changes, background synchronization, additional adapters, and remote publication were out of scope.
- Evidence: PASS — `ProjectApplicationServiceTest`, adapter/workspace/CLI tests, `:workspace:architectureCheck`, strict Javadocs, `gradlew.bat clean check --dependency-verification=strict` (34 actionable tasks), unified launcher smoke tests, and CP-0099. Unreleased compatibility launchers and process harnesses are deleted.

## SYN-009B

- ID: SYN-009B
- Priority: P0
- Title: Provider lifecycle management and installation diagnostics
- Status: DONE
- Purpose: Add project-local provider install, status, uninstall, registry, synthetic health checks, and doctor diagnostics for Antigravity and Claude Code.
- Dependencies: SYN-009A DONE; CP-0099; existing unified CLI, project layout, provider adapters, and shared `ActionGuardrail`.
- Acceptance criteria: provider lifecycle is application-service owned; only Antigravity (`BETA`) and Claude Code (`EXPERIMENTAL`) are listed; provider metadata remains local-only; configuration merges preserve unrelated JSON; writes are atomic; malformed configuration is never overwritten; install/status/uninstall are idempotent; synthetic checks use isolated fixtures; doctor reports project, record, provider, and known-limitations results; Codex and portable packaging remain deferred.
- Required tests: registry, provider configuration merge/atomicity, Antigravity and Claude Code lifecycle, isolated synthetic checks, status classification, uninstall preservation, doctor results, unified-launcher process coverage, and strict full verification.
- Required documentation: implementation note, ADR-0021, provider boundary, provider management, doctor, integration docs, current state, and durable task state.
- Scope boundary: no Codex, MCP, dynamic plugins, shell-command analysis, portable ZIP, release packaging, background synchronization, protocol changes, cloud services, or remote publication.
- Evidence: PASS — `ProviderApplicationServiceTest`, `UnifiedCliSyncProcessTest` (five generated-launcher process scenarios), provider Javadocs, `gradlew.bat clean check --dependency-verification=strict`, generated disposable-project Antigravity and Claude Code lifecycle checks with unrelated hook preservation, and CP-0102. Deleted legacy CLI compatibility tests remain deleted; valid protocol/process behavior was rewritten against the unified launcher.

## SYN-009C

- ID: SYN-009C
- Priority: P0
- Title: Cross-platform distribution and bootstrap installation
- Status: DONE
- Purpose: Produce platform-specific Java bundles with bundled runtimes, a Go installer/update bootstrapper, and a verified CI artifact matrix.
- Dependencies: SYN-009B DONE; user-supplied SYN-009C activation; Codex remains EXPERIMENTAL/DEGRADED and is not promoted by this task.
- Acceptance criteria: native Windows bundle smoke passes; Go bootstrap install/update/uninstall/doctor behavior is bounded and tested; detached Ed25519 manifest verification, SHA-256 verification, safe extraction, atomic activation, rollback, and project preservation pass; CI defines windows/linux/macos x64/arm64 artifact jobs and honest native/cross-compiled status; Java provider behavior remains covered; no Link protocol behavior changes.
- Required tests: `:cli:platformBundle`, bundled-runtime smoke, Go unit/integration tests, artifact/manifest checks, safe-extraction tests, and strict Java verification.
- Required documentation: implementation note, three distribution ADRs, installation/release/signing docs, smoke evidence, and durable state updates.
- Scope state: activated from the pasted SYN-009C goal; no public release or remote publication.
- Evidence: PASS — CP-0106 through CP-0110 and commit `7a40324`; Java strict
  build/archive extraction smoke, Go tests/vet/native subprocess, six
  cross-builds, real Windows Java ZIP bootstrap install and bundled CLI/provider
  lifecycle trial, CI matrix/sidecar validation, release documentation, and
  clean working tree. Production key replacement, OS signing/notarization, and
  public publication remain explicitly deferred.

## SYN-009B.1

- ID: SYN-009B.1
- Priority: P0
- Title: Codex PreToolUse adapter, provider lifecycle, and real-agent validation
- Status: VERIFYING
- Purpose: Add the smallest project-local Codex `apply_patch` PreToolUse adapter and provider lifecycle integration on top of the closed SYN-009B foundation.
- Dependencies: SYN-009B DONE at CP-0102; Codex CLI 0.140.0 is locally installed; official Codex hook/config contract review recorded in `docs/agent/SYN_009B1_IMPLEMENTATION_NOTE.md`.
- Acceptance criteria: Codex is listed after Antigravity and Claude Code as `EXPERIMENTAL`; `synesis hook codex` parses bounded Add/Update/Delete/Move patch paths, resolves `cwd` through the shared project/path guardrail boundary, denies any blocked or invalid multi-path patch with exit 0, emits bounded warnings as `additionalContext`, leaves allowed/unsupported stdout empty, and never applies patches; provider install/status/uninstall owns project-local `.codex/hooks.json` atomically and idempotently while preserving unrelated configuration; install/status/doctor report trust `REVIEW_REQUIRED`/`UNKNOWN` and stay degraded until a real validated run; synthetic tests, process-level launcher coverage, Codex version/fixture capture, and the real authenticated `/hooks` experiment are recorded honestly; Codex remains `EXPERIMENTAL` unless every promotion gate passes.
- Required tests: bounded parser tests for Add/Update/Delete/Move, duplicate normalization, malformed/traversal fail-closed cases, adapter allow/block/warning/unsupported/invalid behavior, multi-path aggregation, provider merge/atomicity/idempotence/uninstall preservation, generated-launcher hook and lifecycle process coverage, and a 20-invocation p50/p95 measurement.
- Required documentation: implementation note, ADR-0022, `docs/integration/codex-hook.md`, `docs/validation/codex-real-agent-experiment.md`, provider/doctor/current-state updates, sanitized actual payload fixture with version, checkpoint evidence, and durable state updates.
- Scope boundary: no Bash hooks, MCP, SDK/App Server, transcript parsing, patch application, trust-database edits, portable ZIP, release packaging, protocol changes, dynamic plugins, or remote publication.
- Evidence: Synthetic parser/adapter/provider tests, generated launcher process
  coverage, 20-call latency measurement (`p50=1.247 ms`, `p95=1.806 ms`),
  strict clean check, and disposable generated-launcher lifecycle checks PASS.
  Real `/hooks` trust review and authenticated denial/re-plan/hash evidence are
  not complete; see `docs/validation/codex-real-agent-experiment.md`.

## Deferred capability register


Deliberately postponed, unsupported, partially verified, and physically
unverified capabilities are tracked in
[`DEFERRED.md`](DEFERRED.md).

Deferred entries are not committed implementation tasks and are not release
promises. A deferred capability enters the task graph only after its activation
trigger is satisfied, required evidence or research exists, the item is
explicitly promoted, a concrete task with acceptance criteria is created, and
exactly one task is made `ACTIVE`. Keep the register entry until the promoted
task replaces it; then mark it `SUPERSEDED` with the task and completion
checkpoint. Use `CANCELLED` only for a deliberate permanent scope decision.

## SL-011

- ID: SL-011
- Priority: P0
- Title: CLI and release verification
- Status: READY
- Purpose: Deliver the public-API-only two-peer CLI and release evidence.
- Dependencies: SL-010
- Acceptance criteria: clean build, CLI workflow, generated Javadocs, protocol docs, vectors, release notes/checklist, and two-machine evidence.
- Required tests: CLI and two-machine tests.
- Required documentation: operations guide, release notes, release checklist.
- Evidence: pending.

## SYN-018

- ID: SYN-018
- Priority: P1
- Title: Repository documentation and script hygiene
- Status: DONE
- Purpose: Inventory, reconcile, organize, and verify maintained Markdown,
  generated agent instructions, repository scripts, and documentation links
  without changing product behavior or the CLI/MCP/provider surfaces.
- Dependencies: prior package and code-quality slices; `SYN-014E` remains paused.
- Acceptance criteria: maintained documentation matches the implemented
  command/provider/MCP surfaces; historical records remain intact; genuinely
  duplicate or obsolete scripts are removed only with evidence; internal links,
  repository paths, script references, machine-path policy, and MCP tool-count
  claims have narrow automated checks; required verification and checkpoint
  evidence are recorded; exactly 11 MCP tools remain exposed.
- Required tests: repository hygiene checks, root Gradle check, Go test/vet,
  CLI help/version, direct MCP initialize/tools-list, provider list, init
  instruction generation, and maintained-link/script reachability checks.
- Required documentation: README, maintained guides, generated AGENTS.md
  source, inventory, durable state, and final hygiene evidence.
- Scope boundary: no production behavior, CLI/MCP surface, provider ID/alias,
  durable schema, event history, signed evidence, updater/signing semantics,
  remote networking, provider-process termination, or forced worktree removal.
- Evidence: PASS — complete at commits `4b7f530`, `59f7c63`, `39f7ff4`, and `501bbca`;
  maintained documentation, generated instructions, hygiene checks, and
  checkpoint evidence are recorded. The architecture-test mismatch was
  promoted to SYN-019 and closed separately.

## SYN-019

- ID: SYN-019
- Priority: P1
- Title: Close workspace application package architecture rule
- Status: DONE
- Purpose: Reconcile the workspace application root-package architecture test
  with the completed package refactor without changing runtime behavior.
- Dependencies: SYN-018 hygiene complete; `SYN-014E` remains paused.
- Acceptance criteria: only deliberate stable application facades remain under
  `org.synesis.workspace.application`; internal collaborators remain in
  responsibility-specific packages; the focused architecture test, module
  checks, full Gradle check, Go test/vet, MCP 11-tool tests, and CLI/provider
  invariants pass; exactly one narrow correction commit and checkpoint are
  recorded.
- Scope boundary: no production behavior, runtime/package redesign, duplication
  cleanup, CLI/MCP/provider changes, schemas, event formats, documentation
  modernization, live demos, or networking work. Explicit exception authorized
  by the user: one narrow Go bootstrap portability fix for versioned activation
  ordering, with no broader installer behavior change, plus the narrow Linux
  MCP absolute-file-URI parsing regression reported by CI.
- Evidence: PASS — the root production directory contains only `ProjectApplicationService.java`; the stale test allowlist was corrected in commit `a87d3d8`.

## SYN-020

- ID: SYN-020
- Priority: P0
- Title: Active work intent and exact-path claim arbitration
- Status: DONE
- Purpose: Let authenticated sessions announce intended work, discover active
  participants, and acquire atomic exact-path/subtree claims before MCP
  mutation.
- Dependencies: SYN-019 DONE; existing signed coordination event store,
  provider session binding, and isolated worktree mutation broker.
- Acceptance criteria: bounded participant, intent, selector, and claim
  records; deterministic overlap evaluation; atomic all-or-nothing acquisition;
  signed event replay; MCP mutation denial for missing/overlapping claims; and
  CLI/MCP adapter parity without changing the 11-tool count.
- Required tests: exact/exact conflict, exact/subtree conflict, unrelated
  claims, concurrent acquisition, multi-selector rollback, release/finalize,
  replay compatibility, and unchanged-file mutation denial.
- Required documentation: ADR for claim arbitration, durable state/checkpoint,
  and acceptance evidence using the task-tracker fixture.
- Scope boundary: no symbol/area selectors, dependency invalidation,
  out-of-band filesystem prevention, deadlock detection, Antigravity maturity
  claims, or MCP naming migration.
- Evidence: PASS — coordination/workspace/MCP/CLI tests and strict root check
  pass; bootstrap Go test/vet and deferred validation pass; claim arbitration
  is covered by `WorkIntentServiceTest` and
  `WorkspacePatchServiceTest.conflictingSessionCannotMutateClaimedPath`; the
  two-handler MCP task-tracker acceptance passes with pre-mutation blocking;
  CP-0260 records stable event-code compatibility, append locking, exact
  binding authorization, lease activity, clean EOF release, and explicit
  release evidence.

## SYN-021

- ID: SYN-021
- Priority: P0
- Title: Authenticated claim lifecycle, presence, and stale fencing
- Status: DONE
- Purpose: Project active participants, lease-backed presence, explicit and
  owner-independent claim recovery, and epoch fencing through shared CLI/MCP
  collaboration services.
- Dependencies: SYN-020 DONE at CP-0260; existing session lease and
  reconciliation services.
- Acceptance criteria: exact-session participant projection; one active intent
  per verified session; heartbeat renewal and clean EOF; suspected-stale versus
  abandoned classification; explicit release on refresh/completion/cancellation;
  verified-abandonment recovery; old-epoch mutation fencing; CLI/MCP parity.
- Required tests: lifecycle projection, heartbeat coalescing, stale/grace
  boundaries, deleted-process recovery, exact-caller release, old-epoch
  rejection, concurrent unrelated claims, and adapter equivalence.
- Required documentation: lifecycle ADR, replay evidence, checkpoint, test
  matrix updates, and provider evidence boundaries.
- Evidence: CP-0266; `WorkIntentServiceTest`, `AbandonmentTest`, focused
  coordination/workspace/MCP/CLI checks, and full Gradle verification pass.
- Scope boundary: no handoff negotiation, contract revision, out-of-band
  integration enforcement, deadlock detection, or MCP naming migration.

## SYN-022

- ID: SYN-022
- Priority: P0
- Title: Contract revisions and dependency invalidation
- Status: DONE
- Purpose: Publish stable shared contracts, track explicit consumers, and
  invalidate stale dependent work before implementation or integration.
- Dependencies: SYN-021 DONE at CP-0266; SL-D-037 activated; signed event log
  and shared collaboration application service.
- Acceptance criteria: contract ID/revision/content hash/owner/status are
  durable; consumers bind to exact revisions; superseding a revision marks
  consumers REPLAN_REQUIRED; stale publication is rejected; history replays;
  CLI and MCP expose equivalent contract inspection/publication behavior.
- Required tests: publish, accept, consume, supersede, stale revision reject,
  explicit dependency invalidation, replay, and compatible independent work.
- Required documentation: contract ADR, deferred-register activation note,
  checkpoint, test matrix, and task-tracker API/schema evidence.
- Evidence: CP-0267; `ContractServiceTest`, focused workspace/MCP/CLI tests,
  strict Javadocs, and full Gradle verification pass.
- Scope boundary: no general semantic API inference, out-of-band mutation
  enforcement, deadlock detection, or provider maturity claims.

## SYN-023

- ID: SYN-023
- Priority: P0
- Title: Contract-aware pre-merge compatibility checks
- Status: DONE
- Purpose: Validate immutable task snapshots, owned changed paths, contract
  revisions, project tests, and control-head ancestry before advancement.
- Dependencies: SYN-022 DONE; isolated worktrees and guarded fast-forward
  service; SL-D-038 promoted for this bounded integration slice.
- Acceptance criteria: prepare/check/advance stages preserve the control
  checkout until all checks pass; stale bases, uncovered paths, superseded
  contracts, unresolved coordination, direct-write violations, failed tests,
  and overlapping snapshots are blocked with actionable diagnostics; a
  configured Python project runs `python -m pytest -q`.
- Required tests: compatible snapshots pass; changed-path claim coverage,
  contract revision, ancestry, overlap, direct-write, and Python test failures
  block deterministically; successful checks can advance only by guarded
  compare-and-fast-forward.
- Required documentation: integration ADR, deferred-register activation note,
  checkpoint, test matrix, and task-tracker integration evidence.
- Evidence: CP-0269; `IntegrationCompatibilityServiceTest`, synthetic
  two-process pytest-backed integration, workspace Javadocs, and focused
  workspace/MCP tests pass.
- Scope boundary: no general language API inference, broker, or control-checkout
  mutation path.

## SYN-024

- ID: SYN-024
- Priority: P0
- Title: Unified collaboration CLI/MCP surface and raw MCP names
- Status: DONE
- Purpose: Keep collaboration capabilities in shared services while exposing
  equivalent CLI/MCP operations and a stable raw 11-tool wire contract.
- Dependencies: SYN-023 DONE at CP-0269; existing 11-tool MCP server and
  provider configuration key `synesis`.
- Acceptance criteria: raw MCP names are advertised exactly once; legacy
  `synesis.*` calls remain accepted; CLI and MCP expose equivalent intent,
  status, release, handoff, contract, and readiness outcomes; schemas and
  error codes are documented and tested.
- Required tests: raw tools/list, legacy tools/call compatibility, CLI command
  registration, shared-service outcome equivalence, and provider config smoke.
- Required documentation: naming ADR, checkpoint, provider documentation,
  test matrix, and migration limitations.
- Evidence: CP-0271; raw tools/list and legacy call tests, CLI registration,
  readiness adapters, strict Javadocs, and full Gradle verification pass.
- Scope boundary: no provider maturity claim or remote service.

## SYN-025

- ID: SYN-025
- Priority: P0
- Title: Provider collaboration acceptance and evidence
- Status: DONE
- Purpose: Run real Claude, Codex, and Antigravity collaboration scenarios
  where provider access exists, and record unavailable-provider limits honestly.
- Dependencies: SYN-024 DONE at CP-0271; local distribution installed; MCP
  server and collaboration protocol tests green.
- Acceptance criteria: successful provider sessions prove claims, conflict
  negotiation, handoff, release, recovery, and MCP mutation through real
  processes; unavailable auth/quota is recorded without a false completion or
  native-hook claim.
- Required tests: separate Claude/Codex sessions, task-tracker conflict and
  compatible work, handoff/reacquisition, deleted-session recovery, and a
  bounded Antigravity attempt when available.
- Required documentation: provider evidence report, checkpoint, test matrix,
  configuration paths, and limitations.
- Evidence: CP-0281; installed MCP jar and direct initialize probe pass; real
  Codex CLI completed `ensure_session`, then created and reread an isolated
  probe file through MCP with a matching revision hash; two-process
  conflict/EOF evidence also passes; authenticated Claude now proves exact
  conflict blocking, isolated mutation/readback, and explicit
  release/reacquisition; direct Antigravity MCP process acceptance now proves
  initialize, isolated exact claim, mutation/readback hash equality, and clean
  EOF release; the Antigravity model CLI still fails to carry structured claims
  through the mutation prompt and is recorded as a harness limitation; a real
  direct Codex/Claude MCP processes now prove JSON-safe collaboration discovery
  and accepted handoff with intent-version fencing; a real
  Claude contract publish now returns a JSON-safe
  revision/content hash after the MCP projection fix; the subsequent full
  strict Gradle check passes; real Claude contract status inspection returns
  JSON-safe revision and supersession data; deterministic MCP publish/status
  projection regressions pass.
  real Claude deleted-chat v3 process now proves lease creation before forced
  termination, suspected-stale then abandonment-eligible classification,
  owner-independent reconciliation, claim release, ABANDONED projection, and
  old-epoch `workspace_generation_changed` fencing. The task-tracker fixture's
  current `python -m pytest -q` run passes all 45 tests.
  Additive MCP request/handoff operations and exact-session `get_next_action`
  collaboration details are covered by the fixed 11-tool parity tests. The
  final strict Gradle check, local installation, bootstrap Go tests/vet,
  deferred validator, and task-tracker pytest suite all pass. Antigravity
  direct MCP evidence is complete; model-driven prompting and native hooks are
  explicitly not claimed.
- Completion: DONE at CP-0292. The roadmap's provider acceptance criteria are
  satisfied for available real MCP paths; the recorded Antigravity harness
  limitation is external and does not block completion.
- Scope boundary: no remote publication, provider credential changes, or
  universal native-hook enforcement claim.

## SYN-026

- ID: SYN-026
- Priority: P1
- Title: Canonical provider MCP scope and legacy configuration migration
- Status: DONE
- Purpose: Ensure each provider installs one canonical Synesis MCP entry,
  removes stale Synesis entries from legacy scopes, and preserves unrelated
  provider configuration.
- Dependencies: SYN-025 DONE; local distribution and provider lifecycle
  services.
- Acceptance criteria: Codex uses one global TOML Synesis entry while
  preserving `other-server` and `node_repl`; Claude uses one project `.mcp.json`
  entry; Antigravity uses one canonical provider-specific MCP config with an
  explicit project root; reinstall is idempotent; legacy Synesis entries are
  migrated without deleting unrelated settings; diagnostics identify the
  effective scope and duplicates.
- Required tests: Codex legacy JSON cleanup, TOML preservation, Claude
  project-scope idempotence, Antigravity single-scope installation, duplicate
  migration, and provider status/configuration parity.
- Required documentation: provider-scope ADR, migration notes, checkpoint,
  and updated provider configuration documentation.
- Scope boundary: no MCP tool-surface change, no provider credential changes,
  and no claim of native-hook enforcement.
- Completion evidence: `:workspace:check` and focused provider tests pass;
  `:cli:platformZip --rerun-tasks` rebuilt the local bundle; the stable launcher
  now keeps Antigravity's explicit target project root after synthetic checks,
  removes the obsolete global mirror, and preserves unrelated Codex entries.

## SYN-027

- ID: SYN-027
- Priority: P0
- Title: Multi-chat logical workgroups and isolated mutation lanes
- Status: ACTIVE
- Purpose: Support concurrent chats and independently authenticated subagents
  under one durable logical work group while preserving one participant,
  binding, lease, claim epoch, branch, and isolated worktree per mutation lane.
- Dependencies: SYN-026 DONE; ADR-0039; existing exact-path/subtree claim,
  session-binding, snapshot, contract, and guarded integration services.
- Acceptance criteria: exact-caller authority is used by every
  authority-sensitive operation; WorkGroup and LaneGrant records support
  targeted joining, continuation, delegation, lane close/revocation, and
  epoch fencing; legacy intents replay as singleton groups; uncommitted lane
  changes publish as immutable provenance-bearing snapshots; cross-process
  integration is claim-aware and contract-aware; two disjoint lanes mutate in
  isolated worktrees and integrate; overlapping claims grant authority to one
  lane; same-provider bindings cannot be crossed; no physical worktree is
  concurrently mutated.
- Required tests: exact-caller regressions, WorkGroup replay and grant
  lifecycle, continuation replay rejection, delegated lanes, claim epochs,
  uncommitted snapshot capture, out-of-claim rejection, cross-process
  integration serialization, deterministic disjoint/overlap/close acceptance,
  and existing event/session replay.
- Required documentation: ADR-0039, updated collaboration architecture,
  checkpoint evidence, provider limitations, and final acceptance report.
- Scope boundary: no symbol claims, remote multi-user authority, shared
  physical worktree, broker/database/service, or MCP tool-count change.
- Progress evidence: exact-caller authority is implemented in capability
  request/response, publication, validation, collaboration, and mutation
  paths at `4fe76ca`; WorkGroup/LaneGrant records and versioned intent replay
  are implemented at `f50c45c`; cross-process integration serialization is
  implemented at `ea5285b`; focused coordination and workspace suites pass.
- Remaining implementation: complete provider-lifecycle lane close/revocation
  wiring and broaden deterministic integration-gate coverage for contract
  invalidation and unresolved coordination requests.
- Additional evidence: `acd909c` adds replayed WorkGroup/LaneGrant lifecycle;
  `56a3ca5` and `8db7f52` materialize dirty lane snapshots, record claims and
  provenance, and validate snapshot refs at integration; `394c76e` exposes
  work-group joining and grant operations through shared CLI/MCP adapters;
  `58b0f05` passes the isolated two-chat same-provider acceptance fixture;
  `1d6bc71` records explicit lane provenance, excludes provider metadata from
  immutable snapshots, adds release CLI parity, expands MCP collaboration
  schemas, and integrates two independent lane snapshots in a dedicated
  worktree; `7f78c29` runs the integrated candidate's pytest acceptance.
