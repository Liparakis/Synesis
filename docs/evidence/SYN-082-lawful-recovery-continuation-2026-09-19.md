# SYN-082 lawful recovery continuation — 2026-09-19

## Result

The missing recovery-held continuation path is implemented and verified at the
source and disposable-project boundaries. A fresh blind Codex process discovered
one unfinished recovery-held lane, explicitly requested the projected
continuation through the existing generic coordination action, received a new
successor intent and participant, used a valid successor worktree, repaired and
validated the recovered application, published its snapshot, integrated it, and
closed the WorkGroup. The source repository remains uncommitted and unpushed.

This is a lawful continuation acceptance, not a claim that a simultaneous
multi-lane hostile A/B/C decomposition was exercised. The real fixed-target run
had a normal bootstrap no-op, one interrupted producer, and one successor. A
review-required continuation was not reached because the disposable target did
not project a review requirement; review and integration gates remain in source
tests and in the existing projection path.

## Baseline and boundaries

- Repository: `C:\Users\Liparakis\Desktop\Synesis`
- Baseline source HEAD at investigation start:
  `813b1f1c5c3572d895444850b1e22ad1f4cc114b`
- Existing task: SYN-082; existing ADR: `docs/adr/0083-lawful-recovery-continuation.md`
- No Synesis coordination/MCP was used for this source checkout.
- No source-checkout `.synesis` state was created or edited.
- The exact ten-tool MCP catalog is unchanged.
- No commit, push, tag, release, or remote mutation was performed.
- Existing unrelated uncommitted work was preserved.

## Initial gap and source reconstruction

The existing implementation already had a durable recovery snapshot and a
continuation primitive, but the ordinary successor path was not projected to a
new blind agent and could require a pre-issued private grant. The durable unit
that must continue is the unfinished `WorkIntent` together with its projected
claims, task, WorkGroup, acceptance criteria, selectors, role, dependencies,
and authority lineage. The old participant, connection, session, provider
runtime, and worktree are not transferable authority.

Reconciliation first moves a dead unfinished participant to `RECOVERY_HELD`,
retaining claims and the immutable recovery snapshot. There is no successor
until a new exact participant sees an eligible candidate and explicitly requests
it. `get_next_action` now exposes a read-only continuation candidate. With one
candidate it supplies only the public intent/epoch payload; with multiple
candidates it supplies selection information but no selected lane and no
continuation payload. It never auto-assigns work.

The server validates the source participant, source intent/version, recovery
state, snapshot, target task/goal/acceptance/scope/role/dependencies, authority
lineage, claim epoch, group, and conflict state under the project append lock.
It derives a fresh one-shot continuation grant when the request omits a
pre-issued grant, records the continuation, creates a new intent and
participant, and transfers projected ownership. The successor preserves the
task and durable authority lineage but receives a distinct participant,
connection instance, session lease, provider binding, runtime authority,
worktree, and intent. The old participant is detached and cannot regain claims.
Review, snapshot publication, integration, and WorkGroup closure remain
authoritative downstream gates.

The fresh public form derives deterministic grant and successor identities but
does not append a separate grant event: the successor intent, claim transfer,
and one-shot grant consumption are reconstructed from one durable
`LANE_CONTINUATION_ACCEPTED` event. The reviewed pre-issued-grant form remains
supported. The event store writes and forces a complete temporary event before
the durable move, then applies projections; restart tests cover loss before the
move, after the move, and after projection. The tested guarantee is
process-crash recovery and deterministic replay, not a hardware power-loss
directory-fsync guarantee.

## First real acceptance failure and repair

The first target was `SynesisContinuationAcceptance-20260919`. Agent B received
`CONTINUED`, but its next `ensure_session` failed closed with
`WORKSPACE_BINDING_MISMATCH`. The recovery snapshot had copied the predecessor's
root `.git` file into the successor worktree. The file pointed at the old
worktree metadata, so the continuation semantics were correct while the
successor workspace was not usable.

The repair centralizes protected-path checks in
`RecoverySnapshotService`: root `.git`, any `.git` path segment, and `.synesis`
are excluded from snapshot materialization and hashing. A regression test proves
that a root `.git` is not copied or changed, `.synesis` is excluded, and ordinary
partial files are restored. The successor's own generated worktree metadata is
therefore authoritative.

## Focused source verification

The following targeted checks passed after the snapshot repair:

