# SYN-082 overnight successor-authority acceptance evidence

Date: 2026-09-19

## Scope and boundary

This record covers the user-authorized overnight extension of SYN-082:

1. deterministic process-crash successor prefixes;
2. blind concurrent multi-lane recovery with abrupt participant loss; and
3. mandatory independent review when a producer or reviewer is lost.

The source checkout was `C:\Users\Liparakis\Desktop\Synesis`. Source work
used ordinary local Git/filesystem authority only. No Synesis coordination or
MCP was used against the source checkout, no source `.synesis` state exists,
and no source commit or push was made.

## Latest source-side repair — reviewer-owned evidence publication

The focused review fixture exposed one narrower downstream omission: an
independent reviewer could record an accepted decision and write its claimed
`review-report.md`, but the server projected no lawful `finish_lane` publication
for the reviewer because snapshot publication was producer-role-only. The
repair reuses the existing role-neutral snapshot/integration path. Participant
claims now include all active claims owned by that participant, while the
foreign-claim fence and the selected lane's own selectors still scope the
snapshot. A reviewer can therefore publish only its own claimed report/test
artifact; it cannot absorb the producer's source paths.

The focused MCP boundary test
`McpSyn039SliceTest.reviewerOwnedArtifactCompletesThroughPublishIntegrationAndGroupClosure`
now passes. It records an independent accepted review, publishes the reviewer
report, verifies that the reviewer snapshot contains only `review-report.md`,
observes integration, and proves two integrated snapshots, zero active
intents, and `COMPLETED` WorkGroup projection. The focused workspace review
predicate tests also pass, and the rebuilt local `cli:installDist` succeeds.

The public MCP path now fails closed earlier when a task declares the reviewer
role without an ownership claim. `ensure_session` returns
`REVIEW_CLAIM_REQUIRED` and points the caller back to `ensure_session`; it does
not create an intent, infer a report path, or treat `reviewTargets` as write
authority. The regression exercises the empty-claim rejection, then retries
with an explicit `review-report.md` claim and completes the same independent
review/publication/integration flow. This makes the blind recovery boundary
actionable without auto-assigning a reviewer or weakening claim isolation.

This is source-level closure for the reviewer-owned publication omission, not
the user's full hostile acceptance. The full `McpSyn039SliceTest` class was
started after the repair but stopped at a bounded time after stalling in an
unrelated fixture Git initialization; it is recorded as incomplete, not a
pass. Real blind current-build evidence still lacks the required combination
of an abrupt reviewer process-tree loss, successor independent decision,
post-review publication, integration, and terminal WorkGroup/goal closure.

## Fresh blind negative probe — normal reviewer discovery still incomplete

Disposable target:
`C:\Users\Liparakis\Desktop\SynesisReviewerLossBlindFresh10-20260919`

ProjectId: `29433c58-e1cc-4615-a391-5ec9c3eb2ae4`<br>
Seed commit: `a974fb9 seed mandatory review acceptance project`

Four initial agents used the unchanged blind prompt. One produced the domain
lane and published a snapshot with `reviewRequired:false`; another produced
the package metadata slice. Two more agents correctly refused overlapping
subtree claims. A later blind agent independently recognized the published
snapshot and attempted a reviewer task, but supplied an empty ownership claim
set. The normal MCP path therefore created no reviewer intent and projected
`coordination_intent_required`; the agent did not bypass that boundary or
invent a report claim. No reviewer decision, reviewer process-tree loss,
successor review grant, or review-enabled integration occurred.

This is a valid negative blindness result: the source must not auto-assign a
reviewer or fabricate a claimed report merely because README policy mentions
review. It does not weaken the focused source fixture, which supplies an
explicit reviewer-owned report claim and proves the role-neutral publication
path. The real hostile reviewer-loss milestone remains PARTIAL/NOT REACHED.

## Fresh blind successor follow-up — current-build multi-lane recovery remains partial

Disposable target:
`C:\Users\Liparakis\Desktop\SynesisReviewerLossBlindFresh11-20260919`

ProjectId: `05577b0c-6b2c-4af0-88ed-a87c555be513`<br>
Seed commit: `8eca92d4d18a9388e62207b80689a8d776b1bd7a`

