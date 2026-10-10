# Codebase remaster plan

**Date**: October 10, 2026
**Status**: APPROVED on October 10, 2026. All waves are authorized.
**Base**: `master` at `dc153cf5`. Work branch: `remaster/2026-10-10`.

## Goal and limits

A behavior-preserving cleanup of the Java modules `cli`, `coordination`, `link`,
`mcp`, `mcp-contract`, `project-record`, `relay`, and `workspace`. The targets are
the four in the Design principles section of `AGENTS.md`: good abstractions,
clean patterns, understandable code, and modular APIs with stable contracts.

Published contracts do not change: the MCP tool catalog and every response
shape, the CLI surface, wire formats, anything persisted under `.synesis` or the
admin root, and the public Java API one module exposes to another. A unit that
turns out to need a contract change stops and comes back for a decision.

Out of scope: the Go bootstrap, `web-ui`, the release and protection tasks in
`cli/build.gradle.kts`, `scripts/`, and `docs/` except this plan and docs a unit
makes stale.

## Measured starting point

- 652 main files and 234 test files in scope. Main code is 117,396 lines:
  `workspace` 58.5k, `coordination` 20.0k, `link` 18.8k, `cli` 8.9k,
  `project-record` 4.9k, `mcp` 4.8k, `relay` 0.9k, `mcp-contract` 0.7k.
- Module dependencies and imports match `docs/architecture/package-boundaries.md`.
  No dependency between modules points the wrong way.
- No module is published as a library (no `maven-publish`, no `module-info`).
  The only consumers of a module's public Java API are other modules here.
- Almost no codec has a byte-level test. One form (`WorkIntent` V7) is pinned.
  Every other wire and persisted format has round-trip coverage or none.

## Baseline

`.\gradlew.bat check --dependency-verification=strict` passes on this
workstation only with `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public\s`.
Without it Gradle fails at startup with `Unable to establish loopback connection`
(the SYN-066 workstation issue). The variable is set in the shell, not in the
repository.

| Module | Tests | Failures | Skipped |
| --- | --- | --- | --- |
| `cli` | 85 | 0 | 0 |
| `coordination` | 116 | 0 | 0 |
| `link` | 111 | 0 | 0 |
| `mcp` | 125 | 0 | 0 |
| `mcp-contract` | 15 | 0 | 0 |
| `project-record` | 24 | 0 | 0 |
| `relay` | 4 | 0 | 0 |
| `workspace` | 508 | 0 | 1 |

These counts come from a forced `test --rerun` on October 10 (`BUILD SUCCESSFUL`).
The one skip is `Assumptions.assumeTrue` in `ProviderSessionBindingServiceTest`
for a Codex alias this host does not have. Static gates in `check` also pass:
`formatCheck`, `staticAnalysis`, `javadoc`, `architectureCheck` (`mcp`,
`workspace`), `repositoryHygieneCheck`, and the `web-ui` lint, typecheck, test,
and build tasks.

## How the survey was done

Sixteen read-only Sonnet 5.5 subagents surveyed one slice each. Every finding
below carries one of three evidence marks:

- **R**: I read the code, or ran it, and confirmed the finding.
- **G**: I confirmed the counts and locations with a repository-wide grep.
- **S**: surveyor evidence only. I re-verify at the start of the unit and drop
  the unit if it does not survive.

## Decisions

Resolved by the repository owner on October 10, 2026.

1. **Java API between modules is internal.** Synesis has had no first release
   and no consumer outside this repository, so public Java types and members
   may be narrowed, moved, or removed when every caller here is migrated in the
   same change. ADR-0088 records this. The MCP and CLI surface, wire formats,
   and persisted state stay published contracts. Wave 9 is therefore approved.
   Where a unit below says deprecate, it now removes.
2. **Unwired subsystems stay.** They are neither deleted nor restructured.
   `docs/agent/DEFERRED.md` records them for a separate review.
3. **Defects are fixed.** Each fix is its own commit, states the behavior it
   changes, and carries a test that fails without it. A fix lands after the
   unit that restructures the same code. A suspected defect that cannot be
   reproduced is recorded and left alone.
4. **Gate cadence.** A forced full run of the Java suites took 21 minutes 28
   seconds. Each unit runs its focused tests, the owning module's `check`, and
   the compile of every dependent module. The full `check` runs at the end of
   every wave.

## Unit format and execution rules

Each unit is one responsibility and one commit. For each: the problem, the
intended shape, the files, the contracts it touches or explicitly does not, the
tests that prove behavior is preserved, and the risk.

Rules that hold for every unit:

- A unit that touches an encoder or decoder depends on the pin unit for that
  format. Bytes for valid input stay identical.
- Where two copies of a rule disagree, the unit keeps each site's current
  behavior and names the difference. It does not pick a winner.
- Error codes, reason strings, JSON keys, and key order are treated as wire
  format and are preserved.
- A unit that grows past its responsibility is split and re-planned.

## Wave 0: pin the contracts (tests only)

