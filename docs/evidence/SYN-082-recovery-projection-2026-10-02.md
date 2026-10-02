# SYN-082 recovery discovery ahead of pending integration

## Failure and authority boundary

The registered Codex project FortniteGamingSeven is
C:\Users\Liparakis\Desktop\FortniteGamingSeven, Synesis ProjectId
6dd2304a-56a1-44ea-bcb4-5b37eead5710. It retained an older invoice WorkGroup,
47eb58a3-d639-381b-87c8-126310e804b4, with three RECOVERY_HELD participants and
pending contract 122fd7ba-02ca-3f3b-bd9e-16a78c412e95. The newer score-report
sessions received only integration_pending/WAIT after successful binding.

The setup's coordination status probe inspected .synesis/shared/coordination,
where sequence was zero; agent collaboration uses .synesis/coordination, where
sequence was 164. The read-only collaboration status exposed the actual existing
work. No state was reset, copied or patched. Diagnosis and raw worker responses:
C:\t\FortniteGamingSeven-RegisteredSetup-Diagnosis-20261001.

## Root cause and change

AgentNextActionService returned a nonterminal integration-pump result before
calling continuationAction. A pending request to a recovery-held owner could
therefore hide the continuation needed to recover that owner. The pump correctly
retained the integration fence; it has no caller binding and cannot manufacture
a response obligation.

The fix computes the existing read-only continuation projection once, allowing
an eligible continuation ahead of WAITING/INTEGRATION_PENDING only. Owned request
responses, review actions and explicit completion actions still run first.
Integration conflicts/failures retain their previous priority. Continuation
execution, exact caller binding, claim/version/lineage checks, snapshot restoration,
project append locking and replay semantics are unchanged. The projection itself
transfers no authority or claims and exposes no private recovery reference.
Multiple candidates remain explicit choices, with no preselected command.

## Focused verification

RED: recovery-held contract owner plus eligible queued snapshot reproduced the
bare integration_pending WAIT instead of recovery discovery.
GREEN: five workspace tests passed, covering the regression, existing discovery,
multiple candidate selection, owner-response priority, and non-owner WAIT.
Three public MCP continuation tests passed, including execution/replay and racer
fencing. Compiler, format and architecture checks passed; diff hygiene passed.
The multiple-candidate assertion initially assumed an executable preselection;
it was corrected to the existing explicit-selection contract. No production
change was made to auto-select a candidate.
Evidence: C:\t\Synesis-RecoveryProjectionRepair-20261002.

## Separate packaged boundary

Source HEAD: 4a10ab9d07958b6db6ca56d4f74a87b94324a3e6, master, with this local
source/test repair uncommitted. Source .synesis remains absent.
Package: 0.1.0-syn082-recovery-projection-20261002-005500.
ZIP SHA256: 652F6F6C40CDA1F4280B189D719AEFC2C3F062797058B14BDC90F15EFE645F62.
Source manifest SHA256: 27EF113D06566FC8427A0D8A98A938A484F9030923C881EDB83938D27A0487F2.
All 809 recorded source inputs matched before/after packaging; all 198 bundle
files matched the archive and relevant compiled classes matched packaged JARs.
The previous frozen package remains intact. Build passed with the three existing
configuration-cache warnings. No broad source-suite campaign was run.

## Registered-project packaged verification and autonomous retry

Matching packaged Codex provider installation used an isolated Synesis Java home,
without copying provider credentials. Installer configuration was recorded and
explicitly pinned to the new package, target --project and cwd. The normal Codex
provider remains SESSION_BOUND. Package MCP catalog: exactly ten tools.

The packaged read-only inbox probe on the same registered project returned
continuation_available/RECOVER with three candidates and selectionRequired true,
rather than bare WAIT. The old owners and pending request remained preserved;
no authority was transferred by the probe. Evidence:
C:\t\FortniteGamingSeven-RegisteredRecovery-20261002.