Four unchanged-prompt agents admitted disjoint producer/test lanes for the
domain package, packaging, model tests, and later persistence/CLI tests. The
target's controller dry run reached `active=0`, `recoveryEligible=5`,
`executableActions=8`, and `ambiguous=0` before the fresh successor ran. A
fresh blind G selected a server-issued continuation for the detached model-test
participant: old participant `agt_e4563470-a575-3839-8049-0b5e457b0f3e` was
detached, and G received new participant
`agt_783ac82b-6824-3d82-939a-9d31ee14e19c`, intent
`5c62cfde-1f31-3d15-b7e4-2c351914bc7e`, claim epoch 2, and grant
`f8239046-4d00-32a7-8f37-c55971079fd8`. G restored/implemented the exact
`tests/test_model.py` lane and ran 9 tests successfully.

The run did not converge: G still saw other continuation candidates and no
authorized `finish_lane` projection, the WorkGroup remained `ACTIVE`, the final
dry run reported `recoveryEligible=5` and `executableActions=0`, and every
snapshot still projected `reviewRequired:false`. No reviewer role, independent
decision, reviewer loss, review successor, or terminal integration is claimed.
This strengthens real blind continuation evidence but leaves Phase 2
PARTIAL and Phase 3 NOT REACHED for hostile acceptance.

## Fresh11 reviewer-admission follow-up — review remained correctly pending

A targeted current-build reviewer probe admitted a new reviewer into the same
Fresh11 WorkGroup under a distinct participant and intent, with the exact
disjoint `review-report.md` claim. The reviewer received
`REVIEWER_PENDING`/`WAIT_FOR_ELIGIBLE_PRODUCER`; it did not receive review
access, publish a report, or make a decision. Fresh11 had three published
producer snapshots, all with `reviewRequired:false`, and several producer
candidates, so an unscoped reviewer could not lawfully select one.

A second agent using the unchanged blind prompt selected the server-issued
`tests/test_storage.py` continuation and created the claim-scoped tests, but
did not publish a snapshot or bypass the pending reviewer state. This probe
did not modify the source checkout or target coordination state manually.

This distinguishes two facts that must not be conflated: the source review
protocol correctly refuses to infer a review obligation from README prose, and
Fresh11 never created an authoritative review request for a unique producer
revision. It is evidence for the negative boundary, not a mandatory-review or
reviewer-loss acceptance pass.

## Phase verdicts

### Phase 1 — crash-safe successor authority: PASS, with a stated boundary

`RecoveryContinuationCrashSafetyTest` covers the durable event-store prefixes:
before the continuation append, the predecessor remains recovery-held and the
request is retryable; after the append and replay, exactly one successor is
projected; a competing retry observes the committed state rather than creating
a second owner. The fresh continuation is one durable
`LANE_CONTINUATION_ACCEPTED` append, and the one-shot grant is reconstructed on
replay when needed.

This proves deterministic process-crash semantics. It does not claim a
hardware power-loss guarantee for directory metadata or an injected real
process crash at every filesystem write boundary.

### Phase 2 — hostile concurrent A/B/C multi-lane recovery: PARTIAL

Fresh target:

`C:\Users\Liparakis\Desktop\SynesisOvernightReviewRecovery-20260919`

ProjectId: `c01a8d57-9fcf-41be-8af1-54c19b7dc9dc`<br>
Seed commit: `a819c69 seed issue tracker acceptance goal`

The target was seeded with a product goal requiring independent review before
integration. Agents began from the same generic blind prompt. The model,
persistence, and CLI lanes completed their own disjoint product slices. The
filtering producer was abruptly terminated after an authorized implementation
edit (`codex` PID 24276 and its process tree). A fresh participant later
selected the exact filtering continuation through the normal action/request
surface, received the successor identity, and restored the exact filtering
claims without editing while the projected action was WAIT-only.

That first run therefore proved meaningful concurrent decomposition, abrupt
producer loss, and fresh exact successor acquisition, but it used the build
before the pending-review retarget repair and did not reach terminal
completion.

A second fresh target used the rebuilt current distribution:

`C:\Users\Liparakis\Desktop\SynesisOvernightReviewCurrent-20260919`

ProjectId: `33c63329-24ab-4cf7-8612-57492c56a863`<br>
Seed commit: `a819c69 seed issue tracker acceptance goal`