| Check | Result |
| --- | --- |
| `WorkIntentServiceTest` fresh grant, replay, race, task/lineage preservation | PASS |
| `AgentNextActionServiceTest` read-only continuation projection, no snapshot path, no polling event, no multi-candidate auto-selection | PASS |
| `RecoverySnapshotServiceTest` protected `.git`/`.synesis` handling and partial restore | PASS |
| `IntegrationQueueTest` predecessor snapshot to successor integration and WorkGroup completion | PASS |
| `McpSyn039RejectedContinuationTest` optional public grant schema | PASS |
| Exact MCP catalog and contract tests | PASS; exactly 10 tools |
| Combined focused coordination/workspace/integration/MCP command | PASS; `BUILD SUCCESSFUL in 3m54s` |

The existing lifecycle, stale-plan, review, and admission tests remain part of
the affected module suites. The source design rejects stale, wrong-source,
wrong-epoch, replayed, conflicting, and racing continuation attempts; no new
authority bypass was added.

## Real blind acceptance

The fixed target was:
`C:\Users\Liparakis\Desktop\SynesisContinuationAcceptance-20260919-Fix`.
The launcher was rebuilt locally and installed through the normal provider path;
the target used that launcher. The first exact blind invocation encountered a
fresh blank scaffold and correctly stopped without assuming work. Repeating the
same unchanged prompt was required to let the normal bootstrap create a real
producer lane; this was not manual assignment.

The exact blind prompt was:

```text
You are an autonomous coding agent working in this project.

You have not been given a task or role directly.

Use the project's normal Synesis integration to determine what project
 you are in, what the current goal is, what work exists, what is blocked,
 and what you can lawfully own.

Coordinate exclusively through Synesis.

Do not assume ownership.

Do not ask the bootstrap conversation to assign work.

Do not manually edit `.synesis`.

Do not bypass authority decisions.

Work autonomously.
```

The interrupted producer was A2:

- participant `agt_dc33a0aa-c250-3cd8-95b5-a587a11abad4`
- intent `617ca69d-3ade-34c3-a10a-f9d5671e369f`
- WorkGroup `82da3ec0-976c-3de5-b417-e1862add92cb`
- task `a3cb4b62-7762-3642-a0d4-6b920f25359a`
- authority lineage `c3a54144-e6d1-3625-a9cc-184ad833c8c8`
- connection lease `conn-instance-713d7808-10c5-42b7-a1e3-8b9ab41f98f5`
- claims: `issue_tracker` subtree, `tests` subtree, `pyproject.toml`, and `README.md`

Its outer Codex process was killed at PID 15516. The child MCP/Java process
also terminated, and no target process remained. The first post-kill reconcile
was correctly empty while a lease was still within grace. After the exact grace
period, dry-run reported two recovery-eligible sessions and two executable
actions. Prepare/execute completed both actions with zero failures and did not
modify the target control checkout.

The successor C independently called `get_next_action`. It received one
`continuation_available` candidate with the predecessor task, WorkGroup,
lineage, recovery-held state, unfinished flag, durable-artifact flag, and
completion obligation. It executed exactly the projected generic action:

```text
request_coordination kind=continuation
payload={claimEpoch:1,intentId:"617ca69d-3ade-34c3-a10a-f9d5671e369f"}
```

The server returned `CONTINUED`. C then had a normal implementation action,
with a new participant
`agt_df1bf059-3ede-3fd3-ad4a-00a254e9d28a`, new intent
`15f3c0c4-725c-32d1-9888-563e26eaad89`, claim epoch 2, new connection lease,
and a one-shot grant. The original task, WorkGroup, and authority lineage were
preserved; A2 was detached and no old claims were retained by it.

C inspected the restored application through Synesis and verified its own
worktree binding. Its `.git` pointed to its own generated worktree metadata,
not A2's. It found and repaired two ordinary recovered-application defects
through authorized Synesis patches: invalid status validation was masked by an
earlier missing-issue lookup, and CLI `argparse` errors escaped instead of
returning the documented nonzero code.

Then, without manual source edits from this operator:

- `python -m unittest discover -s tests -v`: 6/6 passed.
- `python -m pip install -e .`: passed.
- subprocess CLI add/move/board/archive/list/list-all and invalid-status and
  malformed-database checks: all passed with expected exit codes and output.
- `get_next_action({completionRequested:true})` projected
  `snapshot_publication_required` and the exact `finish_lane` action.
- `finish_lane` returned snapshot `snap_0b9fe51ce4411a561950186cc9d8b429`,
  `snapshotState=PUBLISHED`, `integrationState=integrated`.
