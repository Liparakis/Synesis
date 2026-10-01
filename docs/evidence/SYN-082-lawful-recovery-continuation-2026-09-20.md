# SYN-082 lawful recovery continuation — 2026-09-20

## Scope and boundary

This is the final source-and-target evidence update for the lawful
continuation/successor-authority milestone. The source checkout was used
directly. No Synesis MCP or coordination tool was used against the source
checkout, no source `.synesis` state exists, and no commit, push, tag, or
release was made. The disposable target was exercised only through its normal
blind Codex/MCP harness.

Source baseline at the start of this evidence slice:

- repository: `C:\Users\Liparakis\Desktop\Synesis`
- branch: `master`
- HEAD: `813b1f1c5c3572d895444850b1e22ad1f4cc114b`
- source `.synesis`: absent
- worktree: intentionally dirty from the existing SYN-082 repair series;
  unrelated changes were preserved

## Source repair and verification

The latest source defect was a false-live process classification. A new MCP
process could reuse an old PID and expose the same executable/command line,
causing the old session to remain apparently live instead of becoming
recovery-held. `ProcessInspector` now carries the observed process start time
and `SessionLeaseService` compares it with the persisted identity. A mismatch
is conservatively ambiguous while the reused process exists and becomes
recoverable after it is no longer observed; it never authorizes the old
participant. Compatibility evidence without a start time remains supported.

The action projection also now gives an addressed owner response and an
explicit completion request precedence over an unrelated recovery candidate.
This prevents a producer from being directed into an opposite-role
continuation while its durable inbox request or own finish request is still
actionable.

The final focused command passed:

```text
coordination: WorkIntentServiceTest, RecoveryContinuationCrashSafetyTest
workspace: targeted AgentNextActionServiceTest, ProcessInspectorTest,
  StaleAndGraceTest, RecoveryLifecycleTest, IntegrationOrchestrationServiceTest,
  IntegrationQueueTest
mcp: McpRecoveryContinuationTest, McpAdmissionRecoveryTest
BUILD SUCCESSFUL in 1m 7s
```

The strict quality command passed in 3 seconds:

```text
:workspace:formatCheck :workspace:staticAnalysis :workspace:architectureCheck
:workspace:javadoc :mcp-contract:test
```

The rebuilt local distribution also passed:

```text
:cli:installDist --rerun-tasks --no-daemon
BUILD SUCCESSFUL in 29s
```

The only distribution note was the existing Gradle configuration-cache
serialization warning for `:cli:nativeMcpLauncher`; it did not fail the build.
`git diff --check` reported only the repository's existing LF/CRLF
normalization warnings.

## Blind continuation evidence — named disposable project

Target: `C:\Users\Liparakis\Desktop\SomethingVeryFuckingRandom`

Harness: `C:\Users\Liparakis\Desktop\SomethingVeryFuckingRandomHarness-20260920`

The unchanged blind prompt was used. The fresh replacement agent first
received `continuation_available` through `get_next_action` for the unfinished
recovery-held producer lane:

- former participant: `agt_49203a27-9701-3fcf-9af7-16e3618e5f3d`
- source intent: `8680173b-b2fc-34b3-a011-b0e1bb8e24c6`
- source claim epoch: `1`
- WorkGroup: `7f18ae71-58ec-3b72-aa3c-25c815c33a41`
- task: `73d6d777-d8ab-3c8c-857a-1a81fc13d412`
- claims: `app.py`, `tests`, `README.md`
- former state: `RECOVERY_HELD`

It then executed the projected generic coordination request, with no invented
transition:

```json
{"kind":"continuation","payload":{"intentId":"8680173b-b2fc-34b3-a011-b0e1bb8e24c6","claimEpoch":1}}
```

The server returned `CONTINUED` and created a distinct successor:

- successor participant: `agt_677234a4-1cd9-3b86-a105-764dec71b845`
- successor intent: `6e227335-c127-3694-a9ef-bce70643c6ee`
- caller owned the successor: `true`

The replacement answered the successor's owner request and then stopped at a
server-projected `REVIEW_GRANT_PENDING` state. The grant was
`931bd6ac-fe13-3289-8867-d0df82510b33`, for successor epoch 2, and was still
addressed to the target's previously completed reviewer participant
`agt_01f38937-ad73-3afc-9705-ae26cc5cfc7a`. The agent completed the required
bounded wait cycles and reported that no closure had been projected. It did
not modify ownership, invent review authority, or bypass Synesis.

This establishes a real named-project continuation acquisition and identity
separation. It does not establish review decision, successor publication,
integration, WorkGroup closure, or goal closure for this already-populated
target. A second blind producer attempt reached repeated
`SNAPSHOT_COMPLETION_EVIDENCE_STALE`; that is downstream target-state evidence,
not a source continuation failure.

## Blind reviewer-successor evidence — prior noisy target

The separate current-build target
`C:\Users\Liparakis\Desktop\SynesisReviewerLossTarget18-20260920` supplied a
fresh reviewer continuation observation. Agent AX18 continued a recovered
review lane from intent `178d2543-d6bc-3853-a2fd-9f06be38ee1b` at epoch 15 to
successor intent `60d5092d-2687-37c8-98e9-543a9a9ceda9` under fresh participant
`agt_c780a135-cbd5-3f94-8d1c-b3e6edf960b4`, consumed the successor review grant,
and reached the exact `SNAPSHOT_PENDING` projection for `review-report.md`.
After five bounded waits the producer snapshot was still unavailable. This is
positive reviewer-continuation and grant-succession evidence, but the target's
large pre-existing population of detached/recovery-held lanes makes it
unsuitable as a terminal convergence proof.

## Blind review-fixture continuation follow-up — 2026-09-20

The same unchanged blind prompt was retried with the current distribution and
an environment-only PATH correction so the normal Codex provider-install step
could locate the already-installed executable. No prompt, source policy, MCP
catalog, or target coordination state was manually edited.

The target then recorded a real succession chain through the normal ten-tool
surface:

- C continued a recovered reviewer lane: source intent
  `a76bea75-222b-381f-ab5b-e1d39624023e` to successor intent
  `0385223a-fda8-3fed-b7d2-a4a25423a544`, participant
  `agt_2e8192dd-5db2-3713-a794-568ed401331e`.
