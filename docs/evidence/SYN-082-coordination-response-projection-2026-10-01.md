# SYN-082 coordination response projection — 2026-10-01

Scope: diagnose the old Stage 4 specimen once, repair the generic public action
projection, test and package the source, and freeze the specimen. A fresh final
acceptance project belongs to the next task.

## Repository and specimen

- Source branch: `master`.
- Source HEAD: `813b1f1c5c3572d895444850b1e22ad1f4cc114b`.
- Dirty paths: **155 before**, **157 after**. The new dirty paths are the
  architecture document and this evidence document; other touched files were
  already dirty, including the catalog test.
- Source `.synesis`: absent before editing; no Synesis MCP was used in the source
  checkout. Ordinary source tools and disposable test fixtures were used.
- Specimen: `C:\t\SYN082-ReviewerLoss-Stage4-20260930-2`.
- ProjectId: `476f84f5-5043-4e37-86c6-2fa61b950278`.
- WorkGroupId: `85cb2453-0473-4320-9a0b-d63e45dbcd5d`.
- Historically accepted candidate: `snap_e9960a505f83ed411d3381023de90fa3`.

## Bounded old-target observation

The existing owner chat, **Reply READY**
(`01a0f22c-9463-7f63-bb5d-2febc3ff91d5`), received one prompt authorizing exactly
one read-only `get_next_action({})`. No other Synesis call was authorized in that
probe. Its fresh result was reported as:

| Field | Reported value |
| --- | --- |
| status / reason | `retry_required` / `session_not_ready` |
| workflow | `RECOVER` |
| actionId | `f8a53506-a58f-30fa-a714-db361c1a9356` |
| blocker | `session_not_ready` |
| permitted operation / recommendedTool | `ensure_session` |
| arguments | `{}` |

Recovery was not executed. The fresh probe did not expose WorkGroup, candidate,
request, participant, or intent state. Therefore it cannot reverify that the
WorkGroup is still ACTIVE, the candidate still REVIEW_ACCEPTED, or the old
request still pending.

Immediately preceding chat history reported the original dead-end:
`blocked` / `owner_request_pending`, `pending: 2`, request
`87056b36-d967-391e-9a9e-58147fc1b9cd`, actionId
`1ae0eafe-3756-32a9-bfad-8e397d8b4f7e`, workflow `REVIEW_CONTRACT`, recommended
`respond_coordination`, arguments `{}`. App retrieval exposed call markers and
the agent's result summaries, rather than the complete raw MCP envelope. These
are reported historical fields, not a manufactured exact raw response.

The specimen was frozen after this probe. No response was guessed, no request
was cleared, no target state was edited, and no repaired build was installed.

## Source reproduction and root cause

The deterministic fixture reproduced the full malformed public JSON before the
production patch. Its exact response is retained locally in
`build/syn082-projection-20261001/source-before-projection.json` and the failing
JUnit XML under `focused-before-results`.

1. `IntegrationOrchestrationService.orchestrateIntegrationLocked` checks all
   live PENDING coordination requests before allocating an integration attempt.
   It has the project signing identity, but no authenticated caller participant.
2. Its old response was BLOCKED / OWNER_REQUEST_PENDING / RESPOND_COORDINATION,
   containing only the total pending count and the first request ID.
3. `AgentNextActionService.resolveNextAction` first handles the exact caller's
   rich owner inbox. If the caller owns no pending request and has no higher
   priority review/continuation action, it returns the integration pump response.
4. `AgentWorkflowReducer.executableAction` sees RESPOND_COORDINATION without
   `nextProtocolKind` and `nextProtocolPayload`, and projects an interactive
   `respond_coordination` action with empty arguments.
5. The reducer's workflow payload faithfully copies the sparse pump result.
   `AgentResponse.toJson` and `McpProtocolHandler` serialize it without dropping
   richer fields. The missing context originated in the pump response.

The fixture proves that a **non-owner** can receive exactly the reported
`pending: 2` / single request / empty response arguments shape. The owner in the
same fixture receives both complete request records. This establishes the
current source defect; it does not establish binary provenance or the exact
participant binding of the historical installed specimen.

The defect is not restricted to CONTRACT or post-ACCEPT state. The integration
fence filters by PENDING and live participants, without a request-kind or
REVIEW_ACCEPTED condition. Two requests are counted, but only one arbitrary
request ID was returned. Neither that ID nor the count proves the caller's
authority to answer it.