No production code changes in this wave. Each unit decodes fixed bytes captured
from the unmodified code and asserts the re-encoding is identical.

### P-1 Coordination event and command bytes
- Problem: 12 `CollaborationCodec` forms, `ContractCodec`, and nine payload
  classes have no byte-level test. Evidence: G.
- Shape: one golden-vector test per encoded form, a pin of every
  `PredictionEventType` wire code, a fixed vector for `CoordinationCommand`, and
  an exhaustive test that every event type is classified exactly once.
- Files: `coordination/src/test` only.
- Contracts: none touched. This is the guard for the persisted event log.
- Risk: none.

### P-2 Project-record and relay bytes
- Problem: SDR2, SRP1, PRP1, and the relay SLR1 records have no golden bytes.
  `RecordStatus` and `ResultCode` ordinals are on the wire and unpinned.
- Shape: golden vectors per record kind and an ordinal pin per enum.
- Files: `project-record/src/test`, `relay/src/test`.
- Contracts: none touched. Risk: none.

### P-3 Link protocol and control bytes
- Problem: SYN1, SLO1, SLA2, SLB1, SLB2, the handshake encodings, and the
  READY, GOODBYE, PROTOCOL_ERROR, and ACK control payloads are unpinned.
  `SessionCloseReason`, `HandshakeRole`, and `HandshakeFailureCode` go on the
  wire by ordinal with no test.
- Shape: golden vectors and ordinal pins.
- Files: `link/src/test`.
- Contracts: none touched. Risk: none.

### P-4 Link overlay bytes
- Problem: the eight overlay records and the key-agreement derivation have no
  golden vectors or known-answer test.
- Shape: golden vectors for SLE1, SLF1, SLK1, SLM1, SLP1, SLP2, SLT1 and a
  known-answer test for `deriveSession`.
- Files: `link/src/test`. Needed only if LK-9 is approved.
- Contracts: none touched. Risk: none.

### P-5 MCP catalog and malformed-argument behavior
- Problem: no test pins `tools/list` or the two catalog digests. Several
  malformed-argument replies and the manual-attestation frame are untested.
- Shape: golden `tools/list` JSON and digests, a characterization test per tool
  for malformed arguments, the attestation frame, and a guard that the handler
  field tables equal the schema for the five kinds they share.
- Files: `mcp/src/test`, `mcp-contract/src/test`.
- Contracts: none touched. Risk: none.

### P-6 Workspace persisted forms
- Problem: deterministic id derivations, the Codex control envelope digest, the
  idempotency ledger file, plan `contentHash` inputs, Doctor finding text and
  fingerprints, provider hook files, and the managed `AGENTS.md` section have no
  golden test.
- Shape: one golden test per form.
- Files: `workspace/src/test`.
- Contracts: none touched. Risk: none.

### P-7 CLI coordination command output
- Problem: the ownership, task, prediction, and speculation commands have no
  `cli` tests. Evidence: S.
- Shape: characterization tests for stdout, stderr, and exit code, plus the mode
  handling of the five lifecycle commands.
- Files: `cli/src/test`.
- Contracts: none touched. Risk: none.

## Wave 1: shared foundations

### X-1 One SHA-256 helper in `workspace`
- Problem: `MessageDigest.getInstance("SHA-256")` is written 39 times in 33
  files, with different failure handling. Evidence: G.
- Shape: one helper beside the existing `infrastructure` code. Each site keeps
  its current failure type where a caller can observe it.
- Files: `workspace/infrastructure`, 33 call sites.
- Contracts: none. Digests are identical. `PlanIntegrity` stays public and
  delegates.
- Proof: module suite, P-6. Risk: low.

### X-2 Use `AtomicFiles` for temp-then-move writes in `workspace`
- Problem: `AtomicFiles.replaceFromTemporary` has 2 callers. The same sequence
  is hand-written at about 30 sites in 28 files. Evidence: G.
- Shape: migrate the sites whose sequence is identical. Sites that differ on
  failure cleanup or create-only semantics stay and are listed.
- Files: `workspace` stores and services.
- Contracts: none. Bytes and file names unchanged.
- Proof: module suite. Risk: low to medium. A site that leaves a temp file
  behind on failure today is not migrated without saying so.

### X-3 One Ed25519 verify and one node-id check in `link`
- Problem: Ed25519 verification is implemented 7 times in `link` and again in
  `relay`, `coordination`, and `workspace`. The node-id pattern is spelled in 5
  files across 4 modules. Evidence: G.
- Shape: additive `NodeIdentity.verify(publicKey, message, signature)` and
  `NodeIdentity.isNodeId(String)`. Migrate the `link` copies in this unit. Other
  modules adopt it in their own units.
- Files: `link/identity`, `link/protocol`, `link/candidate`, `link/overlay`.
- Contracts: additive public methods. Signature inputs unchanged.
- Proof: `link` suite, P-3. Risk: low.