Blind producers published disjoint CLI, domain, and packaging snapshots and
validated the 14-test product suite. One fresh producer then acquired exact
claims and made real uncommitted product/test edits in its isolated worktree.
Its entire Codex/MCP/Java process tree was terminated. After the configured
five-minute grace, a fresh G participant received `continuation_available` and
lawfully executed three exact continuation requests with server-provided
claim epochs, producing new successor intents/authority for previously held
CLI, packaging, and core lanes: `9047727c-d7f1-3beb-acc1-252f44077e66`
epoch 2 became `be9de613-d96d-3548-aae1-094e50b12562`,
`6ba098b1-597b-3cba-8535-83c06f48ae0a` epoch 1 became
`f6ba3d52-4cdc-3f25-b975-d717e88c1c92`, and
`1d408abf-0651-3e05-9b58-259f9e94f8ba` epoch 2 became
`061c78a6-d443-36ab-aa06-a36f2274478d`. It did not edit `.synesis` or
another participant's worktree.

The current target still did not converge: integration remained owner-gated,
the interrupted producer's own persistence/domain lane remained active rather
than terminal, and the WorkGroup never projected terminal completion. The
target control checkout stayed clean. This is a stronger current-build
interruption/successor result, but not a final acceptance pass.

A third disposable target used the rebuilt distribution for a narrower
continuation/completion check:

`C:\Users\Liparakis\Desktop\SynesisReviewContinuationBlind-20260919`

Harness: `C:\Users\Liparakis\Desktop\SynesisReviewContinuationBlindHarness-20260919`<br>
ProjectId: `1c20b1ff-18c0-4afd-bb1c-0fbe0143f289`<br>
Seed commit: `8ac9aad892c9d989f5a03eb425d8e0e47245dbef`

Blind A claimed the core model slice, created a real partial implementation,
and was terminated as PID 35520 with its process tree. The immediate dry run
reported `suspectedStale=1` and `recoveryEligible=0`; after the configured
grace it reported `recoveryEligible=1` and `executableActions=2`. The
server-issued plan `recplan-1dcae4b5a2d5453bb5b70160ff45ac49` executed as
`recexec-d3f8c4fe0c114b6eb9eee2774e09f6f0`, with two actions completed, zero
failures, and `controlCheckoutModified=false`.

Blind B discovered the one continuation candidate, executed the exact
server-projected `{intentId, claimEpoch}` request, and received a distinct
successor intent/participant. It restored the partial files through Synesis,
ran the product tests (`2 passed`), and finished with snapshot
`snap_1f4a77ce81bc932d62dd404f00cc9537`, `reviewRequired=false`, and
`integrationState=integrated`. Blind C was started independently while B was
active, read the explicit README review requirement, but lawfully selected a
remaining disjoint packaging slice instead of a reviewer lane. C then
published/integrated `snap_439ada848871f0a4d11fdf0067499895`, also with
`reviewRequired=false`. This is a real targeted continuation and integration
PASS, but it does not prove a mandatory-review or reviewer-loss PASS because
no independent review request existed before either publication.

The same target was then extended with the unchanged blind prompt to exercise
the remaining product surface and a failed-integration recovery. Blind D
through I published/integrated CLI, storage, filtering, CRUD, public API, and
README slices; their snapshots all reported `reviewRequired=false`. Blind J
published `snap_9b70c7da648f52005f55a8798cba0342` after 41 passing tests, but
integration reported `INTEGRATION_FAILED`. The failure was traced to an
accidental generic patch that had dirtied the disposable control checkout;
that exact residue was removed, without touching the source checkout or
another lane. A fresh blind K correctly refused to overlap the still-active
failed lane. After the configured grace, blind L received the normal
continuation projection and executed the server-authorized successor:

`b93cf1b2-13ee-36e7-aa30-729efe6fbf69` epoch 1 →
`84a36311-739b-3abe-86e2-ef4f6520e43e` epoch 2,
successor participant `agt_c02d6a20-f39d-3b63-a5f6-e324d56837ac`.

L verified 41 tests and integrated `snap_4832e26ed3967f34bb2e59f453679ddf`.
Blind M and N then used the explicit no-change completion action and released
their claims. Blind O found and repaired a real invalid-output-path error,
raising the target suite to 42 tests, and integrated
`snap_88a8f7de3b505e94a4932c36aaf6a37c`. This extended run is a stronger
successor/recovery and integration PASS, including recovery from an
integration failure, but it still produced no independent review request.