- The following read-only projection was `state=COMPLETED`; the WorkGroup was
  `COMPLETED VERSION=2`.

The disposable target's README contains pre-existing mojibake separators. The
agent did not bypass the authorized patcher to rewrite them; this is a
non-blocking documentation caveat in the disposable target, not a lifecycle or
authority failure. The target control checkout remained clean.

## Repository verification

- Serial `:coordination:test`: PASS (up-to-date; focused continuation tests
  were executed directly and passed).
- Serial `:workspace:test`: PASS; `BUILD SUCCESSFUL in 9m32s`.
- Serial `:mcp:test`: intentionally stopped after approximately 16 minutes in
  the known process-isolated phase without producing a result. The focused MCP
  continuation/schema/catalog checks passed, and this aggregate is therefore
  recorded as incomplete rather than passed.
- Quality-only command covering workspace/CLI/MCP format, static analysis,
  architecture, Javadocs, and MCP contract tests: PASS; `BUILD SUCCESSFUL in
  1s` with 22 actionable tasks.
- Deferred register: PASS, 11 entries.
- `git diff --check`: PASS; Git emitted only expected LF-to-CRLF working-copy
  normalization warnings.
- `scripts/agent-resume.ps1`: PASS; active task remained SYN-082 and the
  source checkout had no `.synesis` directory (`Test-Path .synesis=False`).

The earlier parallel aggregate reported one workspace failure in
`ProjectProcessExecutorTest.cancellationPreservesPartialOutput` after 419
tests. The test passed in isolation in 5 seconds and the complete serial
workspace suite passed, so that aggregate failure is classified as a
parallel-environment interaction, not a continuation regression.

## Acceptance classification

| Requirement | Result |
| --- | --- |
| Recovery-held eligibility is visible through `get_next_action` | PASS |
| Explicit successor request through existing generic coordination | PASS |
| New participant/connection/session/worktree authority | PASS |
| Task, WorkGroup, acceptance scope, and durable lineage preservation | PASS |
| No old lease resurrection or claim bypass | PASS |
| Unpublished recovered artifacts usable by successor | PASS after root `.git` repair |
| Race/replay/stale/conflict rejection | PASS in focused source coverage |
| Review continuation | NOT REACHED in real target; no review was required; existing review gates remain authoritative |
| Snapshot publication and integration | PASS; real `finish_lane` integrated and focused integration test passed |
| WorkGroup closure | PASS |
| Goal closure represented by the target WorkGroup | PASS |
| Full hostile simultaneous multi-lane A/B/C decomposition | NOT CLAIMED; not exercised by this targeted run |

## Remaining risk and exact continuation

The earlier real target proves the requested lawful successor lifecycle and
end-to-end completion for an interrupted unfinished lane. It does not prove
simultaneous independent decomposition, nor a review-required successor path.
The normal `scripts/agent-resume.ps1` startup remains required before any
future task, and this source slice remains intentionally uncommitted. The
separate hostile multi-lane acceptance is recorded below.

## Second hostile blind acceptance — successor acquisition passed; closure was not reached

To exercise the current crash-safe implementation after the targeted run, a
fresh disposable project was created at
`C:\Users\Liparakis\Desktop\SynesisHostileContinuation-20260919` from an
empty Git repository. The unchanged blind prompt was used for each provider
process. The target used the locally rebuilt `0.1.0-dev.syn082-overnight-20260919`
launcher and the normal Codex provider installation.

The first producer acquired the implementation/test/packaging lane:

- participant `agt_58cec99f-afcf-32d2-9867-442f3c3499f5`
- intent `6835190d-6fd3-385a-b605-930f61cacbbc`
- WorkGroup `997f3740-6e3a-37e2-ab2b-b60513e4e28c`
- claims `issue_tracker`, `tests`, and `pyproject.toml`

A second blind producer acquired the disjoint `README.md` lane:

- participant `agt_bb96af9b-35d9-31d9-99c0-be87e4ecd743`
- intent `2582fffa-255f-3e2f-9ffe-acd77703aaf0`
- its candidate snapshot was published with `reviewRequired=false`; its
  integration attempt failed and it remained unfinished

The implementation producer's outer Codex process was verified as the target
process and killed at `2026-09-19T01:39:56.0723282Z`; its Synesis MCP and Java
children were also gone. The immediate dry run correctly reported no recovery
action while the configured grace still held. After the existing grace, the
reconciliation dry run became eligible. A later fresh admission prepared and
executed plan `recplan-fa4992137aa14d8bb4cdb3bec4e1c0fd`, execution
`recexec-2d36d804512e402caf7733663daaca35`, with `actionsRequested=2`,
`completed=2`, `failed=0`, and `controlCheckoutModified=false`.