### X-4 One way to open the coordination store and signing identity
- Problem: `new PredictionEventStore(location.root().resolve(".synesis/coordination"), ...)`
  appears 59 times in 26 files, and the identity bootstrap 42 times. Evidence: G.
- Shape: `ProjectLocation.coordinationDirectory()` and one small open function.
  No new layer.
- Files: `workspace/application/**`.
- Contracts: none. Directory names unchanged.
- Proof: module suite. Risk: low, wide.

### X-5 Coordination binary field helper
- Problem: `readText`, `writeText`, `readUuid`, and `writeUuid` are copied in 13
  to 14 files with different bounds and empty rules. Evidence: S.
- Shape: one helper that takes the bound, the empty rule, and the message as
  arguments, so each codec states its own limit.
- Files: `coordination/domain/**`. Depends on P-1.
- Contracts: persisted bytes and decode acceptance unchanged per site.
- Proof: P-1, module suite. Risk: medium.

### X-6 Deterministic id derivations in `coordination`
- Problem: the `synesis-review-grant:` id is derived at 5 sites in 2 modules.
  The unresolved-lineage seed is a literal in 4 files. Evidence: G for the first.
- Shape: one static factory per derivation on the type that owns the concept.
- Files: `coordination/domain`, `CoordinationRequestService`,
  `AgentReviewActionProjection`.
- Contracts: additive. Seed strings and resulting ids unchanged, pinned in P-1.
- Risk: low.

### X-7 Copy operations on coordination records
- Problem: `WorkIntent`'s 16-argument constructor is spelled at 8 main sites.
  `Participant` is rebuilt by hand 12 times in one projection.
  `IntegrationAttemptRecord` is rebuilt 4 times. Evidence: G, R for one site.
- Shape: the few copy methods the call sites need. Each site keeps its current
  field choices, including the two that drop the recovery reference.
- Files: `coordination/domain`, 4 `workspace` sites.
- Contracts: additive public methods.
- Proof: `CollaborationProjectionTest`, `WorkIntentServiceTest`,
  `RecoveryContinuationCrashSafetyTest`. Risk: low.

### X-8 Named capability-state and grant predicates
- Problem: capability lifecycle state sets are hand-copied in the projection and
  about 11 `workspace` files, and the copies disagree. "Grant still unresolved"
  is written 4 times. Evidence: S.
- Shape: named predicates on `CapabilityLifecycleState` and one method on
  `WorkGroupProjection`. Each site moves to the predicate that matches its
  current set. Sets that differ stay different, with names that say why.
- Contracts: additive. No projection result changes.
- Proof: `CapabilityNegotiationTest`, `AgentNextActionServiceTest`. Risk: medium.

### X-9 Selector text form owned by `ResourceSelector`
- Problem: the persisted `KIND:value` selector string is written once and
  parsed by hand at 5 sites with three different guards. Evidence: S.
- Shape: additive `encode()` and `parse(String)`. Each caller keeps its
  handling of an invalid entry.
- Contracts: persisted `claimSelectors` unchanged. Risk: low.

## Wave 2: `coordination`

### CO-1 `CollaborationProjection` participant transitions
- Problem: ten transitions copy `Participant` by hand and three repeat the same
  intent removal. Evidence: R.
- Shape: use X-7, add a private `dropIntentsOf`. Then move the request and inbox
  state and the terminal-session map into two package-private holders.
- Contracts: none. Public methods stay. Risk: medium for the second step.

### CO-2 `CollaborationCodec` envelope
- Problem: one encode envelope and one decode envelope are repeated twelve
  times. Eight decoders are public with no caller outside the package.
- Shape: two private helpers that take the magic and the messages. Narrow the
  eight decoders. Depends on P-1 and X-5.
- Contracts: bytes unchanged. Narrowing follows decision 1. Risk: medium.

### CO-3 Projection `validate` uses a copy constructor
- Problem: three or more projections clone their state field by field in
  `validate`. A forgotten field weakens validation silently. Evidence: S.
- Shape: a private copy constructor beside the fields.
- Contracts: none. Risk: low.

### CO-4 `WorkGroupProjection.migrateReviewGrants`
- Problem: reviewer and producer rules are interleaved through three ternaries,
  behind a guard that cannot be true. Evidence: S.
- Shape: one private method per role. Grant id seeds stay byte-identical.
- Proof: `WorkGroupServiceTest`, `McpRecoveryContinuationTest`. Risk: medium.

### CO-5 Locked append in the application services
- Problem: "take the lock, open a fresh store, append" is hand-written at 17
  sites with 6 copies of `freshStore()`. Evidence: G.
- Shape: one package-private helper. `WorkGroupService.withLock` folds into it.
- Contracts: none. Error strings unchanged. Risk: medium.

### CO-6 Review admission rules out of `CoordinationRequestService`
- Problem: the 832-line service mixes review admission policy with request,
  handoff, and inbox appends. Evidence: S.
- Shape: a package-private `ReviewAdmissionRules`, as `LaneCompletionRules`
  already is.