- D continued the recovered producer lane: source intent
  `d5acc978-5a31-34f9-95eb-c7544374da67` to successor intent
  `8b1ed393-73d7-331f-8a20-cf6dc97cab8e`, participant
  `agt_3250abe8-c7c1-352c-83dc-dbce87ff2e6a`. D implemented the CLI and
  passed four tests, CLI smoke, bytecode compilation, and wheel build.
- E continued the reviewer responsibility to intent
  `96dfa556-de7c-3122-85e1-7720a634171b`, participant
  `agt_f3c666a3-5584-3efa-b890-5ca08861289b`, consumed a server-issued review
  grant, and correctly stopped at `SNAPSHOT_PENDING` until an immutable
  producer snapshot existed.
- G continued the producer responsibility to intent
  `95c8c1a6-7c2d-34e8-954b-d1a972786447`, participant
  `agt_de289900-3ba1-3ec2-80ee-8af857316ab2`, restored the durable work,
  found that the `src`-layout fixture lacked pytest import configuration, and
  repaired that assigned file. Four tests and a wheel build then passed.

The fixture still did not reach snapshot publication. Its accumulated
historical reviewer continuations/grants left reviewers waiting on older
snapshot obligations while the current producer remained in `IMPLEMENT`; the
run therefore did not establish an independent decision, integration, or
WorkGroup closure. This is target-state evidence of a noisy, partially
advanced fixture, not a justification for auto-revoking grants or weakening
the review gate. The clean source-level review-order fixture already proves
that an admitted and consumed current grant projects `finish_lane` for a
publishable producer.

## Focused verification rerun — 2026-09-20