Exactly two ordinary Codex CLI sessions received an identical prompt to discover
and finish existing durable project work. No former participant, role, file,
candidate or action ID was supplied. Both effective gpt-6-luna/medium settings were
verified immediately. Each has its own persistent MCP connection and product
worker context, with a 20-minute deadline. No human coordination or manual state
repair occurs. No messages were relayed between workers.

Live checkpoint: all three old held lanes were continued through normal product
requests. A race for one lane returned ALREADY_COMMITTED/callerOwnsSuccessor false;
the caller received no foreign ownership. Both sessions reached IMPLEMENT.
This is recovery progress, not terminal acceptance. Final run results will be
recorded after the bounded retry finishes and independent validation occurs.

## Final retry result: BLOCKED, recovery projection proven

Both sessions ended normally before their 1,200-second deadlines (exit 0;
neither timed out). Exit 0 describes the provider process, not acceptance.

- A: thread 01a0f97a-60af-7203-af9b-711f867cb928, participant
  agt_c233876f-ae65-372d-9e55-18cef154adb0; CLI successor intent
  47928054-10ef-38c0-b1a4-247bf36cede1.
- B: thread 01a0f97a-606e-7ce3-9cdf-9fa83da6fb69, participant
  agt_eae6ec58-2fc7-3d4a-9b06-9e77dbdabc7f; wrapper successor intent
  73ade1d8-bb34-3768-b592-5af4add8bac8 and calculation successor intent
  ce8e720d-e6bc-3f6a-8134-98fe99209612.

The workers discovered the pre-existing invoice goal, not a new barebones task.
No agent issued apply_patch or published a new snapshot. A could not obtain the
calculation API from its readable worktree. Its early inbox returned
policy_denied/INTEGRATION_REPAIR for the old integration-blocked snapshot. B's
two exact projected finish_lane calls (revisions 186 and 189) returned
SNAPSHOT_COMPLETION_EVIDENCE_STALE. Another poll projected revision 191.
This demonstrates unsuccessful publication; it does not establish which
revision-changing event caused each rejection. Timestamped calls preserve
concurrent A polls around the second attempt. Do not weaken revision fencing
or label ordinary stale-action rejection itself a defect without that diagnosis.

Both workers attempted collaboration_status with an unsupported workGroupId
field and received COORDINATION_FIELD_NOT_ALLOWED:workGroupId. A corrected its
request to payload {}. This is agent/tool-contract misuse, separate from the
supported publication failure. B ended without making that correction.

B ran five acceptance methods; all failed, with eleven assertion failures
including subtests, because invoice_tool.__main__ was unavailable. The final
independent MCP observer's integrated-base worktree discovered zero tests and
returned exit 5/NO TESTS RAN. Neither result is PASS. No final build validation
can establish the requested invoice behavior with those missing deliverables.
Observer git status/diff --stat were empty; this only describes its assigned
worktree, not every protected worker workspace.

Final public state still shows WorkGroup
47eb58a3-d639-381b-87c8-126310e804b4 ACTIVE. The old snapshot
snap_55bded638f6eafdc1a47682d7b27a000 was INTEGRATION_BLOCKED in the worker
inbox; the final collaboration-status snapshot summary omits its state.
The final observer sees two continuation candidates after B's session ended.
No handoff, integrated new artifact, or terminal WorkGroup was demonstrated.

No false non-owner response obligation was observed in captured calls. The
old request was not re-exercised through an actual owner's CONTRACT decision
in this retry, so owner judgment is NOT PROVEN here. Autonomous terminal
completion is NO. No manual coordination-state edits, role assignments,
inter-agent messages, or forced completions were performed.

Durable external evidence includes product-calls.jsonl,
timestamped-product-calls.json, A/B launch/effective metadata/completion records,
final-public-state.json, final-next-action.json, final-validation.json and
final-observer-mcp.jsonl in the registered recovery evidence directory.

The startup/recovery defect is fixed and verified. The remaining outcome is
a nonterminal inherited integration state plus publication failures and agent
contract misuse, not a successful fresh two-agent acceptance. No additional
source repair was made to force this retry through. The unrelated broad-suite
baseline failures were not rerun or encountered.