- Contracts: none. Error codes unchanged. Risk: medium.

### CO-7 `PredictionEventStore` log scan
- Problem: `load` and `durableHead` repeat the chain check. The list of seven
  projections is written three times.
- Shape: one private scan and one projection list.
- Contracts: file naming and hash chain unchanged.
- Proof: crash-safety tests in `persistence`. Risk: medium.

### CO-8 Command and event-type encoding
- Problem: `CoordinationCommand` has two copy-paste method pairs.
  `PredictionEventType.wireCode()` is a switch that equals `ordinal()` for every
  constant, and `verify` carries a fallback that cannot run. Evidence: S.
- Shape: one private writer. Codes move into the enum constructor. Depends on
  P-1.
- Contracts: bytes unchanged. Risk: medium.

### CO-9 `TaskCompletionProjection`
- Problem: the "may this snapshot integrate" rule is written three times, the
  attempt record is rebuilt four times, and snapshot ids are looped five times.
- Shape: one private gate that keeps both error codes, X-7 copy methods, and a
  package-private attempt ledger holder.
- Proof: `TaskCompletionTest`, `ReviewedSnapshotLifecycleTest`. Risk: medium.

### CO-10 `CapabilityRequestProjection` handlers
- Problem: ten near-identical `process*` handlers repeat a record lookup and a
  legacy-version fence.
- Shape: `requireRecord(handle)` and `payload.isVersioned()`. Check order stays.
- Risk: low.

### CO-11 HTTP adapter
- Problem: query parsing is duplicated and the SSE line format is split between
  server and client.
- Shape: one private `queryParam` and one package-private frame codec.
- Contracts: HTTP bytes unchanged. Risk: low.

## Wave 3: `project-record` and `relay`

### PR-1 `DecisionStore` trust and chain checks
- Problem: "project matches and signature verifies" is written six times with
  three failure forms. The chain rule is written twice. `readHead` parses its
  own bytes twice. Evidence: S.
- Shape: a private `authentic(record)` and `requireChainLink`. Each site keeps
  its failure type. `recover` and `validateCurrentChain` stay separate.
- Contracts: store layout unchanged. Risk: low to medium.

### PR-2 Reconciliation exchange and inventory
- Problem: the byte-budget round trip is pasted about 20 times in `syncProject`.
  The inventory snapshot and chunk arithmetic are written four times.
- Shape: a private exchange that owns the budget and the ERROR mapping, and one
  inventory value. The one lenient call site and both corrupt-count fallbacks
  stay as they are.
- Contracts: PRP1 bytes and request order unchanged. Depends on P-2.
- Risk: medium. Coverage of error branches is thin, so P-2 adds host-side cases.

### PR-3 Split `ProjectReconciliationSync` along client and host
- Problem: one 936-line file holds the joining side, the hosting side, and a
  store audit.
- Shape: the public class stays as the facade. Two package-private classes take
  the halves. Depends on PR-2.
- Contracts: public members unchanged. Risk: medium.

### PR-4 Store owns its integrity audit
- Problem: sync re-derives the store's directory layout and re-implements the
  chain walk. `DecisionStore.rootPath()` exists only for that.
- Shape: `DecisionStore` exposes the corrupt-record count. The count keeps its
  current meaning. `rootPath()` is deprecated, not removed.
- Contracts: additive. Risk: medium, because the three validators differ.

### PR-5 Protocol message internals
- Problem: the two message classes copy their field helpers. Two entry-list
  loops are identical. Status meanings exist only as bare `0`, `1`, `2`.
- Shape: one package-private field helper in `sync.protocol`, one entry codec,
  named status constants. Depends on P-2.
- Contracts: SRP1 and PRP1 bytes unchanged. Risk: low to medium.

### PR-6 Signer uses the `link` identity rules
- Problem: `Ed25519Signer.deriveNodeId` duplicates `NodeIdentity.deriveNodeId`.
  Evidence: R. `fromEncoded` has no caller.
- Shape: call `NodeIdentity`. Deprecate the duplicate and the dead member. Name
  the scope limits in `DecisionRecord` as constants.
- Contracts: additive and deprecation only. Risk: low.

### RL-1 Relay codec owns its numbers
- Problem: record type and status codes are bare literals in the client and
  server. Signature verification and frame setup are each written twice.
- Shape: package-private constants and helpers on `RelayWireCodec`.
- Contracts: SLR1 bytes unchanged. Depends on P-2. Risk: low.

## Wave 4: `link`

### LK-1 One request and response stream exchange
- Problem: `DemoWorkTransport` and `ApplicationStreamTransport` are the same
  230 lines twice and have drifted. Length-prefixed pipeline setup is repeated
  five times. Evidence: G for the timeout difference.
- Shape: one package-private exchange helper and one framing helper. The demo
  path keeps having no client timeout. That difference is recorded as a defect.
- Contracts: frames unchanged.
- Proof: `NettyQuicLoopbackTest`, binding tests. Risk: medium.