### Phase 3 — mandatory review and reviewer-loss recovery: PARTIAL

Source projection and service tests now cover both directions of loss:

* `reviewerContinuationReplacesDeadReviewerGrantWithoutChangingProducerAuthority`
  supersedes an unresolved grant addressed to a dead reviewer and derives a
  fresh single-use grant for the successor reviewer. Accepted review evidence
  is not migrated.
* `producerContinuationRetargetsPendingReviewRequestToSuccessor` keeps an
  unresolved review request pending, retargets it only when its target and
  conflicting intent identify the continued producer, and issues the normal
  independent review grant after the successor responds.

The earlier real target accepted a mandatory review after the reviewer used the
exact request payload, but later remained blocked by an unresolved grant tied
to the predecessor producer. The first overnight target reproduced the
analogous pending-request stale-target condition using the pre-repair build.
The earlier current-build targets' later producer snapshots projected
`reviewRequired:false`, and the extended blind run reached integrated
completion after successor recovery while its fresh agents repeatedly selected
authorized producer/no-change work rather than creating a reviewer intent.
The later target `SynesisMandatoryReviewContinuation6-20260919` did create
real `reviewRequired:true` snapshots and projected reviewer grants and exact
`SNAPSHOT_PENDING` waits, but no independent review decision was recorded.
Therefore mandatory review and reviewer loss remain NOT REACHED in real
acceptance. Those real runs exposed the defects and established the need for
the narrow repairs, but no real current-build mandatory-review PASS is
claimed.

The targeted continuation run also surfaced why the internal continuation
authorization must not be treated as review authority: replay projects the
consumed continuation grant for audit, but marks it as a continuation grant
and excludes it from review obligations. The projection and
`ReviewValidationService` now reject any attempt to record a review against
that grant with `REVIEW_SELF_APPROVAL_FORBIDDEN`. This is source-level
defense-in-depth; the blind target never created a review obligation.

The public MCP admission regression now rejects a declared reviewer with no
owned report/test path as `REVIEW_CLAIM_REQUIRED`; a retry with an explicit
`review-report.md` claim passes through the focused accepted-review,
role-neutral publication, integration, and WorkGroup closure fixture. This
improves blind diagnosability without auto-assigning review or weakening
ownership, but it is not real reviewer-loss evidence.

## Source decisions

`docs/adr/0085-successor-scoped-publication-and-reviewer-recovery.md` records
the narrow downstream repair. Exact finish evidence selects one intent;
disjoint producer lanes publish only their claim-covered paths; unresolved
review grants follow a reviewer successor without migrating decisions; and
pending review requests follow only the exact producer successor. No planner,
auto-assignment, self-review, authority bypass, or review bypass was added.

## Verification

The rebuilt source distribution and checks completed as follows:

* focused coordination continuation/crash checks: `BUILD SUCCESSFUL`, 5
  actionable tasks, 4s;
* focused workspace lifecycle/review checks: `BUILD SUCCESSFUL`, 11
  actionable tasks, 1m22s;
* focused MCP Syn-039 review/recovery checks: `BUILD SUCCESSFUL`, 13
  actionable tasks, 4m34s;
* strict format, static-analysis, architecture, Javadoc, and catalog checks:
  `BUILD SUCCESSFUL`, 19 actionable tasks, 9s;
* local `cli:installDist` rebuild passed after the latest source repair, 23
  actionable tasks, 27s;
* the public reviewer admission/publication regression, crash-prefix matrix,
  and focused workspace review guards passed after the latest source repair;
* format/static/architecture/Javadoc/catalog gates passed after the latest
  source repair, 11 actionable tasks, 17s;
* startup resume, deferred-register validation, and `git diff --check` were
  run for the source checkout.

Current-build disposable evidence also includes a real process-tree kill after
an exact producer claim and three post-grace, server-authorized successor
continuations, followed by a failed-integration retry, a successful successor
continuation at claim epoch 2, and a later 42-test integrated correction. The
extended target's repeated `reviewRequired:false` producer projections, and
the separate review target's lack of a completed decision after
`reviewRequired:true`/`SNAPSHOT_PENDING`, remain explicit acceptance limits.

The serial MCP aggregate remains bounded/incomplete because of its known
process-isolated phase; focused MCP checks pass. This record deliberately
separates source PASS evidence from real-target PARTIAL evidence.