## Existing lawful response contract

`respond_coordination` requires top-level `kind` and `payload`. Its advertised
kinds remain `capability_response`, `coordination_response`, `inbox_acknowledge`,
`inbox_resolve`, `implementation_validation`, and `review_validation`.

For `coordination_response`, the strict adapter requires
`payload.coordinationRequest` (UUID) and `payload.coordinationStatus`; proposal
is optional. Advertised statuses are ACCEPTED, REVISED, REJECTED, CANCELLED,
and COMPLETED. The advertised generic payload has no unconditional required
fields; kind-specific requirements are enforced by the adapter.
The complete compiled catalog schema is retained locally as
`build/syn082-projection-20261001/respond-coordination-schema.json`.

The exact owner's existing `pendingCoordination` projection includes every
request's requestId/inboxItemId, requester, target, conflicting intent, kind,
proposal, status, optional snapshot, and enriched target intent/WorkGroup/epoch.
The surrounding collaboration projection also exposes participant and intent
context. Those records plus the public response schema make CONTRACT responses
discoverable. Empty recommended arguments remain valid for that owner decision.
Synesis does not choose a CONTRACT response status.

Review admission uses `coordination_response`; the independent review decision
uses `review_validation` with exact grant, snapshot, intent, epoch, and an explicit
reviewer result. Admission is not an ACCEPT decision on a candidate.

## Repair and authority implications

The project-wide integration fence now returns WAITING / INTEGRATION_PENDING /
WAIT with the pending count. It no longer selects a request or assigns a
response obligation. The participant-bound owner inbox retains responsibility
for projecting actionable requests. This correction belongs in the integration
pump because that is where actor-specific guidance was incorrectly created.

The fence remains before attempt allocation under the existing project append
lock. Polling appends no coordination response or integration attempt while
these requests are unresolved. Fresh polls reload durable state. Mutating
responses still require the exact request target, reload under the append lock,
reject stale conflicting resolutions and terminal authority, and preserve
existing same-status retry behavior. No grant, claim, review decision, recovery,
or replay authority rule was changed.

Source changes in this task:

- [IntegrationOrchestrationService.java](../../workspace/src/main/java/org/synesis/workspace/application/integration/IntegrationOrchestrationService.java):
  replace the sparse actor-specific response with the existing integration WAIT.
- [IntegrationOrchestrationServiceTest.java](../../workspace/src/test/java/org/synesis/workspace/application/integration/IntegrationOrchestrationServiceTest.java):
  strengthen the fence test to check WAIT, no arbitrary request, and no append.
- [AgentNextActionServiceTest.java](../../workspace/src/test/java/org/synesis/workspace/AgentNextActionServiceTest.java):
  add two CONTRACT requests and verify owner context, interactive judgment,
  non-owner WAIT, public JSON preservation, and no durable response from polling.
- [McpToolCatalogTest.java](../../mcp-contract/src/test/java/org/synesis/mcp/contract/McpToolCatalogTest.java):
  verify required response envelope and distinct coordination/review choices.
- `docs/architecture/autonomous-coordination-lifecycle.md` and
  `docs/agent/TEST_MATRIX.md`: record the boundary and validation evidence.
- This evidence document.

Preexisting dirty edits were preserved. Before-edit copies of touched existing
files are retained locally under `build/syn082-projection-20261001`; the task's
production delta is one response block, not the complete dirty diff from HEAD.

## Validation and package

Focused red/green: the corrected fixture failed before the production patch
with the exact malformed non-owner response; both focused tests pass after it.
The first launch failed before compilation on the host Java loopback setup;
the interrupted retry produced no completed test evidence. Process-local
TEMP/TMP and `jdk.net.unixdomain.tmpdir` settings enabled subsequent runs.
Test fixture compile/constructor mistakes were corrected before the recorded
source red/green run.

The relevant workspace run passes **31 tests** across next-action, reducer, and
integration classes. The fresh focused coordination run passes **32 tests**
across WorkIntentService and recovery continuation crash boundaries.