Final source state: HEAD 4a10ab9d07958b6db6ca56d4f74a87b94324a3e6;
two modified source/test files and this new evidence document; diff --check
passes; source .synesis absent. Both package ZIP hashes were rechecked and
unchanged. No commit or push was performed for this repair.

## Historical-request integration repair (2026-10-02)

The subsequent user-authorized investigation found a second inconsistent gate.
The integration pump's initial pending-request check used liveCoordinationRequest,
excluding only explicit terminal participant states. validateSnapshotMetadata
instead rejected every PENDING request, including preserved history addressed
to detached participants. It could turn an otherwise eligible live snapshot
into durable INTEGRATION_BLOCKED after the initial gate had correctly admitted it.

A new IntegrationQueueTest reproduces this with two detached historical request
participants and a separate live, fully claimed immutable snapshot. RED: the
live candidate did not integrate. GREEN: the same candidate integrates, the
historical request remains PENDING, and the unrelated uncovered-path snapshot
remains blocked. The fix adds the existing liveCoordinationRequest predicate to
the metadata guard; no request is answered, rewritten or deleted. Active,
recovery-held and unknown participants remain conservatively live under the
unchanged shared predicate. Eight focused integration tests passed, including
the existing live-request gate; format, static and architecture checks passed.
An initial Gradle invocation failed to establish its loopback connection; the
existing explicit Java socket-temp setting resolved that environment issue.

Separate package: 0.1.0-syn082-historical-request-fence-20261002-033000.
Archive SHA256: E4BC4804EF6D651BCA302AF2500DA41B95DD290AB82EF02E4A2713147CCE63BF.
Manifest SHA256: 5B61B4C147C38A7939DA8E1D5A41552D237CCF7BDC7CAB90568E4A1DBDD455FA.
All 809 recorded inputs matched before/after packaging; all 198 archive files
matched the bundle and affected compiled classes matched packaged JARs.
Packaging passed with four configuration-cache warnings. Previous archives
were preserved. Detailed source/provenance evidence:
C:\t\Synesis-IntegrationHistoricalRequestRepair-20261002.

CLI resume of the two existing app chats was refused because the desktop
app retained authoritative thread-store writers. No lock was bypassed. Two
fresh ordinary Codex sessions were then launched in the registered target,
with identical discovery/continuation prompts and exact new package, provider,
project and Java-home settings explicitly pinned. Both effective sessions were
verified gpt-6-luna/medium. This is a new SESSION_BOUND incarnation, not claimed
managed continuity. Evidence:
C:\t\FortniteGamingSeven-HistoricalRequestFix-20261002.

- A: 01a0fa05-5ff0-7202-bd5e-2de6d092cc25, participant
  agt_db60c377-1779-3dc6-b40d-2095d157a472. Its continuation race returned
  ALREADY_COMMITTED/callerOwnsSuccessor false. It gained no foreign claims and
  ended after bounded polls without inventing a responsibility.
- B: 01a0fa05-5fd8-7aa2-8165-d77e6c6ca755, participant
  agt_6dbf057e-e832-3677-8234-12425fc3f352. It recovered the CLI intent through
  the projected request at epoch 4, ran five acceptance tests successfully,
  executed the exact publication envelope (revision 304), and integrated
  snap_6dd0dab25f371f3df5b131d4e56f3204. Its completed lane then lawfully WAITed
  for the active peer responsibilities. No code changes were necessary.

Both processes exited normally before their 20-minute deadlines. The project
WorkGroup remained ACTIVE because the earlier app owner incarnations still
held the calculation and wrapper intents; their snapshots remained blocked.
The controller did not seize those claims, requeue state manually, or relay
instructions to answer/wait/resume a peer request. A packaged read-only
reconciliation inspection recorded 18 sessions, active 0, suspectedStale 2,
recoveryEligible 10, ambiguous 2 and executableActions 0; it performed no
mutations. These lifecycle categories must not be conflated with the separate
collaboration participants' public ACTIVE states.