### LK-2 `NettySessionHandshake` entry points
- Problem: 16 public overloads, about 6 unused, and a responder transcript path
  that no caller can reach. Evidence: S.
- Shape: delete the unused overloads and the dead responder plumbing. All
  callers are inside `link`.
- Contracts: none outside `link`. Risk: low.

### LK-3 Control payload codec
- Problem: the READY, GOODBYE, PROTOCOL_ERROR, and ACK layouts are built with
  inline `ByteBuffer` code in six places.
- Shape: one package-private codec, as `HeartbeatMessage` already is. Depends
  on P-3.
- Contracts: bytes unchanged. Risk: medium.

### LK-4 Single-pending datagram exchange
- Problem: `NettyStunBindingTransport` and `NettyTraversalExchange` repeat the
  same pending-slot, timeout, and close skeleton.
- Shape: one package-private base. Each keeps its match rule and its current
  cancellation behavior.
- Proof: the four STUN and traversal tests, including real sockets. Risk: low
  to medium.

### LK-5 Handshake stream handler
- Problem: `channelRead0` mixes stream routing, the duplicate-control guard,
  negotiation, admission, and the proof exchange. Inbound routing exists twice.
- Shape: one routing helper and one validation method. The admission release
  logic does not change.
- Risk: medium. Security path.

### LK-6 `Onboarding` owns its endpoint once
- Problem: the network resource bundle is set up by hand six times and two
  flows bypass `EndpointCleanup`. The candidate race and transcript are copied
  three times. Evidence: S.
- Shape: one package-private endpoint owner and one private connect method.
  Event order and failure codes stay exactly as they are.
- Contracts: public `PreparedHost` and `PreparedJoin` unchanged.
- Proof: `OnboardingTest`, `HumanMediatedLinkTest`. Risk: medium.

### LK-7 Protocol field and link helpers
- Problem: bounded-field and signature-envelope code is repeated across five
  protocol classes. Share-link parsing is written four times.
- Shape: two package-private helpers in `protocol`. Depends on P-3.
- Contracts: bytes and accepted link grammar unchanged. Risk: medium.

### LK-8 Small duplicates in `candidate` and `overlay`
- Problem: `TraversalCoordinator.accept` and `acceptAsAnswerer` are one check
  with sides swapped. `isNodeId` is written three times. `OverlayForwarder`
  repeats its duplicate guard three times. `StunBindingMessage` has two dead
  clauses.
- Shape: private helpers. The "recorded before send" rule is written down.
- Contracts: none. Risk: low.

### LK-9 Overlay record codecs
- Problem: eight records hand-copy the canonical decode skeleton. Membership
  signatures are re-verified at every consumer.
- Shape: two helpers in `OverlayCodecSupport`, a memoised verify on the
  immutable snapshot. Depends on P-4.
- Contracts: bytes and the fail-closed rule unchanged.
- Risk: medium. Three of the larger overlay classes have no production caller,
  so this unit runs only if decision 2 says to invest there.

## Wave 5: `mcp`

### MC-1 Remove five unreachable coordination operations
- Problem: `bind`, `request`, `lane_grant_issue`, `lane_grant_revoke`, and
  `work_group_close` cannot be reached from the published schema. Evidence: R.
- Shape: delete the five branches.
- Contracts: none. The schema never produced them. Risk: low.

### MC-2 Typed coordination operation
- Problem: `request_coordination` is normalized into flat `collaboration*` keys
  that a second method re-parses with casts.
- Shape: the normalizer returns a typed value per operation. Depends on P-5.
- Contracts: every error string preserved. Risk: medium to high.

### MC-3 `handleToolsCall` gates
- Problem: one method runs seven ordered gates, and per-tool policy is spelled
  in four places behind a prefix that carries no information.
- Shape: named private steps in the same order and one per-tool policy value.
  The `Unknown tool: synesis.<name>` text stays.
- Proof: the handler suites and P-5. Risk: medium. Authority order matters.

### MC-4 Argument reading and reply helpers
- Problem: the validation selector is parsed twice, the selector wire names are
  mapped in five places, `BLOCKED, POLICY_DENIED` is built at 14 sites, and
  three frames bypass `McpJsonRpcResponses`.
- Shape: shared readers and private builders. No reply changes.
- Risk: low.

### MC-5 `ensureSession` phases
- Problem: one 130-line method runs five phases and lets later phases overwrite
  earlier responses.
- Shape: three private methods. The current precedence is kept and stated.
- Risk: medium.

### MC-6 Handler construction
- Problem: seven constructors, three unused, plus a flag that duplicates
  `managedAttachment != null`. Connection identity is threaded through seven
  classes by hand. Process identity capture exists twice.
- Shape: delete the unused constructors, derive the flag, add one
  package-private connection record.
- Contracts: none outside `mcp`. Risk: low.

### MC-7 Catalog construction
- Problem: `buildDescriptors` is 245 lines of repeated schema fragments, and
  `constrainPayloadKind` re-reads its own earlier output.