The current uncommitted source passed the continuation-focused coordination
tests (`WorkIntentServiceTest` and `RecoveryContinuationCrashSafetyTest`), the
workspace continuation/recovery/integration tests, and the MCP continuation and
admission tests. The strict workspace format/static-analysis/architecture/
Javadoc gates and MCP-contract tests passed. A fresh `:cli:installDist
--rerun-tasks` rebuild also passed, including the web UI production build and
native MCP launcher. The first Gradle invocation hit the known Windows JDK
loopback setup failure; reruns used only the process-local
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`
workaround. No source `.synesis` state, target coordination state, commit, or
push was created by this verification.

## Fresh review-gated successor run — 2026-09-20

Target: `C:\Users\Liparakis\Desktop\SynesisMandatoryReviewFresh6-20260920`

Project identity: `4f29106f-0123-4346-8b57-0685b1edfad4`

Harness: `C:\Users\Liparakis\Desktop\SynesisMandatoryReviewFresh6Harness-20260920`

The target was created and bootstrapped through the normal local workflow. The
resulting server exposed exactly ten MCP tools. Two blind reviewer processes
claimed disjoint report paths with empty `reviewTargets`. Once the `tests`
producer was active, Synesis projected `REVIEW_ADMISSION_REQUIRED`, issued the
exact `work_group_join` requests, and issued review grants for the exact
producer intent. One reviewer process tree was terminated and the later
observer saw reviewer recovery-held/detached states and fresh successor
candidates. The grant-consumption timing around that termination was not clean
enough to claim a standalone exact kill-prefix proof; this is process-loss/
recovery evidence, not a stronger claim.

The blind source producer published and integrated
`snap_efbff536b6b0ada66a0782e9c7fcd691`, with `reviewRequired:false`. That was
correct policy behavior because no authoritative review request applied to
that source intent; the target's review prose did not create a review gate. It
is not evidence of mandatory-review completion.

The next blind successor selected the recovered `tests` producer and received
`CONTINUED` with successor intent
`41c01df5-d157-3039-b75a-298178007ba5`. The same participant's attempt to
select a reviewer continuation was rejected with `REVIEW_SELF_NOT_ALLOWED`.
Several later reviewer successors independently selected reviewer candidates,
consumed successor-scoped review grants, and reached the exact
`SNAPSHOT_PENDING` state without reading or deciding. Producer successors
restored the `tests` work and passed four tests, but completion remained
blocked by unresolved reviewer recovery; no tests snapshot was published.

At the final observation the WorkGroup was still `ACTIVE`, with one non-review
source snapshot, zero review actions, and no terminal integration or goal
closure. This run proves real concurrent blind claims, participant
loss/recovery, exact review admission, reviewer successor-grant consumption,
and self-review rejection. It does not prove an accepted independent review,
reviewer-owned report publication, review-enabled integration, WorkGroup
completion, or goal closure.

## Acceptance classification

### PASS

- source process-start-time repair and focused regressions
- strict formatting, static analysis, architecture, Javadoc, and MCP catalog
  gates
- rebuilt local distribution
- normal blind discovery of a recovery-held continuation candidate
- exact server-projected continuation action
- one durable successor with a new participant and intent identity
- preservation of review and snapshot gates; no filesystem or authority bypass
- focused crash-prefix, replay/race, scope, lineage, review-grant, and
  integration regressions already recorded in the SYN-082 series
- real review-admission projection, exact review grants, reviewer successor
  acquisition, `SNAPSHOT_PENDING`, and self-review rejection on a fresh target

### PARTIAL / NOT REACHED

- the named project did not reach an independent review decision or terminal
  WorkGroup/goal closure because its remaining grant targeted an old completed
  reviewer in the pre-existing target state
- the reviewer-successor probe reached `SNAPSHOT_PENDING`, not publication or
  integration
- the fresh review-gated run reached exact admission and reviewer successor
  grant consumption, but no independent decision, report publication, review
  integration, WorkGroup completion, or goal closure
- a fresh blind A/B/C run proving simultaneous hostile convergence through
  mandatory review, successor publication, integration, WorkGroup completion,
  and goal closure remains unproven

### Phase verdict

- Phase 1 — crash-safe successor authority: **PASS** for the tested process
  crash prefixes, replay, and race fencing. No hardware power-loss or
  directory-fsync guarantee is claimed.
- Phase 2 — hostile blind A/B/C multi-lane recovery: **PARTIAL**. Real
  concurrent disjoint claims, participant loss, grace-bound recovery,
  successor acquisition, and source integration were observed; terminal
  convergence was not reached.
- Phase 3 — mandatory independent review and reviewer-loss recovery:
  **PARTIAL / NOT REACHED**. Exact admission, successor grants,
  `SNAPSHOT_PENDING`, and self-review denial were observed; accepted review,
  reviewer publication, integration, and terminal WorkGroup/goal convergence
  were not observed.

The honest milestone result is therefore: lawful continuation and successor
authority are source-verified and real-target exercised, including on the exact
named project. The full hostile review-enabled end-to-end acceptance remains
open.

## Additional clean-target attempt — 2026-09-20

Target: `C:\Users\Liparakis\Desktop\SynesisPhase123Clean-20260920`

Harness: `C:\Users\Liparakis\Desktop\SynesisPhase123CleanHarness-20260920`

Project ID: `208aaaf8-df5a-45bc-8f8a-fc49c8e2115f`; WorkGroup:
`44d8c921-8f6b-38ef-8940-9604bad44d21`. The target was Git-seeded and
initialized through the ordinary CLI, bootstrapped through the ordinary MCP
surface, and exposed exactly ten tools. A and B independently discovered
disjoint product slices; an independent reviewer D was admitted before either
producer snapshot existed and remained in the server-projected
`REVIEWER_PENDING` / `WAIT_FOR_ELIGIBLE_PRODUCER` state.

The core producer reached the exact server-issued publication action and
persisted snapshot `snap_145f3553a6807b953b4490c4806f5536` as integrated with
`reviewRequired:false`. The operator then killed A's real process tree, but
the log shows the `finish_lane` append had already committed before the kill;
therefore this clean target does not count that kill as a recovery-prefix
proof. The packaging producer exited with dirty assigned state and entered
`WORKSPACE_STALE_DIRTY`; the reviewer exited while still pending. Normal
reconciliation later prepared and executed plan
`recplan-19d3a571f8154a84be54d964ac7aa6ba` with four completed actions, zero
failures, and no control-checkout modification. No manual `.synesis` repair or
target-file mutation was performed. The replacement candidates were
ambiguous, so no blind successor was assigned and no terminal convergence is
claimed from this attempt.

The full serial MCP suite was also run with the documented loopback-only JDK
temporary-directory environment and passed: `:mcp:test --no-daemon
--max-workers=1 --no-configuration-cache`, `BUILD SUCCESSFUL in 15m 57s`.

## Additional primary-target recovery continuation — 2026-09-20

The primary target was continued only through the normal CLI reconciliation and
MCP surfaces. After the earlier A/B/C/D run, the target reported five
recovery-eligible sessions and four executable actions. Immutable plan
`recplan-da91681d48a14287bf9bbdf0bc5a54c6` executed as
`recexec-d5321260b31d4f3ba98e5e13aba3f9be`, completing four actions with zero
failures and `controlCheckoutModified=false`.

Fresh blind participants then exercised additional successor lineage:

- E claimed a fresh reviewer intent but correctly stopped at an owner-response
  wait addressed to the dead model owner; no review decision was fabricated.
- F continued model intent `9b4ff25e-1c73-3836-87ec-5faff0935ea8` as
  `dacbc5be-533d-3f66-b9a2-5de2f3fae949`, accepted the pending review request,
  and stopped at the projected review-grant wait.
- G continued the reviewer intent, consumed the exact grant for the model
  successor, and stopped at `SNAPSHOT_PENDING`.
- H continued both disjoint producer slices as
  `52634de7-c5d5-3ea8-a2f9-c69e73398b85` and
  `bdc4bab1-7c04-3fc4-907d-766368d96e59`; it stopped at the projected wait for
  reviewer grant consumption.
- I continued G's reviewer intent and consumed the successor-scoped grant for
  the model revision, again remaining at `SNAPSHOT_PENDING` while the producer
  was not active.

Subsequent normal reconciliation plans
`recplan-2355f5bfe9c74c43a5343c5f2356858`,
`recplan-3d9bf14a9a2246ee8dab93d06369a265`, and
`recplan-ddeafe0716434eeb93fd9c7689d1cc47` each executed without failures or
control-checkout modification. J recovered the persistence slice and produced
snapshot `snap_da8c65047c67b1d50016249a2055c222`; L autonomously selected the
disjoint packaging/documentation slice and produced `snap_a4d887bff00db59693b27ded4e1a8786`.
Both reached `INTEGRATION_BLOCKED` because no valid independent review decision
was projected for those revisions. M continued I's reviewer intent and
consumed the current grant, but remained waiting for the producer snapshot.

N and O were launched concurrently as fresh blind producers. O recovered the
persistence lane, passed direct behavior checks, and produced
`snap_64f6c59f2981e71867066e93c9d57b4f`; integration remained blocked by the
review authority boundary. N attempted a CLI slice but made an accidental
native patch in the target control checkout. Synesis detected the
`workspace_mismatch`, rejected subsequent reads and writes, and N stopped
without attempting cleanup or metadata repair. The unexpected target files
were preserved as evidence; no manual `.synesis` repair was performed.

This continuation strengthens the real recovery evidence: multiple fresh
successors acquired producer and reviewer authority, pending review requests
were retargeted to lawful successors, reviewer grants were consumed without
self-approval, and integration remained fail-closed. It does not change the
milestone verdict: no accepted independent review, review decision, terminal
WorkGroup, or terminal durable goal was reached. The multi-lane run also
exposed that an empty-target reviewer cannot select among several simultaneous
producer revisions under the current no-auto-selection rule; this is an
acceptance limitation to resolve or explicitly preserve, not evidence of
successful convergence.

## Fresh single-successor crash continuation — 2026-09-20

Target: `C:\Users\Liparakis\Desktop\SynesisSuccessorTarget-20260920`

Harness: `C:\Users\Liparakis\Desktop\SynesisSuccessorHarness-20260920`

Project ID: `e4c6818a-53c6-44b7-8137-35e6708531ef`. The target was created
from an empty directory, Git-initialized, initialized through the ordinary
CLI, and bootstrapped through the ordinary ten-tool MCP surface. The source
blind prompt was copied unchanged; its SHA-256 matched the source prompt.
The source checkout remained free of `.synesis` state.

Producer A independently claimed the bounded producer lane with intent
`2e2a2cb8-8fc9-359a-982a-8953c52a99f7`, participant
`agt_96d80afd-7454-3a41-860d-7462815633f4`, claim epoch 1, authority lineage
`7e894f14-6b04-35a4-a05b-a8455904571a`, and the assigned `src/issue_tracker`
and `tests/test_store.py` paths. Its real process tree was killed before
`finish_lane`; no completion append was claimed. After the existing five-minute
grace, normal reconciliation prepared plan
`recplan-f560f1bdcfcd4807a2ec85be0ee9b0f9` and executed
`recexec-ffaaa4dd2e5544e9af446dac2289f09c`, completing 2/2 actions with zero
failures and `controlCheckoutModified=false`.

Blind producer B, using the unchanged prompt and a distinct runtime/session/
participant identity, received `continuation_available` from `get_next_action`.
It executed the server-projected generic continuation request and received
`CONTINUED`: successor intent `631eb250-3f3e-38cd-87a7-95c964826481`, successor
participant `agt_906c59c8-53d0-3050-badf-01f4240e471b`, claim epoch 2, and
`callerOwnsSuccessor=true`. B then implemented the recovered fixture only
through Synesis-scoped reads/writes, ran 3 tests successfully, and completed
the projected `finish_lane`. The resulting snapshot
`snap_20806a156de6ae572262e2cab27967ef` was `PUBLISHED`,
`reviewRequired=false`, and `integrationState=integrated`; the WorkGroup
reached `COMPLETED` at version 2.

The old participant was never reclaimed, and a later fresh run with the same
blind prompt did not reclaim its historical lane: it created a new unrelated
producer intent in a new WorkGroup and was stopped before mutation. This is
evidence for identity-separated successor authority and against silent
historical ownership recovery. The post-run dry run still reported one
derived `recoveryEligible` terminal lease but zero executable actions; the
terminal participant was intentionally skipped, so this is lease-cleanliness
evidence, not an active authority leak.

This target is a real PASS for crash-safe single-successor discovery,
server-authorized acquisition, artifact restoration, claim/intent transition,
source integration, WorkGroup closure, and goal closure in the no-review
bounded case. It does not alter the separate PARTIAL/NOT REACHED verdict for
hostile multi-lane mandatory-review and reviewer-loss convergence.

## Follow-up source verification — 2026-09-20

After recording the fresh target, the bounded source regressions passed with
the documented command-local JDK loopback directory and a command-local 4 GB
Gradle heap override required by the host:

- coordination continuation/crash-safety and WorkIntent review/continuation
  tests: `BUILD SUCCESSFUL` in 5s;
- workspace action-projection, integration, task-snapshot, recovery-snapshot,
  and reconciliation-lifecycle tests: `BUILD SUCCESSFUL` in 5m04s;
- MCP continuation, admission-recovery, rejected-continuation, reviewer
  recovery, review validation, and reviewer-owned publication/closure tests:
  `BUILD SUCCESSFUL` in 3m10s;
- `git diff --check`: PASS; source `.synesis`: absent.

These checks add no new end-to-end review claim. The acceptance classification
remains bounded single-successor PASS, hostile multi-lane PARTIAL, and
mandatory independent-review/reviewer-loss terminal convergence
PARTIAL/NOT REACHED.

## Fresh review-loss target — 2026-09-20

Target: `C:\Users\Liparakis\Desktop\SynesisMandatoryReviewFresh7-20260920`

Harness: `C:\Users\Liparakis\Desktop\SynesisMandatoryReviewFresh7Harness-20260920`

Project ID: `03d629b2-1655-4f73-bf1b-f5e1457361ff`. WorkGroup:
`dde99ac2-18f1-3409-b441-0bf480399a91`. The target was Git-initialized,
initialized through the ordinary CLI, provider-installed, and bootstrapped
through the ordinary ten-tool MCP surface. The producer and reviewer prompts
were copied unchanged and matched their source SHA-256 values. No source
`.synesis` state or source Synesis coordination was used.

The first blind reviewer claimed only `review-report.md` as participant
`agt_9ffb41df-9c7d-37b6-ba5e-97c984fdadb6`, intent
`416ec877-2432-39a3-9b2d-f778c9cf9e2e`; the producer claimed only
`src/issue_tracker` as participant `agt_6aad53ce-98f5-3139-9a62-804deff36694`,
intent `8d249484-0c09-32de-b9bb-be607a1df419`. The reviewer request
`9ce61742-de87-4a7d-a8a1-515669b2481e` was accepted and the exact review grant
was issued. Both model processes then exited before their first `finish_lane`;
the producer also reported a command-worktree consistency failure.

After the normal grace period, reconciliation prepared
`recplan-5ee78f651015430a95f86ff9f2d1e8ca` and executed
`recexec-79725c8c84a1456697d98db5ef5166e0`, completing 2/2 actions with zero
failures, no process termination, and `controlCheckoutModified=false`.
The producer successor `agt_83c798cc-c3b2-3a63-8aea-fc13990aab62`, intent
`ecdf013f-c953-39b2-8a99-807bf2366ce9`, recovered the core lane and published
snapshot `snap_ab10a4cb847c8f682e9713eaecbf52b7`.

A fresh reviewer successor `agt_7bfb62c1-d878-3a5e-a18b-55a0cd8e99ca`, intent
`e30dfca1-7a0d-35ef-9d0a-736625e68a57`, consumed the successor-scoped review
grant, inspected the immutable snapshot, and rejected it with a concrete
finding: the public status-update helper bypassed the forward-only transition
invariant. It created correction request
`e29ad630-a77f-4bca-b59e-62f134ea05f3` to the producer successor. A fresh
producer `agt_a18f2614-2000-3816-ab31-a281048a87c4`, intent
`12fc63bf-e5e3-3a16-a102-a9e9f4fe9c33`, lawfully reacquired the correction lane
at epoch 3 but stopped at `REVIEW_GRANT_PENDING`; it did not self-approve or
integrate.

The reviewer model then exited after issuing the rejection. Normal
reconciliation eventually fenced it as `RECOVERY_HELD`, but the next fresh
reviewer admission was rejected because `review-report.md` remained held by
that recovery-held reviewer participant. The final target dry run showed
`active=0`, `recoveryEligible=5`, and `executableActions=0`; the WorkGroup
remained `ACTIVE`. This exposes a front-door acceptance gap between a
recovery-held reviewer claim and a fresh reviewer session: the direct
continuation/review services are source-tested, but this exact unchanged
reviewer prompt did not reach a successor admission after the reviewer’s
post-decision exit.

This target is PASS for real review admission, exact grants, immutable
snapshot review, independent rejection, correction routing, reviewer/producer
identity separation, and fail-closed no-self-approval. It is PARTIAL for
reviewer-loss continuation after the decision, correction publication, review
integration, WorkGroup closure, and durable goal closure. One invalid
operator-supplied execute-plan ID was attempted while preparing the final
cleanup; it mutated nothing, and the exact prepared plan was subsequently
executed successfully.

## Fixed recovery-held reviewer admission target — 2026-09-20

Target: `C:\Users\Liparakis\Desktop\SynesisReviewRecoveryFixed-20260920`

Harness: `C:\Users\Liparakis\Desktop\SynesisReviewRecoveryFixedHarness-20260920`

Project ID: `659d0e3a-22b0-46fb-a98c-372b678555d3`. WorkGroup:
`5a895559-3e1f-354a-bfb3-1e3778a9dd20`. The target was Git-initialized,
initialized through the rebuilt local distribution, provider-installed, and
bootstrapped through the ordinary MCP surface. The bootstrap catalog contained
exactly ten tools. No source `.synesis` state, source Synesis coordination, or
manual target mutation was used.

The initial blind producer/reviewer lanes were concurrent and disjoint. The
producer B wrapper PID `35292` and Codex child PID `33188` were verified as a
process tree and killed together before B completed. Normal grace/recovery
prepared `recplan-669e169754aa4f3f8ad62ad484e8290f` and executed
`recexec-cf05943a41c94b0a9ec297d93e8cf0a2`, completing 6/6 actions with zero
failures and `controlCheckoutModified=false`. Fresh producer successors
recovered separate storage and core lanes rather than reclaiming the dead
participant's identity. A recovered storage successor published and integrated
snapshot `snap_5ef26d1bcdd8710a8161fe643c208e85`; its snapshot projected
`reviewRequired:false` because the review request for the still-pending core
lane had not yet applied to that intent.

Reviewer-loss recovery was also exercised. Reviewer J was killed as the exact
wrapper PID `28576` and Codex child PID `21688` reached the projected
`work_group_join` boundary for the available review grant; no review decision
or report write occurred. Normal recovery prepared
`recplan-b8141039e42b47b288a57b1c3c03952d` and executed
`recexec-9fb3a2191ad749d9a68b577ad82cef82`, completing 4/4 actions. Reviewer K
then acquired a fresh successor identity and reached the actual projected
`review_validation` action with `nextProtocolKind` `SNAPSHOT_PENDING` for core
intent `9f03392f-5726-3b23-be70-36f715b42bb6`, reviewed participant
`agt_60c74c0a-8749-3d97-8cfe-c28f5b4775c5`, and grant
`3e102a7e-100e-3223-b76f-4be70805c6b2`. K explicitly stopped at that server
projection without reading `review-report.md` or submitting a decision. This
proves lawful reviewer successor projection after loss, but not an accepted
review: the core snapshot was still pending and no reviewer artifact was
published.

Later reviewer/producer launches produced mixed role/session output, including
a restored core producer with no new source change and a fresh reviewer report
that the producer snapshot was still pending. Because the target never reached
an independent review decision, reviewer publication, review integration,
WorkGroup completion, or goal closure, the late output is retained as
diagnostic evidence only and is not treated as terminal convergence.

## Source repair supporting the target — 2026-09-20

`WorkspaceCollaborationService.recoveryContinuationAdmission` is a read-only
preflight for an exact recovery-held reviewer claim. It checks role,
participant distinctness, optional WorkGroup identity, recovery snapshot,
active WorkGroup, and exact selectors; it creates no participant, intent,
claim, grant, successor, or continuation record. `McpProtocolHandler` uses it
only to let that exact fresh reviewer session reach the existing projected
continuation action instead of being rejected at empty-claim admission. The
focused regression is
`McpAdmissionRecoveryTest.recoveryHeldReviewerClaimCanReachContinuationProjectionBeforeClaimTransfer`.
ADR-0086 records the boundary.

## Acceptance classification

- Phase 1, crash-safe successor authority: PASS. Source checks and the earlier
  bounded real single-successor target reached lawful successor acquisition,
  artifact restore, publication, integration, and WorkGroup/goal closure.
- Phase 2, hostile concurrent blind A/B/C recovery: PARTIAL. Concurrent
  process loss, ordinary reconciliation, disjoint successor lanes, and
  successor-scoped identity were observed, but this target did not converge to
  terminal completion.
- Phase 3, mandatory independent review and reviewer-loss recovery: PARTIAL /
  NOT REACHED. Reviewer successor projection after hostile loss was observed,
  but no accepted decision, reviewer publication, review integration, or
  terminal WorkGroup/goal state was reached.

No commit or push was performed.

## 2026-09-23 — process-crash prefixes and reviewer wait regression

### Abrupt JVM termination at continuation append boundaries

Added
`coordination/src/test/java/org/synesis/coordination/persistence/RecoveryContinuationCrashSafetyTest.java`
coverage that launches a separate JVM, enters
`WorkIntentService.continueEligibleFromRecovery`, and halts with exit code 86
at three `PredictionEventStore` append boundaries. This is an actual process
termination, not an exception thrown in the test JVM. The test class passed:
4 tests, 0 failures, `BUILD SUCCESSFUL in 9s`.

- `BEFORE_DURABLE_MOVE`: the child leaves only a temporary event. Opening a
  fresh store removes that orphan file, leaves the source participant
  `RECOVERY_HELD`, creates no successor/event, and a retry commits one
  successor.
- `AFTER_DURABLE_MOVE`: the child exits after the numbered event is durable but
  before projections apply. Replay reconstructs one successor; retry returns
  that same successor and does not append a second event.
- `AFTER_PROJECTION`: the child exits after in-memory projections apply but
  before the service returns. Restart again reconstructs one successor, and
  retry does not duplicate the event.

There is no durable append #2 in the current continuation path: grant issuance,
consumption, successor intent, claim transfer, and predecessor detachment are
projected from one signed `LANE_CONTINUATION_ACCEPTED` event. Therefore there
is no `append #1`/`append #2` intermediate state. The child-JVM test exercises
the coordination service/event-store boundary; it does not physically crash a
full MCP server during workspace snapshot copying.