| Check | Result | Evidence |
| --- | --- | --- |
| New focused red/green | PASS | Two focused tests; pre-fix malformed JSON captured, post-fix both pass |
| AgentNextActionServiceTest | PASS | 22 tests |
| AgentWorkflowReducerTest | PASS | 7 tests, including explicit review decision choices |
| IntegrationOrchestrationServiceTest | PASS | 2 tests; unresolved requests append no attempt |
| Full coordination suite, forced fresh execution | PASS | 102 tests, 20 classes, no failures/errors/skips |
| MCP review/recovery and catalog | PASS | 43 tests, 7 classes, no failures/errors/skips |
| mcp-contract catalog suite | PASS | 11 checks; exact ten raw names and response choices |
| Full workspace suite | FAIL | 445 tests, 1 failure, no errors/skips |
| Formatting and strict compiler diagnostics | PASS | workspace, coordination, mcp, mcp-contract |
| Architecture tasks | PASS | workspace and mcp; workspace/coordination architecture tests also ran in full suites |
| Repository hygiene | PASS | 38 maintained Markdown files checked |
| MCP / coordination / mcp-contract Javadocs | PASS | MCP executed; others up to date |
| Workspace Javadocs | FAIL | Two previously documented warnings become an error under Werror |
| git diff --check | PASS | No whitespace errors |

The broad workspace failure is
`ReviewAdmissionOrderIndependenceTest.reviewerFirstWaitsForProducerBeforeNoChangeCompletion`,
line 308: expected `NO_CHANGE_REVIEWER_PENDING`, received
`NO_CHANGE_NOT_DECLARED`. `NoChangeCompletionEligibility.assess` checks the
acceptance declaration before the unresolved review obligation. Both status
and public reason assertions pass; the specific denial reason differs. This
fixture has not created a producer snapshot or pending coordination request at
that assertion, and the changed integration fence is not reached. The existing
precondition ordering and fixture were left unchanged.

The unchanged workspace Javadoc warnings are `ProviderManualService.java:26`
(MANUAL_NAME has no comment) and `ProviderSessionBindingService.java:576`
(missing throws documentation). The gates were run with `--continue`, so the
other listed checks completed despite that failure.

All runs used strict dependency verification. Logs, preserved fresh JUnit
results, and before-edit file copies are under
`build/syn082-projection-20261001`. The broad combined run initially restored
the full coordination results from cache; a separate `:coordination:test --rerun`
then executed all 102 tests freshly. The entire MCP suite was not run; the seven
relevant classes above were run. No incomplete run is counted as PASS.

## Unique package and payload provenance

Version: `0.1.0-syn082-projection-20261001-025045`.

Archive:
`C:\Users\Liparakis\Desktop\Synesis\cli\build\distributions\synesis-0.1.0-syn082-projection-20261001-025045-windows-x64.zip`.

Bundle:
`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-syn082-projection-20261001-025045-windows-x64`.

Both output paths were verified absent before `:cli:platformZip`. Packaging
passed in 16 seconds. The bundled CLI version smoke ran from `C:\t` and returned
the dedicated version, BUILD_COMMIT `813b1f1c5c3572d895444850b1e22ad1f4cc114b`,
BUILD_PLATFORM `windows-x64`, and BUILD_TIME `2026-10-01T02:50:51.565188400Z`.

SHA-256 values:

| Payload | SHA-256 |
| --- | --- |
| ZIP | `4C0FA8C6534E9A45E1504467D33F80DA3B14879FF3E95FEEA85D1E2C386F086D` |
| Native bin/synesis-mcp.exe | `EC4407FDD92E3469723A698828DBD5A5523076D217D8D0C966B0CC72B9207048` |
| app/synesis-cli.jar | `8DF6293E6923F2FB1FEAA5913A79EA7695B67D7061A724ECD62094BC58E3BCCD` |
| app/lib/workspace-0.1.0-SNAPSHOT.jar | `F927E08E680DE742D955087D8E5873F144878D2D17ED9E1B74575CBDBC3592A0` |
| IntegrationOrchestrationService.class | `8111F5B6A36C56920C50C2A6842C94CBFCA99DF5E00EE33B4211CC54889DFA31` |
| IntegrationOrchestrationService$CandidateResolution.class | `AE0E10EF9A308F045510D1D911F465C90BD1CB08273EF8AD6A159EB1163B34E8` |
| Source input manifest | `7ADEFCA8AA8193084A62363567399644199E127FAD69FF308ED9E10330275759` |