- Shape: one private method per tool and fragment helpers. Depends on P-5.
- Contracts: `tools/list` and both digests byte-identical. Risk: medium.

### MC-8 Unpinned root selection returns a result type
- Problem: one method carries two policies and a return value with three
  meanings.
- Shape: the unpinned path returns the same `Selection` the pinned path does.
- Risk: high. Root authority. Runs last in the wave with its own pins, or is
  dropped on request.

## Wave 6: `workspace`

### Agent workflow

- **WS-1 Review and capability rules in their own homes.** A projection calls
  back into `AgentTaskCompletionService` for `reviewRequired`, and three
  capability rules sit in the review projection. Move both within the package.
  All members are package-private. Risk: low.
- **WS-2 Finish tail in `AgentTaskCompletionService`.** The terminal-seal and
  continuation tail appears three times. One private method, with each site's
  exception behavior kept. Also the evidence checks become methods on
  `CompleteTaskRequest`. Risk: low to medium.
- **WS-3 Caller admission.** The same prologue and failure mapping is pasted
  across the completion, cancel, and two capability services. One
  package-private admission value. Lock ordering differences stay. Risk: medium.
- **WS-4 Shared wire keys.** `NextActionKeys` exists but two classes still use
  about 40 raw literals. Replace with the constants. Risk: low.
- **WS-5 Chain and predecessor helpers.** Three chain walks and four
  rejected-predecessor checks with differing filters. Named helpers, one per
  current variant. Risk: medium to high. Authority gates.
- **WS-6 Session admission failures.** `AgentSessionService` passes causes as
  exception message text and decodes them by substring. A typed code, same
  responses. Risk: low to medium.

### Collaboration, integration, and tasks

- **WS-7 Snapshot ref names.** `refs/synesis/snapshots/` is composed at 8 sites
  in 6 files (evidence: G). One holder. Equality and prefix checks stay per
  site. Risk: low.
- **WS-8 Lane identities.** About ten deterministic id formulas are scattered,
  one of them copied three times. One class. Depends on P-6. Risk: medium.
- **WS-9 Collaboration service delegates.** Eight methods are the same four
  lines. One private `withSession`. Depends on X-4. Risk: low.
- **WS-10 Command outcome mapping.** The same outcome-to-response switch exists
  three times. One mapping. The differing `error` and `reason` keys stay.
  Risk: low.
- **WS-11 Disposable worktrees.** Root resolution, project id lookup, and the
  add, verify, remove sequence are written three times with four fallbacks.
  One package-private helper, fallbacks kept. Risk: medium.
- **WS-12 Control branch finalization.** The normal path inlines the event
  sequence the recovery path already has. Call one from the other. Event order
  is persisted, so this needs a new direct test first. Risk: medium.
- **WS-13 Integration candidate policy.** A 265-line method interleaves
  candidate policy with attempt lifecycle. Extract a package-private policy
  class. Lock order untouched. Risk: medium.
- **WS-14 Changed-path partition.** One rule in three formulations. One
  package-private function. Persisted `changedPaths` unchanged. Risk: medium.
- **WS-15 `TaskSnapshotService` overloads.** Nine `createSnapshot` overloads,
  two main callers, both on the longest (evidence: G). Migrate tests, then
  deprecate or delete under decision 1. Risk: low.
- **WS-16 Git wrappers.** Six classes define their own `runGit` helpers. One
  set. Probe semantics stay. Risk: low.

### Project, read, and mutation

- **WS-17 Workspace path policy.** The protected-target rule is copied in
  three places plus two callers (evidence: G), and the copies disagree. One
  policy class, each site's current answer kept. Risk: medium. Safety boundary.
- **WS-18 Mutation broker rejections.** A 175-line chain of about 25 returns,
  each a 9-argument call. A private rejection value and staged checks. Decision
  names and reason codes are persisted and stay. Risk: medium to high.
- **WS-19 `ProjectApplicationService` internals.** The facade also holds the
  `project.json` codec, the `AGENTS.md` merger, and Git probing. Extract three
  package-private types into `application.project`. The facade keeps its
  methods. Depends on P-6. Risk: medium.
- **WS-20 Command argument validation.** Argv and working-directory checks are
  written three times. One owner. Outcomes unchanged. Risk: low to medium.

### Providers

- **WS-21 Provider differences as capabilities.** `"codex".equals(...)` appears
  13 times in the application layer (evidence: G). Default methods on
  `ProviderIntegration`. Output keys unchanged. Risk: medium.
- **WS-22 Binding copies and paths.** `Binding`'s 24-component constructor is
  spelled nine times and `ManagedAttachmentRecord`'s eight. Copy helpers, one
  path helper, and `ensure` split into named steps. Risk: medium.
- **WS-23 Binding status.** Status is a string whose "inactive" sets disagree
  between classes. An enum parsed at the file boundary, unknown values
  tolerated, each site's set kept. Persisted strings unchanged. Risk: medium.