### Full test sweep and correction

The combined serial command
`:coordination:test :workspace:test --no-daemon --max-workers=1
--no-configuration-cache` completed the full coordination task, then the
workspace task reported 432 tests with one failure. The failure was
`ReviewAdmissionOrderIndependenceTest.reviewerFirstWaitsForProducerBeforeNoChangeCompletion`:
an admitted reviewer with no producer and no grants received generic
`IMPLEMENT` instead of `WAIT`. `hasUnresolvedReviewObligation` treated the
empty grant list as resolved while trying to exempt continuation grants.

The fix uses the durable `WorkGroupProjection.continuationGrant` marker to
distinguish a reviewer-lane continuation from the absence of review work. An
empty grant list again means the reviewer waits for a producer; an explicit
continuation grant by itself does not create a review obligation. Verification
after the fix:

- the formerly failing workspace test passed;
- all four `ReviewAdmissionOrderIndependenceTest` cases passed in 1m48s;
- the focused MCP continuation, admission recovery, and rejected-continuation
  classes passed (6 tests, `BUILD SUCCESSFUL in 2m26s`);
- the four-test crash-safety class passed after the final held-state assertion.

The full serial workspace suite still needs a post-fix rerun. The prior full
serial MCP attempt was interrupted and produced no final result; it still
needs a complete run. Strict workspace quality gates, the final MCP catalog
check, `git diff --check`, and a fresh source checkpoint remain. No source
`.synesis`, Synesis coordination in the source checkout, commit, or push was
used.