The build JAR, packaged JAR, and JAR inside the ZIP have identical hashes. Each
listed class inside the packaged JAR matches the compiled class bytes used by
the tests. The source input manifest covers the current dirty module/build/native
inputs and is identical before and after packaging. Full metadata is retained
locally in `build/syn082-projection-20261001/provenance.json`.

This is a build of the existing dirty checkout, including its preexisting
production changes. HEAD alone does not identify it. No old named distribution
was overwritten; no global installation or final acceptance run was performed.

## Verdicts

The specimen and source fixture are distinct evidence. FAIL for fresh specimen
confirmation means the requested state was not exposed; it does not assert
that the candidate or request changed state.

| Area | Check | Verdict | Boundary |
| --- | --- | --- | --- |
| SOURCE REPRODUCTION | REVIEW_ACCEPTED still present | FAIL | Fresh probe returned session_not_ready; historical acceptance remains reported evidence |
| SOURCE REPRODUCTION | REVIEW_CONTRACT dead-end reproduced | PASS | Deterministic pre-fix source fixture; fresh specimen probe did not repeat it |
| SOURCE REPRODUCTION | respond_coordination projected | PASS | Pre-fix fixture and preceding chat history |
| SOURCE REPRODUCTION | Projected response executable/discoverable | FAIL | Pre-fix fixture exposes only count and an unowned first request ID |
| ROOT CAUSE | Projection owner identified | PASS | Actor-unbound integration pump emits the sparse response |
| ROOT CAUSE | Missing context reason identified | PASS | Sparse pump response bypasses the exact caller's rich inbox |
| ROOT CAUSE | Multiple-pending ambiguity understood | PASS | All requests counted, first request selected without caller authority |
| SOURCE FIX | Actionability invariant fixed | PASS | Non-owner WAIT; owner gets every complete request |
| SOURCE FIX | Interactive agent judgment preserved | PASS | CONTRACT arguments remain empty with discoverable context; no chosen status |
| SOURCE FIX | Exactly ten tools | PASS | Both catalog layers tested |
| TESTS | Focused regression | PASS | Two source tests red/green |
| TESTS | Next-action tests | PASS | 22 tests |
| TESTS | Coordination tests | PASS | 102 fresh tests |
| TESTS | MCP review/recovery regression | PASS | 43 relevant MCP tests |
| TESTS | Broader relevant gates | PARTIAL | Full workspace denial-reason mismatch and known Javadoc warnings remain |
| PACKAGE | Fresh identifiable package | PASS | Dedicated version and unique paths |
| PACKAGE | Payload provenance | PASS | Compiled class, build JAR, bundle JAR, ZIP JAR and source manifest checked |
| OLD TARGET | Frozen after diagnosis | PASS | No later target calls, install, response, state repair, or final-proof attempt |

Final source state: branch/HEAD unchanged, 157 dirty paths, source `.synesis`
absent, zero commits and no push. The old target was not manually repaired and
was not used to prove the repaired package. No fresh final acceptance target was
started. Source projection semantics are proven; real current-build end-to-end
acceptance remains unproven.
The user's final steering names **FortniteGamingSeven** if a new project is
needed. No new project was needed or started in this source repair task.

## Configuration and remaining limits

No command in this task edited global Codex configuration. The first observed
config hash was `171174F4004FD6E1D2026D28A6388ABD95C6D9DE227B15DBB3255FA53BAFE69E`;
a later observation was
`FD63DCC1CCCEE31D2454116F085EF73FD0847B9BC7E351CB5A4B3D993ED4A985`.
This differs from both historical hashes in the request. The cause of the drift
was not attributed. Test provider configuration uses isolated home directories.
No unknown historical bytes were reconstructed or restored.
The last observation during final checks was
`7F45EBCAA651B6E0AFAFB48FE6CBA7A586F6D5F18C243995934DD515A51C438F`.
The file continued changing after test execution had ended. This records an
observation, not a claim that an actively edited external config stayed stable.

No subagents were spawned. No commit or push was authorized or performed.
Pending requests may still keep integration waiting when their exact owner is
unavailable. This repair proves lawful projection semantics, not reachability
or terminal convergence of the old specimen. A clean current-build final
acceptance remains for the next task.