The fresh blind successor was participant
`agt_ca66767e-9215-3e9e-a1bb-c2074b6633d2`. Before acquiring anything,
`get_next_action` projected both recovery-held candidates, their scopes,
acceptance criteria, task and WorkGroup IDs, claim epoch, former owner, and
durable-artifact availability. The agent explicitly chose the implementation
candidate and called the normal generic action:

```text
request_coordination kind=continuation
payload={intentId:"6835190d-6fd3-385a-b605-930f61cacbbc",claimEpoch:1}
```

The first exploratory payload also included an unprojected `workGroupId` and
was correctly rejected by the strict schema. The agent reread the projection
and retried the exact two-field payload; Synesis returned `CONTINUED` with
successor intent `7544488f-cf2c-3efb-b649-548d574f92f5`, claim epoch 2, and
caller ownership. The same blind agent then explicitly continued the README
candidate, creating successor intent `aeb40e14-d9c9-33cd-a6f6-537fdc68fcf3`.
Both former participants became `DETACHED`; no source identity or runtime
binding was reused. The successor's isolated worktree contained the restored
implementation files, and the agent completed 7 tests, editable installation,
and installed CLI help through Synesis-authorized commands.

The run did not reach `finish_lane`. After validation, the normal
`get_next_action({completionRequested:true})` projection remained `IMPLEMENT`
with ordinary read/write/command operations and no server-issued finish
arguments. The agent correctly did not invent a lifecycle call or close the
WorkGroup. The target therefore remains a partial hostile acceptance rather
than a full end-to-end pass. The current source-focused continuation test does
project `finish_lane` after a single successor lane; the hostile run exposed a
separate downstream limitation when one successor participant owns two
disjoint active intents in one worktree.

### Hostile-run classification

| Stage | Result | Evidence |
| --- | --- | --- |
| Continuation eligibility | PASS | Fresh `get_next_action` exposed both held lanes after 2/2 reconciliation |
| Action projection | PASS | Public scope/epoch payload through normal 10-tool surface |
| Successor acquisition | PASS | Two `CONTINUED` responses with new intent and participant IDs |
| Artifact accessibility | PASS | Restored implementation files were read and tested in successor worktree |
| Claim transition | PASS | Old participants detached; successor epoch 2 owned the union of scopes |
| WorkIntent transition | PASS | Source intents replaced by successor intents with task/lineage preserved |
| Review continuation | NOT REACHED | Both target snapshots had `reviewRequired=false` |
| Integration continuation | NOT REACHED | Finish projection was not available |
| WorkGroup closure | TIME BOUND | Agent safe-stopped at the missing projected lifecycle action |
| Durable goal closure | TIME BOUND | WorkGroup remained `ACTIVE`; no forced close was attempted |

This second run is evidence for successor discovery, explicit acquisition,
identity separation, replay/race fencing, and artifact restoration. It is not
evidence that the full simultaneous hostile A/B/C flow has reached review,
integration, WorkGroup terminality, or durable goal terminality.

## Latest current-build review-continuation attempt — partial

The rebuilt distribution was then exercised against
`C:\Users\Liparakis\Desktop\SynesisMandatoryReviewContinuation6-20260919`
through the unchanged blind harness in
`C:\Users\Liparakis\Desktop\SynesisMandatoryReviewContinuationHarness6-20260919`.
The target WorkGroup was
`eaa4da6b-9bb4-390d-a7b3-3015f9defe7f` and the project ID was
`7133079f-a4c9-42bc-a56f-cc53efd0143d`. No target files or `.synesis` state
were edited by the operator; all project mutations came from blind agents or
the normal `reconcile` CLI.

The run supplied stronger review-path evidence than the earlier target:

* Producer continuation advanced the model lineage from
  `0f102f88-d796-3230-8cc0-660a801788a1` / epoch 30 through successor intents
  `630650b4-0863-32c1-b6ae-8dae8d07333b`,
  `b40eece3-a0bf-3ae0-aaaa-e1423acaccef`, and
  `2caf9ccf-839d-328b-aeb4-0d839d3fd48c` under new participants.
* A fresh reviewer continuation advanced the reviewer lineage from
  `11f7b70b-6e1b-3190-a183-d68c97c3f560` to
  `85da36cb-9f15-38ca-ad5c-91eb37264875` and then
  `066d9caf-24cf-3252-a37d-017a5c85c6ab` under new participants.