## 2026-09-24 addendum — post-fix suites and overnight acceptance

This addendum supersedes the outstanding-suite statements above where current
results are available. The original 2026-09-20 observations remain historical.

### Source baseline and guardrails

- Checkout remained C:\Users\Liparakis\Desktop\Synesis, branch master, HEAD
  813b1f1c5c3572d895444850b1e22ad1f4cc114b. The existing dirty tree had 136
  paths (60 modified and 76 untracked) at the starting check; it was preserved.
- Source .synesis was absent. No Synesis MCP or coordination was used in this
  source checkout. No commit or push was made.
- Codex CLI was 0.153.0; the built distribution identified as 0.1.0-dev.local,
  build timestamp 2026-09-24T09:24:53.942681500Z. The MCP initialize handshake
  reported 0.1.0-SNAPSHOT. Java 25 was used. The MCP catalog remained exactly
  10 tools.

### Current-source verification

- Coordination suite: 91 passed.
- Workspace suite: 432 passed after the review-obligation ordering fix.
- Full serial MCP suite: 108 passed; focused SYN-039 suite: 20 passed.
- Focused continuation/recovery tests, static analysis, architecture checks,
  Javadocs, mcp-contract:test (10), deferred/resume validators, and the
  agent-resume checks passed.
- cli installDist passed. cli distributionCheck hung in GUI installer smoke
  and was interrupted; it is not a pass. The repository format gate reported
  trailing whitespace in three pre-existing untracked evidence documents;
  those files were not cleaned as part of this acceptance.