Only the existing global Synesis MCP command was updated to the new verified
package, with other parsed configuration unchanged and a backup retained.
The user was asked to perform the desktop's supported MCP restart because this
toolset has no restart control and retained old owner connections cannot be
replaced by guessed authority. Terminal shared acceptance is still pending
that supported environment step and subsequent autonomous recovery. No further
source repair or coordination-state intervention was used to force a PASS.

The new packaged MCP catalog was checked directly: exactly ten tools. The first
catalog-only observer launch failed because its temporary script generated a
tab in the Java-home option; the JVM exited before initialization, and the
observer timed out after 45 seconds. Correcting that observer script produced
the successful catalog result. This was an observation-helper defect, not a
product regression; it performed no coordination actions. Both previous ZIP
hashes were rechecked unchanged. Final source status at this checkpoint is four
modified source/test files plus this evidence document, source .synesis absent,
with no commit or push for either local repair.

## Post-restart multi-intent completion defect

After the user reported restarting MCP, both existing chats recovered through
fresh product sessions, again verified gpt-6-luna/medium. One participant
agt_8099f11d-591b-3bbb-a3d3-f74c63c5e5ef acquired both remaining disjoint intents
through exposed continuation requests. The other chat's races were fenced with
ALREADY_COMMITTED/callerOwnsSuccessor false. The wrapper snapshot
snap_9f77a2e2d7c22b59dcb36a173c594e0e integrated, while calculator intent
dec1f514-2d97-34f7-a3f0-00dc369d2633 remained active. Subsequent readiness recovery
allocated a new participant, stranding the remaining responsibility with the
former incarnation. No claim was seized. Evidence:
C:\t\FortniteGamingSeven-PostRestart-20261002.

Source diagnosis: both binding completion entry points unconditionally wrote
COMPLETED after a single lane's integration. Integration releases only that
exact lane, so a binding can still own other announced intents. Two regressions
demonstrated premature completion by connection and durable session ID (RED).
The repair checks fresh owned intents while holding the existing project append
lock. Integration passes its already-held lock; other callers acquire it with
the existing bounded primitive. A binding remains unchanged while owned work
exists and completes after the final intent is released. No authority transfers
occur. A held-lock regression also passed, verifying no nested-lock timeout.

The completion service's generic derived-intent claim-release fallback was
removed: exact integrated-lane release belongs to the integration service, and
a generic release could target an unrelated responsibility in a shared session.
No-change completion retains a binding with remaining owned work and returns
its normal continuation, rather than interpreting that retention as an internal
failure. Nine focused workspace checks passed across the three new binding
tests, five queue tests and configured-validation flow; fifteen MCP checks
passed (three recovery, twelve no-change). Format/static/architecture passed.
Initial missing-import/checked-exception compilation errors and one incorrect
MCP test-filter package were corrected; those invocations were not PASS.

Package: 0.1.0-syn082-multi-intent-completion-20261002-041000.
Archive SHA256: D40401CB9DF5C379273F04BBB8DBD3F30F262DF1BE0A6B2D2EDBB1E346CFD6F4.
Manifest SHA256: 5ADB17801A510822C4B2CBC269459E849F8C8409DFB893F24B25EC44AB71F680.
All 809 source inputs and 198 bundle/archive files matched; affected classes and
JARs matched. Packaging passed with three configuration-cache warnings.
Evidence: C:\t\Synesis-MultiIntentCompletionRepair-20261002.

## Fresh-target setup checkpoint

The user explicitly selected a clean disposable worktree while preserving the
existing target. Every old FortniteGamingSeven commit includes tracked project
identity metadata, so the controller used an empty orphan worktree in the same
registered repository instead of copying that state. Branch:
codex/fortnitegamingseven-final-autonomous-20261002; directory:
C:\t\FortniteGamingSeven-FinalAutonomous-20261002.