- **WS-24 Hook files and adapters.** Hook-entry merge exists in four variants.
  One merge, bytes preserved per write path. Depends on P-6. Risk: medium.
- **WS-25 Provider metadata and dead members.** The metadata file is spelled
  twice. Four members have no caller in any module. One record, deprecations.
  Risk: low.

### Codex lifecycle

- **WS-26 `Checkpoint` derive operations.** The 17-argument copy is written
  out at each transition. Record methods. Persisted JSON unchanged. Risk: low
  to medium.
- **WS-27 Attachment startup.** `start()` and `resumeAttachment()` repeat the
  bootstrap and failure cleanup, and journal close is repeated six times. One
  private open method with the failure state as a parameter. Risk: medium.
- **WS-28 Service shape.** After WS-26 and WS-27, move the protocol listener to
  a package-private class and delete the executor that is never submitted to
  (evidence: R). Risk: medium.
- **WS-29 Host authority checks.** `ProjectRuntimeHost` mixes admission,
  authority verification, and status assembly. Extract a package-private
  verifier. Pure extraction. Risk: low if nothing else moves.
- **WS-30 Envelope and ledger codecs.** Authority fields are listed twice by
  hand. The ledger format is written in one class and read in another. One
  place each. Depends on P-6. Risk: medium.
- **WS-31 Protocol client and terminator.** Extract request correlation. Give
  `ProcessTreeTerminator.terminate` its two repeated phases as methods.
  Diagnostic strings unchanged. Risk: medium.

### Lifecycle

- **WS-32 Plan store.** Three plan stores share create, hash, and load.
  One helper beside `PlanIntegrity`. Keys, order, and hash input stay
  byte-identical. Depends on P-6. Risk: medium.
- **WS-33 Admin layout and journals.** The admin directory is built by hand in
  about 12 places and two journals share identical readers. One layout class
  and one journal helper. Repair keeps its own key names. Risk: low to medium.
- **WS-34 `CleanupExecutionService.executePlan`.** A 370-line method repeats
  one record-and-journal block 16 times. A private outcome value. Journal
  content unchanged. Risk: medium.
- **WS-35 Reconciliation and lease helpers.** Two copy-pasted discovery
  branches and repeated lease evidence rules. Private helpers. Enum values
  stay, because persisted plans are read with `valueOf`. Risk: low.
- **WS-36 Durable command objects.** Read, verify, and write is copied six
  ways. Two operations on `CommandDurableFormat`. Bytes unchanged. Risk: medium.
- **WS-37 Baseline journal.** `ManagedBaselineTransactionService` mixes
  orchestration with journal format and IO. A package-private journal store.
  Phase names unchanged. Risk: medium.
- **WS-38 Git runner surface.** Unused overloads and callers that re-wrap its
  result. Trim and use the result directly. Risk: low.

### Control plane, Doctor, and migration

- **WS-39 Doctor findings.** The 10-argument `DoctorFinding` is built about 25
  times. One private builder over a table. Text and fingerprints byte-identical.
  Depends on P-6. Risk: medium.
- **WS-40 Migration journal.** Admin paths and journal line formats are derived
  in five places. One helper. Also remove an unreachable branch and three dead
  private helpers in `ProviderConfigMigrationService`. Risk: low to medium.
- **WS-41 Control plane read model.** The model also mutates the registry and
  holds DTO-to-JSON mapping away from the DTOs. Move the mutators to the
  endpoints and the mapping to the DTOs. JSON unchanged. Risk: medium.
- **WS-42 Onboarding endpoints.** Three near-identical begin, try, finish
  blocks. One private method. Risk: low.

## Wave 7: `cli`

No other module references `org.synesis.cli`, so its Java surface is internal.
The contract here is command names, options, output text, and exit codes.

- **CL-1 Sign-and-submit pipeline.** About 17 commands copy the same
  resolve, sign, submit, print, and catch sequence. One helper in the
  coordination support class. Depends on P-7. Risk: medium.
- **CL-2 Project resolution.** Written about 18 times with real differences.
  Two entry points that keep each site's option type and error. Risk: medium.
- **CL-3 Speculation metadata.** `speculation.meta` is parsed three times in
  `cli` and written in `coordination`. An additive reader beside the writer.
  Each site keeps its error text. Risk: low.
- **CL-4 Daemon runtime process.** Lift the process handle out of the 857-line
  `DaemonServer` and build the child JVM command once. Remove the conditional
  whose two arms are the same string (evidence: R). IPC unchanged. Risk: medium.
- **CL-5 Lifecycle mode commands.** Five commands share one mode skeleton.
  Mode selection moves into the existing `CommandModePolicy`. Divergent
  handling stays. Risk: medium.
- **CL-6 Mutate idempotency cache.** A 90-line persisted cache lives inside a
  command. Extract a package-private class. File format unchanged. Risk: low to
  medium.
- **CL-7 Dead code.** `ReadinessInspector` and `ReadinessReport` have no
  production caller. Unused daemon overloads and three forwarders. Remove.
  Risk: low.