### Disposable target 4 — project and participants

Target: SynesisOvernightABC4-20260924. Project
a57fef3a-b7a4-449b-8565-81ddf0b83af6; WorkGroup
93a9e157-8a82-3328-9e67-928bf03f6b55. Baseline commit d922531. MCP exposed
exactly 10 tools. Productive agents used the same blind prompt hash
93A1AE75F0D0C139FE152907ABD7DDB99892C45FEDC144A5B0E02E1CA7C0EDCA. No human
role assignment or bootstrap lane was used.

- A participant agt_8d9b7b69-e916-39dc-a388-f4402da7b204 owned model/test
  paths and published snap_40354e1e733fedbb1b8a31b9fe93a1d8; its report said
  four tests passed. Synesis marked reviewRequired=false and integrated it.
- B participant agt_96b876f5-081a-3d3a-ba66-780c88386cff owned the disjoint
  storage/test paths. The watcher killed its process tree at 2026-09-24
  13:43:52 +03 with exit 0. A's wrapper was alive at the kill. B's patch
  material remained available for continuation.
- A exited at 13:44:46, before C started at 13:49:21 after the five-minute
  grace. Thus the required condition that the survivor still be active when C
  acquires is not met; do not infer liveness from a Synesis ACTIVE projection.
- Blind C participant agt_e2e472f2-b43e-35e9-98be-a2bb95227eea continued B's
  exact storage lane: source intent 69aa346d-03ef-3f2c-b91b-04c753caed1e,
  successor intent 40b80a54-aa94-3863-a1f1-22f38ff191fb, claim epoch 2. C
  attempted to accept A's producer-origin review request and was denied with
  REVIEWER_INTENT_REQUIRED. C made no file changes and ended without terminal
  WorkGroup convergence.
- Blind D participant agt_161c4eff-3812-3cb9-aab1-9f2427941fa2 chose disjoint
  CLI/test paths. Its direct create smoke passed. Pytest failed collection:
  the command runner imported issue_tracker from another workspace, while
  Synesis reads showed the claimed package initializer absent; revision-matched
  patches were rejected stale. D published
  snap_5b9f3d0a921a6397c0491162f3ecb6fd, but Synesis marked it
  INTEGRATION_BLOCKED and reviewRequired=false. No manual fix was made outside
  D's claim.