An initial init invocation rejected the controller's uncommitted README/fixture
as CONTROL_CHECKOUT_DIRTY. Those two controller-authored setup files were moved
to C:\t\FortniteGamingSeven-FinalAutonomous-Setup-20261002 for subsequent normal
MCP publication. No Synesis state was edited. Supported packaged init then
created fresh ProjectId 0fc89be0-d24c-4354-a912-3143df40a601 and its canonical Git
baseline. Known-project discovery warned REGISTRY_FULL. Matching Codex provider
installation attested the manual and passed its health probe, with optional
hook/trust validation still DEGRADED/REVIEW_REQUIRED.

The actual packaged MCP bootstrap rejected that linked control root with
PROJECT_ROOT_PIN_INVALID before task declaration or file mutations. Inspection
confirmed this is an intentional main-checkout restriction covered by existing
root-authority tests, not a reason to weaken that boundary to produce PASS.
The linked setup is preserved. A clean standalone checkout of the same
repository has been proposed to the user; acceptance remains pending that scope
choice. Source .synesis remains absent; no source commit or push occurred.

## Final autonomous acceptance: PASS on approved standalone checkout

The user approved a clean standalone checkout. The controller used a no-checkout
clone and empty orphan branch, with no checked-out prior Synesis metadata. The
packaged product created ProjectId 3bdc40df-ae66-48c2-8926-cf1f7a287828 at
C:\t\FortniteGamingSeven-FinalAutonomous-Control-20261002. The immutable invoice
fixture and README goal were established through normal MCP claims, publication
and integration before the implementation agents started.

Two fresh ordinary CLI sessions, both verified gpt-6-luna/medium, independently
chose and negotiated the calculation-library and CLI responsibilities. API
request 43530ef2-32ed-496c-bddb-92572d6ce6c2 belonged to the library participant
agt_07162a96-ed38-3108-b28f-20da82120f59. It received REVIEW_CONTRACT and retained
substantive judgment, revising an earlier scope request and accepting the API
request through supported responses. Non-owner CLI participant
agt_971a9b07-89e4-365d-a5a9-057471dbd27b received IMPLEMENT on its own claimed
CLI task during the pending request, with no foreign pendingCoordination item.
This is the explicitly permitted independent-work case, not a claimed packaged
pending-WAIT witness. Seventy-one captured MCP calls contained three projected
response obligations, all addressed to the projection's current participant.
No foreign obligation was observed.

The library integrated snap_9410347696b64a248e2f780aea0e85e4; the CLI integrated
snap_8ac571b416f0fec87b6ae6d5e9ee29b9. The library actor WAITed for peer work,
then observed terminal completion through ordinary polling. No controller
message relayed a response or instructed either worker to wait/resume/publish.
The demonstrated dependency is the agreed API and integrated artifact consumed
by the CLI; no separate structured capability-request lifecycle was claimed.

Independent packaged MCP projections verified implementation WorkGroup
702e18cc-e2af-4792-87a1-ea3ca45df470 COMPLETED (version 2), both implementation
participants COMPLETED with no claims, and both snapshots INTEGRATED. Independent
final tests passed all five immutable acceptance cases; compileall passed. The
fixture hash remained a02829e56e97f78c0b38c99eb75dc0092d6fd713ecaf6a3aa5c8f98ffaa3e8d1.
The final implementation paths were disjoint and inspected. Final target HEAD:
7b15448f1cb2de08fef324afa2fdb72d7d1d5235. Generated bytecode was preserved.

The five owner/continuation regressions were rerun on the final source and passed;
final focused source evidence comprises fourteen workspace checks and fifteen
MCP checks, with format/static/architecture passing. Provenance was rechecked
after acceptance; all 809 inputs and 198 archive files still matched the frozen
0.1.0-syn082-multi-intent-completion-20261002-041000 package. The global executable
entry alone was updated to that accepted package, preserving its existing target
argument and other settings with a backup. The accepted CLI sessions used their
explicit recorded standalone-project overrides.