* Reviewer successor `agt_dc6ccfed-53e4-3ee8-b737-2c423e64b4e3` received
  current grant `5117203b-8b42-387e-91bf-d53a02435605` and reached the exact
  server-projected `SNAPSHOT_PENDING` wait for the producer successor.
  A later reviewer successor consumed grant
  `77ceeadb-a21f-3bc7-8b3f-c805947b9de5` and also reached the exact snapshot
  wait. No review decision was fabricated.
* The blind producer/reviewer sessions eventually stopped before publishing
  the required successor snapshot. A targeted producer process-tree
  interruption removed the Codex process and its harness-owned children.
  The first concurrent reconciliation execution was
  `recplan-e977978e0fef4e588696e9bce229d01b` /
  `recexec-ff1d4c205884458fb847455e04106d78`: 6 requested, 5 completed,
  1 failed, and `controlCheckoutModified=false`. After the concurrent lock
  contention cleared, retry plan
  `recplan-a720ef2a9b0c44d397c7291d0ab24c75` /
  `recexec-e59ad3341caa45cc8a210bf8ea8790db` completed 2/2 with zero
  failures. Final cleanup plan
  `recplan-2e6779617f5f4326850c513e7b772dbb` /
  `recexec-abb2322a6fcb4a8eb98c66b0e862ee8c` completed 6/6 with zero
  failures and left the control checkout unchanged.
* The live run exposed a real late-event ordering edge: an old predecessor
  review grant could remain in the durable audit projection after successor
  migration. The source now ignores any grant whose target intent has a
  successor at review projection, grant-bound completion, request, issuance,
  and consumption boundaries. A focused regression confirms that the late
  predecessor grant is not an executable review action and the successor
  grant remains projected.

The current-build target therefore proves independent review-grant succession,
correct current-grant consumption, stale predecessor-grant fencing, and
crash/reconciliation recovery, but not review decision, snapshot publication,
integration, WorkGroup closure, or durable goal closure.

### Latest classification

| Stage | Result | Evidence |
| --- | --- | --- |
| Continuation eligibility | PASS | Fresh blind sessions repeatedly received public recovery candidates |
| Successor action projection | PASS | Existing `get_next_action`/`request_coordination` surface |
| Producer successor acquisition | PASS | Multiple `CONTINUED` responses with new participants/intents |
| Reviewer successor acquisition | PASS | Reviewer continuations and new review grants |
| Current-grant consumption | PASS | Current reviewer grant consumed; predecessor grant was not used |
| Late stale-grant fencing | PASS | New `AgentNextActionServiceTest` regression |
| Unpublished artifact accessibility | PARTIAL | Recovery worktrees were available, but required successor snapshot was not published |
| Independent review decision | NOT REACHED | Agents stopped at exact `SNAPSHOT_PENDING` |
| Integration continuation | NOT REACHED | No accepted successor review decision |
| WorkGroup closure | NOT REACHED | Group remained active |
| Durable goal closure | NOT REACHED | No terminal goal projection |

This is a bounded partial result, not a full hostile acceptance claim. The
source checkout remained uncommitted and unpushed, and the exact ten-tool
catalog was unchanged.

### Follow-up unchanged-prompt reviewer-continuation probe

After the preceding run was stopped, a fresh blind agent used the original
acceptance prompt against the same target. It first encountered the public
`continuation_available` projection, selected a producer candidate, and
continued intent `ae1b8ff0-a593-3ec5-a7df-d53e057d596b` at epoch 2 to successor
intent `8e376ab2-ed84-3b9d-89ea-b09e1f100afd` under participant
`agt_7c442a83-90c7-3e43-a83d-9a4e58e80618`. This independently confirms that
the current runtime continues to expose and execute the generic successor
transition after the earlier reviewer probe; it is not counted as reviewer
decision evidence because the agent selected a producer candidate and was
stopped at an overlapping claim.

The target was then reconciled through the normal CLI: plan
`recplan-275f621876004b5fb9aea731dad5a57f`, execution
`recexec-b4763f6c4ca4482d96fb86aa6c3fb129`, 2/2 actions completed, zero
failures, and no control-checkout mutation. The final dry run reported
`active=0`, `suspectedStale=1`, `recoveryEligible=116`,
`executableActions=0`, and `controlCheckoutModified=false` for the target's
historical state. This probe does not change the Phase 3 partial verdict.