- After the Windows PowerShell launch wrapper treated a Codex warning as a
  terminating error, the same blind prompt was started correctly under
  PowerShell Core. Blind E autonomously chose the available storage slice. It
  continued source intent 40b80a54-aa94-3863-a1f1-22f38ff191fb at source epoch
  2 into successor intent 2f6e6e36-cdca-3747-b607-8104201db3e9, participant
  agt_7e8bc054-0b4f-3dd5-ab9e-63b1a8d44ca9. E found the predecessor's storage
  files, passed tests/test_storage.py (4 tests) and the combined target suite
  (8 tests), and made no file changes. This is a second fresh continuation of
  the storage lineage, not proof of the A/B/C survivor condition.
- E also encountered the pending review request. Synesis again rejected
  acceptance with REVIEWER_INTENT_REQUIRED. E did not treat continuation
  authority as review authority. The WorkGroup remained ACTIVE with unresolved
  owner-response state; no terminal goal was reached.

### Phase verdicts

Source review-state finding: reviewRequired is projected true for an exact
available/consumed non-self grant, an exact pending/accepted REVIEW request,
or a previously rejected snapshot on the same lineage. The target's WorkGroup
acceptance prose alone created none of those states before publication.

- Phase 1 — PASS for deterministic source crash-prefix/replay/idempotency and
  bounded disposable participant-process loss followed by a fresh lawful
  successor. The source event-store tests halt before durable continuation,
  after its durable event, and after projection. Limitation: the real target
  did not crash the complete Synesis MCP server between workspace snapshot
  copying steps or during the durable append itself; no power-loss claim is
  made.
- Phase 2 — PARTIAL. A/B worked concurrently on disjoint slices and B was
  actually killed; fresh blind C acquired B's exact lane with new identity and
  epoch. A was not still active at acquisition. Later E also lawfully
  continued storage and verified retained tests. Product validation and
  recovery races did not reach a terminal WorkGroup/goal.
- Phase 3 — PARTIAL; the full hostile mandatory-review path was NOT REACHED.
  Review attempts failed closed as intended, but snapshots reported
  reviewRequired=false, no reviewer WorkIntent was established, no independent
  decision or reviewer-loss successor occurred, and integration/terminal
  convergence were not demonstrated.

### Explicit answers

1. Source fault injection supports exactly one lawful owner across the tested
   durable prefixes. The new protocol has one durable
   LANE_CONTINUATION_ACCEPTED event; there is no separate durable append #2
   after append #1. Before that event, recovery leaves predecessor work
   eligible; after it, replay reconstructs exactly the committed successor.
   If the caller loses the CONTINUED response, retry resolves to that same
   successor rather than creating another owner. The real target did not
   physically crash the full MCP process at the append boundary.
2. Blind A/B did work concurrently and a killed lane was lawfully continued
   without inherited participant identity. The strict three-agent success
   question is not proven: the survivor exited before C's acquisition, later
   lanes did not converge, and WorkGroup/goal remained active.
3. No. This run does not show recovered work traversing mandatory review and
   reviewer loss. It shows unauthorized producer acceptance is rejected with
   REVIEWER_INTENT_REQUIRED and continuation authority is not review authority.
   A replacement reviewer could not lawfully approve because a reviewer intent
   was never admitted; the correct candidate was not reviewed.

Logs for the latest target run are under
C:\t\SynesisOvernightABC4-20260924-run\retry-approval. Preserve the current
source worktree and these partial verdicts; do not claim full unattended
acceptance or use target filesystem edits to repair Synesis state.
## 2026-09-25 addendum — review admission safety and fresh target attempt

This addendum records the current source verification and a new disposable
target. It does not upgrade the earlier live acceptance to full PASS.

### Source changes and durable review behavior

- Review acceptance now requires the requester to hold an active explicit
  REVIEWER WorkIntent for the exact WorkGroup before the producer can durably
  accept the request and issue a grant. The check occurs before the accepted
  response is appended, so a requester without reviewer authority cannot leave
  an accepted-but-unusable request.
- An accepted exact review request reserves its snapshot across the crash
  window between durable response and grant append. On restart, a second
  reviewer cannot take the same candidate; the original reviewer may retry and
  produce one snapshot-bound grant.
- The accepted-request replay shortcut is scoped to the same target intent and
  epoch. A full MCP regression exposed replay of an old accepted request across
  a corrected producer epoch; the guard was narrowed and the rejected-
  continuation suite and full MCP suite then passed.
- Mandatory review policy and exact snapshot identity are represented in the
  durable WorkGroup/request/grant records. V2 payloads round-trip and existing
  V1 payloads remain readable. No migration command is required for V1 records;
  mixed-version deployment compatibility was not exercised.
- Workspace command environments remove inherited PYTHONPATH, PYTHONHOME,
  and PYTHONUSERBASE, preventing an unrelated checkout from supplying Python
  imports to a Synesis-managed command.
- No MCP tool was added or removed. The catalog remains exactly 10 tools.

### Current-source verification

All full suites below completed serially after the source fixes:

- Coordination: 99 tests passed.
- Workspace: 436 tests passed.
- MCP: 109 tests passed. An earlier full run exposed the rejected-correction
  replay regression; after the epoch-scoped fix, the full suite completed with
  no failures.
- mcp-contract:test passed, including the 10-tool catalog contract.
- Continuation crash-prefix tests: four child-JVM fault/replay tests passed at
  BEFORE_DURABLE_MOVE, AFTER_DURABLE_MOVE, and AFTER_PROJECTION.
- Focused workspace checks passed: all 20 AgentNextActionServiceTest cases,
  all 4 ReviewAdmissionOrderIndependenceTest cases, both
  AgentTaskCompletionReviewPolicyTest cases, and
  ProjectProcessExecutorTest.workspaceCommandsDoNotInheritPythonImportRoots.
- Focused MCP checks passed: mandatory-policy persistence, wrong participant /
  stale epoch / snapshot / invalid-result / conflicting-replay review
  validation, reviewer successor grant migration, admission recovery, catalog
  checks, and both McpSyn039RejectedContinuationTest cases.
- Format, static analysis, architecture, and Javadoc gates passed for
  coordination, workspace, MCP contract, and MCP.