This PASS applies to the approved fresh run, not to the preserved old target or
rejected linked-worktree setup. Full report, chronology, ownership audit, raw
product calls, launches, terminal projections and independent checks:
C:\t\FortniteGamingSeven-FinalAutonomous-Evidence-20261002\final-report.md.
Source remains master at 4a10ab9d07958b6db6ca56d4f74a87b94324a3e6, with seven
modified source/test paths plus this evidence document; source .synesis absent,
no source commit, no push, and no manual Synesis-state repair.

## Desktop-app CONTRACT guidance repair and CSV acceptance

Two actual project-attached nigatrontest app chats stopped at overlapping claims:
REVIEW_CONTRACT advertised respond_coordination with arguments {}, while the
installed manual required exact executable arguments. The tool description
already documented response kinds/statuses, but that did not resolve the manual
and projection inconsistency for these ordinary sessions.

The repair reuses workflow.decision/reviewDecision. Owned pending requests now
provide responseTemplates containing only their server-sourced kind/request
identity, allowedResults, required coordinationStatus and optional proposal.
No status is preselected. The reducer omits the invalid empty executable response;
the manual distinguishes deterministic continuation from interactive judgment.
Existing target/binding/status authorization remains unchanged and non-owners
receive no decision form. Nine reducer, one owner/non-owner, one manual and one
catalog tests passed, plus format/static/architecture/diff checks. The new
regression first failed before the repair.

Package 0.1.0-syn082-contract-guidance-20261002 was verified against all 809 source
inputs, 198 bundle/archive files and affected classes/JARs. ZIP SHA256:
FE66D5ACFB42CB54A7CA3DEE06D462F0057EFD40041926834384B5727F871099.
Manifest SHA256: FB180AA6A02459AC77AAC8E67C31B98999BE0356F578250A4E9AB0EE4D68BCE0.
A staged JAR mismatch after final manual wording was caught and resolved by a
normal rebuild before freezing. Prior accepted artifacts were preserved.

After the user restarted MCP, the same two visible app chats independently
recovered implementation and disjoint documentation/tests responsibilities.
CSV WorkGroup d541643a-3c02-4b6d-addf-638f16d7080c reached COMPLETED, version 2;
both participants completed and snapshots snap_bd17fa53d30407ece601ed6bb1ae378a
and snap_ba2769447c0ba27159793f1f173e18db integrated. Independent packaged MCP
checks passed nine tests, compilation and five additional CSV/JSON behavior cases.
No CLI coding-session replacement or manual coordination-state repair was used.

Restart also changed ownership distribution, so the successful app retry does
not alone isolate the decision-form repair as the cause; its exact shape and
non-owner exclusion are covered by the focused regression. Full source/provenance
evidence: C:\t\Synesis-ContractGuidanceRepair-20261002. App retry report and raw
product/session evidence:
C:\t\nigatrontest-app-contract-guidance-retry-20261002\report.md.
Source .synesis remains absent; no source commit or push occurred.

## Final cold-start visible-chat check and publication authorization

A further user-requested run used the empty registered finalfinalfinaltest
project with only Git/product/provider bootstrap. Two project-attached visible
app chats received the same word-frequency CLI outcome, with no starter code,
tests, roles, file assignments or follow-up prompts. They independently chose
implementation and documentation/test scopes and reached WorkGroup
3f21236e-2e7d-3212-bbcc-1a29c4ba3395 COMPLETED. Both snapshots integrated and both
participants completed without retained claims. Independent packaged validation
passed four project tests (explicit unittest discovery from tests/), five separate
behavior cases and compilation. Default discovery's zero-test result was
preserved and was not counted as PASS. No source changes or artifact rebuild
were needed for this final run. Full report and raw evidence:
C:\t\finalfinalfinaltest-app-20261002\report.md.

The user subsequently explicitly authorized committing and pushing the source
repairs and evidence. Earlier no-commit/no-push statements above describe their
recorded checkpoints. Tested package provenance remains anchored to its actual
pre-publication source HEAD plus recorded input hashes; it is not relabeled as
having been built from the later publication commit.