- **CL-8 Ready line.** The `COORDINATION_SERVE_READY` key names are spelled by
  the printer and again by the parser. One record. Bytes unchanged. Risk: low.

## Wave 8: packages and visibility inside a module

These moves touch no type that another module imports.

- **PK-1 `cli` packages.** `daemon` and `command.coordination` import each
  other and `CliPackageArchitectureTest` hard-codes the exception. Move the
  browser launcher and the shared support to neutral packages. Put the
  onboarding commands in their own family.
- **PK-2 `link` cycles.** `TraversalCoordinator` moves to `protocol` and
  `InvitationAdmission` to `session`. Both are imported only inside `link`.
- **PK-3 `workspace` cycles.** The `lifecycle.command` to `application.project`
  back-edge is one constant. `CodexHookAdapter` and `CodexNativePatchRouter`
  move beside their only caller. The shared `Response` leaves the HTTP client
  that is never instantiated. The durable-state classifier leaves `doctor`.
- **PK-4 Codex sub-packages.** Process-tree and wake-admission classes are
  already public and can leave the core package, which the boundaries doc says
  they cannot. Update `package-boundaries.md`.
- **PK-5 Visibility pass.** Per module, `public` types and members with no
  caller outside their package become package-private. About 40 candidates are
  recorded in the survey. Update `package-boundaries.md` where it lists them.

Each unit updates the architecture tests and the boundaries doc it makes stale.
Risk: low, mechanical, wide diffs.

## Wave 9: moves that change cross-module names

- **PK-6 Split `coordination.domain.integration`.** Three of its five types
  describe capability implementation and two describe task integration. Seven
  `workspace` main files and two test files import the package.
- **PK-7 `transport.control` and `lifecycle` root regrouping** in `workspace`,
  where `cli` or `mcp` import the moved types.

Every importer is in this repository and is migrated in the same commit.
ADR-0088 covers the rename.

## Recorded, not planned

**Unwired subsystems** (no production construction site, kept by decision 2):
`OverlayReconciler`, `OverlayKeyAgreement`, and `OverlayEnvelope` in `link`;
`OverlayRelayClient` in `relay`; Codex managed continuity
(`ManagedCodexProcessLauncher`, `lifecycle.codex.runtime`,
`WindowsJobObjectProcessTreeSupervisor`, most of `ManagedAttachmentService`);
project migration backup and restore; `ManagedBaselineTransactionService.recover`;
`ProjectCommandMaintenanceService.cleanupDeadAnchor`; the coordination ownership
registry; the legacy SYN1 and SLO1 host flows in `Onboarding`.

**Suspected defects.** Confirmed means I read or ran the code. Decision 3
governs which are fixed.

1. Confirmed, ran the built CLI: `synesis collaboration contract publish`,
   `bind`, and `status` fail with a Picocli `InitializationException`. The three
   classes are registered by annotation and have no no-argument constructor.
2. Confirmed by reading: a contract body under 32,768 characters but over
   32,768 UTF-8 bytes leaves the contract superseded with no new revision.
3. Confirmed by reading: the manual-attestation predicate for
   `request_coordination` is true for every call, though its comment says
   status is a safe read. It fails closed.
4. Confirmed constants, consequence not run: `DecisionStore` caps a record at
   64 revisions, sync allows 100.
5. `DemoWorkTransport`'s client has no timeout and production onboarding uses it.
6. Handshake admission release is not tied to the connection that reserved it.
7. `CoordinationService.submit` appends outside the project lock on a
   long-lived store that the control plane reads.
8. Membership fan-out stops at the first failed send, topology continues.
9. `CandidateRacer` may leak a session when completion races a deadline.
10. Repair never compares the saved target fingerprint, and one action journals
    "archived" for a directory it does not move.
11. The cleanup execution lock blocks forever if its holder dies.
12. `apply_patch` and `cancel_lane` can turn a wrong argument type into
    JSON-RPC `-32603`.
13. The readiness preview reports a stale base that integration would accept.
14. `PredictionProjection.snapshot()` can throw on stored `null` states.
15. `SpeculationWorkspace` may deadlock on a full child pipe.
16. `verifyWorkspaceTrust` and `verifyWorkspace` check the same things in a
    different order with different codes.
17. Several decoders disagree on trailing bytes and one has an unbounded count.

Items 5 to 17 come from reading by a surveyor and have not been reproduced.

**Out-of-scope observations.** `docs/development/current-state.md` is dated
August 30 and lists seven Java modules, omitting `relay`. `docs/current-state.md`
does not exist. `McpPackageArchitectureTest` filters on a package that no longer
exists.

## Order and size

Waves run in the order above. Within a wave, units that others depend on come
first. Wave 0 has 7 units, wave 1 has 9, and waves 2 to 8 have 90 between them.
Each unit is briefed to one Sonnet 5.5 subagent at medium effort, reviewed in
full by the lead, gated, and committed on its own.