- CLI format, static analysis, Javadoc, installDist --rerun-tasks, and
  launcherSmoke passed. The web UI bundle and native MCP launcher also built.
- Deferred-register validation passed (11 entries). The resume validator ran;
  it warned that the CLI rebuild recreated web-ui/node_modules newer than
  STATE.md.
- cli:distributionCheck was not passed: the prior GUI installer smoke hung
  and was interrupted. No fresh installer smoke or Graphify run is claimed.

The Windows loopback test workaround was process-local:
TEMP/TMP=C:\t and
JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe,
with one Gradle worker and no configuration cache.

### Disposable target and bootstrap

The first fresh target contained a committed README.md; synesis init returned
CONTROL_CHECKOUT_DIRTY for that path before creating .synesis. It was
preserved and not manually repaired. A second empty Git repository,
C:\t\SynesisOvernightABCfresh2-20260925, initialized successfully through
the built Synesis CLI. Its Synesis-managed baseline commit was 0fb67fc;
ProjectId was 7f41f168-cf34-4c4a-9218-e55890b25176.

The built CLI reported Synesis 0.1.0-SNAPSHOT (commit bc334ac) and Codex
CLI was 0.153.0. Codex provider installation reported DEGRADED /
EXPERIMENTAL, while its MCP health check passed and session binding was
BOUND; workspace trust and native mutation interception remained unproven.
The target MCP tools/list returned exactly 10 tools.

The controller seeded the durable issue-tracker goal and created WorkGroup
b2b754e9-2c46-4475-b781-8639064f617c with
mandatory=true and independenceRequired=true. It supplied no lane claims,
roles, or implementation assignments. Agents used the same unchanged blind
prompt (SHA-256
93A1AE75F0D0C139FE152907ABD7DDB99892C45FEDC144A5B0E02E1CA7C0EDCA).
Initial Codex MCP calls under approval policy never were rejected before
session or file mutation. The successful runs used Codex automatic review
(--approve-for-me); no dangerous approval, sandbox, or hook-trust bypass
flags were used.

Logs are preserved under
C:\t\SynesisOvernightABCfresh2-20260925-run.

### Blind work and recovery race

- Blind A (agt_0b6a2c4b-8193-3d08-897e-05d0bd55a57a) independently claimed
  subtree issue_tracker with intent
  dd9e1223-e595-35c4-bbb6-e47f4e1e820c. It added
  issue_tracker/__init__.py and issue_tracker/store.py; its SQLite CRUD,
  transition, filtering, and JSON round-trip smoke check passed.
- Blind B (agt_bd73c403-e91c-3fc7-a964-f4c08ddc4c84) independently claimed
  disjoint subtree src/issue_tracker with intent
  e6e56d31-68f2-35c2-847c-e8dbc5f43f02. It added
  src/issue_tracker/core.py and src/issue_tracker/__init__.py; import and
  transition smoke checks passed. Both Codex processes and both distinct
  WorkIntents were observed active concurrently.
- The agents chose different package roots for the same core product area.
  Their path claims did not overlap, but the resulting packages were not
  integrated into one product layout.
- A and B's first noninteractive Codex turns ended normally; neither was
  abruptly killed while active. They did not publish, integrate, or close the
  WorkGroup. Do not count this as the required hostile A/B kill event.
- On resumed sessions, two participants raced for the same recovery candidate.
  B's new participant
  agt_caa0fc43-204a-3984-9278-42aa78ebf33b received CONTINUED for B's intent
  at epoch 1, creating successor intent
  6b66ce3e-172e-3369-a192-77331d3c25d0. A's competing request returned
  ALREADY_COMMITTED to that same successor with
  callerOwnsSuccessor=false. B's participant then continued the other
  disjoint lineage to successor intent 3222ae06-04cc-3a79-9324-08c34694181c.
  This observed race created one successor per source intent, not two.
- Fresh blind C (agt_5d3ec25b-8cb4-3ced-b2b5-1d4772eaa5a6) later discovered
  the recovery candidate without cross-chat instructions and continued
  successor intent 3222ae06-04cc-3a79-9324-08c34694181c at epoch 2, creating
  ef0de11e-5091-3b40-be18-6d5cce48cc68. Through Synesis run_command, C
  verified SQLite CRUD/persistence, forward transitions, filtering, JSON
  export/import, and python -m compileall -q issue_tracker.
- C ended its Codex turn after polling; no second active participant was
  observed at C's acquisition. The WorkGroup remained ACTIVE, with no
  projected finish, integration, or terminal goal action. The target did not
  exercise a physical kill during active implementation.

### Phase verdicts and explicit answers

- Phase 1 — PARTIAL. The deterministic source crash-prefix/replay matrix
  passes and continuation is one durable event. A full Synesis MCP process was
  not physically killed at the append or snapshot-materialization boundary.
- Phase 2 — PARTIAL. Blind A/B worked concurrently on disjoint claims,
  observed continuation arbitration rejected the losing caller, and fresh
  blind C continued a lawful lane with new Synesis participant/session
  authority. The required abrupt kill while a survivor is active, survivor
  liveness at C's acquisition, and terminal WorkGroup/goal convergence were
  not proven. The target packages also remain unintegrated.
- Phase 3 — PARTIAL / end-to-end path NOT REACHED. Mandatory independent
  review policy persisted through the target MCP and source tests cover active
  reviewer intent, snapshot binding, crash retry, and reviewer successor
  authority. No real target review decision, reviewer loss/replacement,
  integration, or terminal convergence occurred.

1. Crash at every continuation point: source fault injection proves the
   tested durable prefixes converge to one outcome without manual repair.
   Physical full-server crash/restart at every requested boundary remains
   unproven.
2. Three-agent hostile convergence: no. Two blind agents worked concurrently
   and a fresh blind C continued a lane, but there was no abrupt kill with an
   active survivor at acquisition, and no terminal convergence.
3. Mandatory review through reviewer loss: no. The policy and source-level
   guards are in place, but a live independent decision and reviewer-loss
   successor path did not run.

No Synesis MCP/coordination was used in the Synesis source checkout. Source
.synesis remains absent. No source commit or push was made.
