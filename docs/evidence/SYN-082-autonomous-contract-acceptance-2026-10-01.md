# SYN-082 autonomous CONTRACT acceptance — 2026-10-01

Final acceptance verdict: **BLOCKED / PARTIAL**. The ownership projection and
owner judgment were observed through packaged product interfaces. Correct code
integrated and passed objective tests, but the WorkGroup did not reach terminal
completion. This is not a PASS.

## Artifact boundary and discovered defect

Source branch/HEAD remained master /
813b1f1c5c3572d895444850b1e22ad1f4cc114b. Starting dirty paths: 157.
Source .synesis remained absent; no source Synesis MCP, Git commit, or push was used.

The original frozen artifact was
`0.1.0-syn082-projection-20261001-025045`. Its ZIP SHA-256 remained
`4C0FA8C6534E9A45E1504467D33F80DA3B14879FF3E95FEEA85D1E2C386F086D`.
Its provenance SHA-256 was
`83C1A3406182F53CC21D8713F0E03395EC127F1F15F3D4959BA1839A44756628`.
All 198 bundle files matched the ZIP before acceptance. No old package contents
or the older frozen Stage 4 target were modified.

The first real acceptance exposed a genuine public MCP contract mismatch: six
calls conformed to the input schema emitted by the packaged server, but its
kind-specific runtime rejected fields allowed by that merged schema. Examples
were intentId/targetParticipant/goal in contract_request, and intentId in
coordination_response. Raw schema and runtime rejection pairs are retained in
the first attempt's `advertised-schema-mismatches.json`. The first attempt failed
before its true CLI owner exercised the pending decision.

Under the user's genuine-defect exception, two existing source files were
changed: McpToolCatalog.java and McpToolCatalogTest.java in mcp-contract.
The catalog now couples contract_request and coordination_response to their
strict payload fields through oneOf and explicit descriptions. Runtime authority
checks and semantic owner choice were unchanged. Existing architecture guidance
was updated. The two regressions failed before repair, then all 13 catalog checks
and 2 relevant MCP catalog/admission tests passed; strict compilation, format,
architecture and diff checks passed. No broad verification campaign was repeated.

A separate package was built and frozen:
`0.1.0-syn082-contract-schema-20261001-042929`, under
`cli/build/platform-bundle/`, with its ZIP under `cli/build/distributions/`.

| New payload | SHA-256 |
| --- | --- |
| ZIP | 2AED7E0F4A35F43ABB2EA59EEB8D64F10409C02FA7EEA641D5ABDB98A05F711C |
| Native synesis-mcp.exe | EC4407FDD92E3469723A698828DBD5A5523076D217D8D0C966B0CC72B9207048 |
| CLI JAR | 2915B363240EE2D6E772C55F54D847AD1AE333E854B568AD8A32FB4BF4A7130C |
| mcp-contract JAR | D19B5D6F182ADBB5A852E5A630CF2288DC5F4D9C871136599FA2D2509D21139C |
| workspace JAR | F927E08E680DE742D955087D8E5873F144878D2D17ED9E1B74575CBDBC3592A0 |
| McpToolCatalog.class | 6F01EA457A62B7ADA9B338B18CC6C2729028AA506DE31DCF53748D2D3E65BA66 |
| 809 source input manifest | 0A744CA5A3D39E26CBE81F145F12DEA9422319789E04B33678FB01105292ECDE |

Build, bundle and ZIP JAR bytes matched. Catalog class bytes matched compiled
output; all 198 bundle files matched the new ZIP. Complete provenance is retained
under `C:\t\FortniteGamingSeven-SchemaRepair-20261001\provenance.json`.

## Projects and ordinary sessions

The registered Desktop FortniteGamingSeven folder was initially empty. The first
attempt used that folder, product ProjectId 6dd2304a-56a1-44ea-bcb4-5b37eead5710,
WorkGroup 47eb58a3-d639-381b-87c8-126310e804b4. It is preserved as the schema-defect
specimen, with report and raw evidence under
`C:\t\FortniteGamingSeven-Acceptance-20261001-032407`.

The fresh repaired-package project was `C:\t\FortniteGamingSeven`, product
ProjectId cd98deef-3842-4a8a-8b8d-d712aee15ac1. Its first accepted ordinary task
announcement created WorkGroup 519f79d2-b663-3210-b77a-86130f921e98. The controller
constructed no durable IDs, claims, WorkGroups, capability state or authority.
The product created administrative Git baselines and normal publication commits.
The controller executed no Git commit or push.

All ordinary Codex exec sessions received the same shared task prompt SHA-256
`9616F50876D5DFD6F78EAB778F660034BFF3FB31F0D6E1D001940E5BB18E240E`.
They chose their own scopes. Model/effort were explicitly pinned and verified
from the exact provider session metadata as gpt-6-luna / medium or high; first
attempt sessions also included verified low effort. No human role/file selection
or peer-message relay was used. Per-session process limits were 25 minutes,
MCP startup 45 seconds, tool calls 120 seconds, and waits were bounded.

The real coding goal was a standard-library Python decimal invoice calculator,
CSV-to-JSON CLI, immutable public acceptance fixture, independent tests and docs.
It included exact discount/tax rounding, invalid input behavior and objective
examples. No external service or credential was required by the application.

| Post-configuration-retry label | Product participant | Self-selected work |
| --- | --- | --- |
| E | agt_7c86688e-e0ba-36be-9af9-b7f28367d8d9 | test_acceptance.py wrapper |
| F | agt_86c63742-f732-398c-bf34-9827945b7b4f | README.md and acceptance_check.py |
| G | agt_318f3362-b100-30b0-a400-3a7087d32a23 | test_cli_errors.py |
| H | agt_c0bc06b0-6dbb-3cc9-886a-ca5bbbf2629f | test_calculations.py |
| I | agt_70d596f7-266f-35f0-8f40-f96af70d0521 | invoice_tool subtree |

Each had a distinct exact product-created session/worktree binding. Original
launch files, effective model metadata, normal MCP replies and later successor
bindings are retained under
`C:\t\FortniteGamingSeven-Acceptance-SchemaFixed-20261001-043529`.

## Configuration and retries

The selected native launcher and explicit --project arguments were generated by
the packaged provider installer and supplied through normal per-process Codex
config overrides. Global user config and exec rules were ignored for these
ordinary sessions. No credentials or managed attachment proofs were copied.
Actual continuity was SESSION_BOUND; MANAGED_CONTINUITY was never asserted.

A prerequisite retry was needed because Codex's MCP child did not inherit the
installer's isolated Java user.home. All three initial repaired-package sessions
were blocked by MANUAL_MODIFIED_OR_OUTDATED before any claim was accepted.
Product provider status independently showed ATTESTED in the installation home
and outdated in the default home. This was a harness/configuration failure.
Explicit mcp_servers.synesis.env.JAVA_TOOL_OPTIONS and cwd corrected it; the
package remained unchanged, and fresh ordinary sessions then acquired claims.
The supported env configuration is documented in the
[official configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).

Global config drift was observed rather than normalized: starting hash
7F45EBCAA651B6E0AFAFB48FE6CBA7A586F6D5F18C243995934DD515A51C438F,
intermediate 1E5ED7195555A8F127E95AD943981ADB2FDEEC18D7309C151A4E65087CBA166A,
last checkpoint 0D606D39463D8A2BA77B6DBCA39F95E6E9A9C81DF88709A684AD9AD502FC10B4.
The controller did not edit the global config. Explicit overrides preserved the
artifact/project selection. Existing REGISTRY_FULL, experimental/native-hook
trust, command retention and direct-package migration warnings were recorded.
No migration, cleanup or manual repair was executed. Known source workspace
denial-reason/Javadoc failures were not rerun or treated as acceptance regressions.

## Ownership, decision and propagation evidence

The first package's real integration-pump regression was exercised by pending
request 122fd7ba-02ca-3f3b-bd9e-16a78c412e95, targeting its actual CLI owner C.
A had a published candidate; non-owners A/B received integration_pending / WAIT /
get_next_action({}), without a fabricated respond obligation. C received complete
owned request context and retained its interactive decision.

In the repaired-package run, H lawfully requested the calculator interface from
I's current owned intent 18fac13a-862a-3f55-9f65-6706c23ccbcb. Request
60be3b13-3c8b-4099-9d12-08d6a20ab32a targeted I. I's owner inbox projected
REVIEW_CONTRACT with that exact request, and I explicitly responded ACCEPTED,
agreeing to export calculate_invoice(rows). The reciprocal API proposal
d0a8b888-f472-4289-95f2-5baff3090a83 targeted H; H independently accepted the broader
calculation API. Overlap negotiations also produced genuine REVISED judgments.

The final structured audit covered 35 owned response obligations and found
zero cases where an obligation's target differed from currentParticipant.
There were 38 owner-response WAIT projections in the repaired run. The distinct
reciprocal requests addressed to contenders were their own negotiations, not
responses impersonating another task owner. Invalid copied/typed UUID attempts
were rejected by REQUEST_NOT_FOUND or INTENT_NOT_FOUND; no authority fallback
was used. Detailed audit is `ownership-audit.json`; raw product replies are
`product-calls.jsonl` and original per-session JSONL.

## Integration, recovery and remaining block

F integrated documentation/fixture snapshot snap_7050d0221ddd76ffa2520a65854e64a8.
That control advance left live ordinary dirty lanes stale. Exact projected
ensure_session({}) attempts returned WORKSPACE_STALE_DIRTY, while publication
attempts encountered SNAPSHOT_COMPLETION_EVIDENCE_STALE. The workers preserved
their claimed work and stopped unsafe mutation. These were actual packaged
responses, not source fixture results.

The controller reported the stall and started fresh blind ordinary sessions with
the unchanged shared goal. They received no abandoned participant, scope, grant,
candidate or action ID. J used the existing continuation protocol for E's wrapper;
K used it for G's CLI tests; L used it for I's package and H's calculation tests.
Product replies issued fresh successor identities/epochs and callerOwnsSuccessor.
A concurrent duplicate returned ALREADY_COMMITTED and callerOwnsSuccessor false.
No managed continuity was faked and no .synesis state was edited.

J integrated snap_66e114d7eeae78a656450921c40af940. L integrated the actual package
as snap_95edc5634fba2d6fe69afa749ca62906. The remaining test/review obligations did
not close. B in the first attempt and later J/K/L reached their 25-minute process
deadlines; the launcher stopped only their exact process trees. The product's
latest public state was WorkGroup ACTIVE, with announced CLI-test and calculation-
test successor intents. Read-only diagnostics and raw retry loops are preserved.

Final product-mediated validation executed compileall, unittest discovery and
the explicit acceptance module against the integrated application. Exit code was
0: five objective tests passed in discovery and the same five passed explicitly.
The supplied fixture's product contentHash matched
B648C38541D5F47F085C9C401C74F6EC4C9460E8B484D6B619EAE381ACE9B99B.
The calculator/CLI, fixture/docs and wrapper were present through normal product
integration. The additional CLI/calculation test lanes remained unpublished;
their work was preserved. This is correct integrated code evidence, not terminal
workflow acceptance.

## Direct answers and final boundary

- Did a non-owner receive an obligation to answer someone else's request?
  **No in the observed runs.** Exact-target audits and the original integration
  WAIT witness support that statement.
- Did the true owner retain the CONTRACT decision?
  **Yes, and I exercised it with ACCEPTED** for request 60be3b13…; semantic status
  was not selected by Synesis or the controller.
- Did the autonomous workflow subsequently finish correctly without manual state
  intervention? **No.** Normal continuations and correct code integration were
  demonstrated, but the WorkGroup remained ACTIVE with unresolved test/review
  lanes. Final acceptance remains BLOCKED.

The schema defect was repaired under the genuine-product-defect exception.
The remaining workspace/completion/review stall is recorded for separate focused
investigation; no authority/freshness check was weakened to force closure.
No manual CONTRACT response, ownership rewrite, recovery-plan execution, source
Synesis metadata, commit or push occurred. Both packages remain identifiable,
and the original older Stage 4 target remains frozen.
Final source state: master at the same HEAD, 158 dirty paths (the one new path is
this evidence file), source .synesis absent, and all 809 recorded new-package
source inputs still matching their manifest. Original and repaired ZIP hashes
remain unchanged.

## Goal continuation recheck — 2026-10-01 09:54 UTC

The preceding BLOCKED checkpoint is retained. A fresh packaged MCP observation
offered two available, recovery-held continuations with durable artifacts. Two
ordinary sessions M/N were started with the unchanged shared prompt and original
25-minute process limit. Both effective sessions were verified as gpt-6-luna/high.
No participant, file, candidate or response was supplied to either agent.

M (`agt_ec3f02ae-fc98-3071-ba08-35b37d08e6b7`) acquired calculation-test successor
`513ca343-5d29-38c0-9d26-5f80aa6956d1`, epoch 3. N
(`agt_7ddb263c-3bf3-3c81-8bc6-dad7a2bbed21`) acquired CLI-test successor
`06c9e606-9b99-34c7-a125-c91c780b6c48`, epoch 3. M's duplicate attempt to acquire
N's continuation returned ALREADY_COMMITTED/callerOwnsSuccessor=false.

M passed ten local tests and compileall, then followed its exact publication
projection. Snapshot `snap_f3149ff2f6c2ea4d99666d7aeb71ee04` integrated
`test_calculations.py`, commit `76e3079bb2ecaf7641467d8387a5aab7697201e9`.
N passed nine local tests and compileall; its projected publication was rejected
with SNAPSHOT_COMPLETION_EVIDENCE_STALE. Final observer validation through
packaged run_command passed ten integrated discovery tests, the five explicit
acceptance tests, and compileall. Four snapshots are now integrated. N's CLI-test
lane remains ANNOUNCED; the WorkGroup remains ACTIVE.

The next precise product failure was observed after M's publication. Its completed
binding received a concrete work_group_join review-admission projection for N,
without an active reviewer intent. M followed it. Request
`2a141ff9-7667-4ea9-b766-737fed555d34` targeted N, whose owner inbox retained
REVIEW_CONTRACT. N's explicit ACCEPTED response was denied with
REVIEWER_INTENT_REQUIRED. N subsequently chose a lawful REJECTED response,
explaining the missing reviewer intent. Synesis then projected another admission
request with the same missing prerequisite. Further rejections were followed by
workspace_stale/ensure_session/CONTROL_BASE_ADVANCED recovery, while M alternated
the repeated admission projection with owner_response_pending/WAIT.

Source inspection explains the admission mismatch: AgentNextActionService's
reviewActions branch at line 1110 emits optional review admission when
callerReviewers is empty and a completed-group filter is present. The resulting
reviewAdmissionAction projects work_group_join directly. WorkIntentService's
validateReviewAdmission at line 183 correctly requires an active matching reviewer
intent before accepting a REVIEW request. That authority requirement was not
weakened. The existing completed-review workspace test proves request creation
and WAIT, but does not exercise the owner's subsequent ACCEPTED decision.
Classification: product projection/prerequisite defect, separate from the
earlier schema defect and unrelated source-suite baselines. No source repair or
package regeneration was performed during this continuation.

At this checkpoint M/N are confirmed live through their exact launch process
handles and remain inside their existing deadlines. This is a verified wait,
not terminal completion. The updated audit covers 41 owner response obligations
with zero non-owner violations. Evidence is under the acceptance root's
`goal-recheck-20261001` and `goal-recheck-after-publication-20261001` folders,
plus M/N raw logs, launch records and effective metadata.

The observer's first launch had an incorrectly escaped Java home and a local
script NameError; both were corrected before a successful package observation.
These were observer failures, with no manual project-state change. The package
startup banner reports 0.1.0-SNAPSHOT / bc334ac; this does not match the archive
label/source boundary and is recorded as embedded metadata drift. Payload hashes
and the captured build manifest remain the provenance authority.

## Final continuation audit — 2026-10-01 09:58 UTC

The latest acceptance verdict remains **BLOCKED / PARTIAL**, not PASS. The same
nonterminal completion/admission condition persisted through the original goal
turn and both subsequent goal continuation audits. Successful intermediate
integration did not satisfy the terminal requirement.

Four review requests were created by following M's repeated product projections:
`2a141ff9-7667-4ea9-b766-737fed555d34`,
`abcb8d8f-f19d-45da-8b98-46d4e0e3fe14`,
`b7d26a97-a88a-4302-a277-c94f11a85618`, and
`67224d63-93eb-48f9-9c26-c9a29c43f80a`.
N's first ACCEPTED response was denied; two lawful REJECTED responses and one
REVISED response completed normally. Neither those decisions nor exact workspace
recovery produced a usable publication continuation. The next admission again
lacked an active reviewer intent. Unsupported field attempts in N's later
coordination calls were denied; they did not confer authority or alter ownership.

The experiment controller stopped the exact verified M/N Codex process trees
after reproducing this cycle, before their original 25-minute deadlines. Their
launch wrappers recorded exit 1 and timedOut=false. This is an explicitly
recorded human/controller intervention to end a failed experiment, not automatic
completion, a timeout claim, or continuation of either participant. No project
state was repaired or marked complete. Termination verification and reasons are
in `goal-recheck-after-publication-20261001/controller-termination.json`.

A subsequent packaged MCP observation confirmed WorkGroup
`519f79d2-b663-3210-b77a-86130f921e98` remained ACTIVE, with the CLI-test successor
intent ANNOUNCED and four snapshots present. The observer's unannounced binding
received coordination_intent_required; it did not acquire a task or respond to
requests. Final integrated test evidence remains ten discovery tests, five
explicit acceptance tests, compileall, exit 0. CLI-test work remains unintegrated.

The final audit covers **44** exact owner-response obligations, **zero** non-owner
violations and 69 owner-response WAIT projections. The first package's explicit
integration_pending WAIT witness remains separate evidence; the repaired-run
audit does not claim that exact integration-pump branch was separately observed.

Direct answers remain: no observed false non-owner obligation; yes, the true
calculator owner retained and exercised ACCEPTED CONTRACT judgment; no, the
autonomous workflow did not reach terminal completion without intervention.
The successful code and ownership checks do not make this final acceptance pass.
The admission projection/prerequisite mismatch needs a separate focused product
repair and another verified package boundary before final acceptance can close.

Current source remains master/813b1f1c5c3572d895444850b1e22ad1f4cc114b, with 158
dirty paths, source .synesis absent, no controller commit and no push. All 809
repaired-package source inputs still match. Both ZIP hashes remain unchanged.
Known broad-suite baselines were not rerun. Final structured observations and
the source/package recheck are preserved in `goal-final-recheck-20261001`.

## Admission/retry repair and new bounded acceptance — 2026-10-01

On the resumed goal, the reproduced admission defect was repaired under the
user's genuine-defect exception. Three production files changed:
AgentNextActionService, AgentWorkflowReducer and AgentTaskCompletionService.
Automatic admission no longer selects a reviewer role for a completed participant
without an active reviewer intent. A completed lane instead keeps read-only WAIT
while its WorkGroup is active. Existing admitted reviewers, grants and owner
requests retain their continuations. Stale finish evidence remains rejected, but
its normal response now carries a concrete fresh
get_next_action({completionRequested:true}) workflow; neither stale arguments nor
a sticky completion flag are reused.

The three regressions failed before repair. The affected next-action/reducer
classes passed 31 checks in the first broader focused run; review ordering added
three passes and the unchanged known denial-reason baseline failure. Final
verification of the completed WAIT behavior and actual MCP response decoration
passed ten workspace tests and three MCP tests. Compiler, format and architecture
checks passed. The first MCP retry regression exposed missing response decoration
and failed before that final correction. No authority, ownership, epoch or
revision check was weakened. Before copies and all red/green XML/logs are under
`C:\t\FortniteGamingSeven-AdmissionRepair-20261001`.

A separate frozen package was built:
`0.1.0-syn082-admission-retry-20261001-103712`.
ZIP SHA-256: 492077E137FB8D1C0C852BC9AF0E65065B21DD56B97A944641AC319014FC5926.
Source manifest SHA-256:
3792BDBEF3EEC512828753B52C511E5CB3735C9283D0190105BECB79F0064E2F.
All 809 source inputs were recorded; 198 bundle files matched the archive;
affected compiled classes matched the packaged JARs. Both previous packages were
preserved. Packaging succeeded with three existing configuration-cache warnings.

Fresh project: `C:\t\FortniteGamingSeven-Acceptance-AdmissionFixed-20261001-103712\project\FortniteGamingSeven`.
ProjectId: 37ee86ef-412f-4739-a09c-5204b48b4f12.
WorkGroup: 823dbed3-1150-3e2e-b643-a404599c6b10.
Evidence root: `C:\t\FortniteGamingSeven-Acceptance-AdmissionFixed-20261001-103712`.
The packaged initializer created all coordination state. An initial controller
command assumed a nonexistent synesis.exe; it was corrected to packaged
synesis.cmd before product initialization. Provider installation was ATTESTED,
MCP health passed, with the same experimental/degraded native-hook support.
Explicit per-process command, project arguments, cwd and Java home were used.
The actual catalog still exposed exactly ten tools. Global configuration was
ignored for these launches and was not normalized.

A/B/C received the unchanged shared prompt; all effective sessions were verified
gpt-6-luna/high. Their independent choices were CLI __main__.py (A), reusable
calculation.py (B), and README/immutable acceptance fixture (C). B was the actual
owner of request 8e29c873-ac00-44fd-ade7-dc2e0b5c9d6a, issued lawfully by A for
the calculation interface. B received REVIEW_CONTRACT and chose **REVISED**,
specifying InvoiceLine inputs and JSON-ready money strings. This must not be
reported as ACCEPTED. No non-owner response obligation was observed.

The repaired retry was exercised in the real package: B's two stale finish
attempts received the exact fresh completion poll, then snapshot
snap_2156575635f5be4c8a375b442745c0da integrated calculation.py. B remained in
read-only WAIT rather than receiving fabricated review admission. A recovered a
clean workspace and read the published calculation result. C's dirty document
workspace subsequently failed exact recovery with WORKSPACE_STALE_DIRTY and
ended, preserving work. C's separate attempt to extend its task declaration also
hit an overlap with its existing README/fixture claims; no ownership bypass was
used. This task-declaration failure is distinct from the stale-workspace failure.

A fresh blind session D received the same goal with no former participant,
candidate, scope or action hint. Synesis offered C's continuation; D independently
selected it and received successor 67f32f56-5526-33ef-ab3e-00a1235b1c09, epoch 2,
participant agt_bb633571-2ffd-38d9-8dbf-025b00ec144c and callerOwnsSuccessor=true.
D integrated README/fixture commit fe7d270 through the normal product surface.
D later independently announced a real reviewer intent with a disjoint
integration_review.md claim; this was not the removed automatic role assignment.

The run nevertheless did not converge. A's later public binding was participant
agt_9a585c7f-7c5d-36ea-b901-317bae1a5095 while its original CLI intent still
belonged to agt_3ad639fb-113f-3eda-8092-d46737192d14. The normal projection returned
coordination_intent_required. A subsequently acquired a separate discovery-wrapper
responsibility, then encountered stale-workspace recovery after documentation
integration. No actor borrowed the old CLI authority. Classification: a
provider/task-binding continuity issue requiring further source diagnosis; its
root cause is not claimed proven. Dirty recovery remains a product workflow
limitation, and the overlap/task changes also require separating agent declaration
behavior from a product regression. No further source repair was made to mask
these results.

A/B/D reached their configured 25-minute deadlines. C ended earlier on the
fail-closed recovery response. No controller stop was added to this new run;
only the predeclared launcher deadlines terminated those exact process trees.
All four launches have final completion records. Current raw product state was
WorkGroup ACTIVE, with CLI/discovery/reviewer work unfinished.

Product-mediated final checks: compileall passed; unittest discovery found zero
tests and returned exit 5; the five explicit acceptance tests failed (exit 1,
11 assertion/subtest failures) because the CLI was not integrated. The immutable
fixture hash remained B648C38541D5F47F085C9C401C74F6EC4C9460E8B484D6B619EAE381ACE9B99B.
Read-only Git history confirmed only calculation and documentation/fixture
publication. Therefore this new acceptance is **FAIL / PARTIAL**, and final
autonomous acceptance remains BLOCKED. The successful earlier project's ten
tests are not attributed to this new project or package run.

The source remains master at 813b1f1c5c3572d895444850b1e22ad1f4cc114b, with 159
dirty paths: the preexisting 157, the evidence report, and the now-modified MCP
regression test. No source .synesis, controller commit, push or manual acceptance
state repair occurred. The source repairs and package are concrete progress;
the requested terminal autonomous result remains unproven.

## Repeated recovery binding defect — source verification

The next bounded source investigation resolved the earlier unproven continuity
diagnosis. A's first clean recovery at raw log line 47 retained session
a873fe05-1f2b-484a-abf2-6b8fdae99cf2 and relocated it to a path with
`-r-bd704c2843c1`. Its participant and CLI intent remained intact afterward.
The subsequent ensure at line 127 returned WORKSPACE_BINDING_MISMATCH. Empty
recovery at line 131 then allocated session d998881a-1039-4056-8099-8383a1d8e868,
whose participant did not own the original CLI intent. Thus the first clean
recovery itself did not lose authority; the following ensure broke idempotence.

ProviderSessionBindingService.ensure called withWorktree even when the recorded
worktree was fresh. withWorktree's default allocation uses the original session
path, whereas reallocatePreservingSession uses the same session plus a recovery
suffix. The subsequent call replaced the relocated binding path with the original
path while retaining the new base commit, causing verification failure and the
later new-binding allocation. This is a genuine product defect, not a reason to
borrow the abandoned participant's authority.

The existing clean-recovery regression was extended with three subsequent ensures
and assertions on session, supervisor, worker, worktree identity/path, branch,
base commit and strict verification. It failed before repair. A six-line fresh
binding branch now reverifies and retains the exact registered current worktree
instead of reconstructing its original allocation. Seven focused binding tests
passed, including exact-connection isolation, sibling/unknown caller rejection,
dirty-workspace refusal and managed continuity. Two public MCP recovery/command
scope tests also passed, with a repeated ensure added to the recovery test.
Compiler, format, architecture and diff checks passed.

Only the binding service, its regression test, the existing MCP recovery test and
related documentation changed in this step. Before copies and red/green evidence
are under `C:\t\FortniteGamingSeven-BindingRepair-20261001`.
Dirty-workspace refusal and all identity checks remain intact. No packaged
acceptance is claimed for this latest source repair: a new verified package and
bounded ordinary-session run are still required. Previous packages and acceptance
state remain untouched; no source .synesis, commit or push was introduced.

## Binding-fixed package and live acceptance checkpoint

The verified source repair was packaged separately as
`0.1.0-syn082-binding-idempotence-20261001-120241`.
ZIP SHA-256: 518694F463839FC47E22D48B16006A7591EF53B19F607488155FF54CCCE171C6.
Manifest SHA-256: DACE9406E35E7813F44AE3453865472076D4CA0895DDD32B45A469EC45DCAFB7.
All 809 inputs and 198 bundle/archive files were verified, including the binding
service's compiled classes and all earlier repaired classes. Provenance is under
`C:\t\FortniteGamingSeven-BindingRepair-20261001`. Previous packages stayed frozen.

A clean, shorter external target was initialized at
`C:\t\FGS4\FortniteGamingSeven`, ProjectId
7075d59d-4ce2-446d-9c6f-097a7617eda2. The path change was recorded to reduce Windows
path-length variation; no prior state was copied. Actual provider mode remained
SESSION_BOUND, with explicit package/project/cwd/Java-home configuration and
ATTESTED integration. The real catalog contained exactly ten tools.

Four ordinary sessions received the unchanged shared prompt and independently
selected responsibilities. All four effective sessions were gpt-6-luna/high.
WorkGroup 3a543c59-8be6-3aad-b304-fbd001b6c467 was product-created. A chose README;
B eventually chose CLI/test_cli.py after disjoint-claim denials; C chose the
calculation module; D chose the immutable fixture/discovery bridge. Roles and
paths were not supplied by the controller.

B's real interface request 57967bc0-bdfb-4eea-88b2-1ddb7eb55472 targeted calculation
owner C, agt_4a0a8797-0c9e-3de0-ae4f-1eea93588773. C explicitly chose ACCEPTED for
calculate_invoice(rows) with JSON-ready money output. Other overlap requests
received normal owner REVISED/REJECTED/CANCELLED judgments. The recorded audit
covered 13 owner-response obligations with zero non-owner violations.

C's repeated stale finish evidence was rejected and followed by fresh explicit
completion polls; publication eventually integrated calculation snapshot
snap_bb531b0f34fcf21cbff3632538402d62. C remained in read-only WAIT while the shared
group was active, with no fabricated review role. B's parser had four focused
tests passing before its integration work. D's discovery bridge found all five
fixture tests, but those tests could not yet pass without the application.

The ordinary dirty-workspace limitation remains: following calculation
integration, A and D received workspace_stale, and exact empty recovery returned
WORKSPACE_STALE_DIRTY. Both ended with their work preserved. B/C were still
confirmed live inside their original 25-minute deadlines at this checkpoint.
No new participants, manual responses or manual state repair were injected.
This is partial progress and a verified wait, not terminal acceptance.

Raw evidence, configuration, launch/effective metadata and observer summaries
are under `C:\t\FortniteGamingSeven-Acceptance-BindingFixed-20261001-120241`.
The wait audit's legacy reason filter does not count completed-lane WAIT with a
null reason; those normal projections are retained in the raw calls. Source HEAD
and 159 dirty paths remained unchanged, source .synesis absent, no commit or push.

## Binding-fixed run: final bounded failure audit

All four launches ended. A/D exited after fail-closed workspace recovery; B/C
reached their original 25-minute deadlines at 15:31:36 local time. No additional
participants or controller-directed responses were introduced. The final normal
packaged MCP observer still showed WorkGroup 3a543c59-8be6-3aad-b304-fbd001b6c467
ACTIVE. Only snapshot snap_bb531b0f34fcf21cbff3632538402d62 was integrated,
calculation commit 2a3ae84. CLI, documentation and acceptance/discovery work
remained unfinished or unintegrated, preserved in the original worker contexts.

Final product-mediated validation: compileall of the available calculation
package passed (exit 0). Standard unittest discovery found zero tests and returned
exit 5. Explicit acceptance_check invocation failed to import the unintegrated
fixture (exit 1, one loader error), not five executed acceptance failures. The
additional count check confirmed DISCOVERED_TEST_COUNT 0. Read-only Git history
showed the managed baseline plus the calculation snapshot only. This run is
FAIL / PARTIAL; the final autonomous acceptance verdict remains BLOCKED.

The repeated binding bug and fresh-completion retry have source/public-MCP
verification. Real packaged completion retry also allowed C's calculation
publication. However ordinary SESSION_BOUND dirty work after a sibling control
advance still received workspace_stale followed by WORKSPACE_STALE_DIRTY.
Classification: remaining product workflow/recovery limitation; the existing
guard refused unsafe replacement correctly. Agent declaration and completion
choices contributed to the chronology and are preserved as evidence. No broader
dirty-workspace authority change is claimed designed or verified by this run.
Resolving this requires a supported recovery mechanism or a revised verified
product/configuration boundary; bypassing freshness to obtain PASS is forbidden.

Direct answers: no observed non-owner response obligation (13 exact-target
obligations audited); yes, the true owner explicitly ACCEPTED calculation request
57967bc0-bdfb-4eea-88b2-1ddb7eb55472; no, dependencies/application integration,
objective tests and terminal completion did not subsequently finish. No manual
Synesis state intervention occurred. The original integration-pump WAIT witness
remains separately identified evidence from the first package attempt.

Final observations and validations are in the binding-fixed evidence root's
`final-observer` directory. The final checkpoint also records all completion
records, the complete fresh manifest comparison and frozen archive hashes.
The same nonterminal dirty-recovery blocker persisted through the resumed source
repair, subsequent packaged run and this final audit. Successful narrow fixes do
not satisfy the overall acceptance objective.

## Supported-provider-path audit

The resumed audit checked current provider documentation, managed-launcher source
references, and the frozen package's public root/supervisor help. Provider-topic
help probes were rejected by the CLI parser, so they do not prove the absence of
an uninspected route. Ordinary Codex sessions are explicitly SESSION_BOUND.
Managed continuation requires an authenticated home/App Server/thread and a
server-issued attachment proof. No managed-agent launch action was exposed by
the inspected command registrations or the ten-tool MCP catalog. Managed Java
launcher classes exist; directly invoking them or constructing a proof would
violate the supported-interface acceptance boundary. Neither was attempted.
This is an observation of inspected entry points, not an exhaustive availability
claim. No source implementation, package, project state, commit or push changed.

The bounded-failure report is complete. The tracked objective permits that
outcome, while the original success bar requires full PASS. One clarification
was requested about the stopping condition: continue focused recovery work toward
PASS, or close the experiment as FAIL. That decision is pending; elapsed time
is not a user decision, and no product acceptance success is claimed.

## User-directed continuation and owned dirty-workspace refresh

The user explicitly selected continue toward PASS. The earlier pending-choice
note is historical; bounded failure evidence does not close this acceptance.
The real packaged run exposed a product gap: exact ordinary sessions with active,
disjoint claims cannot recover unfinished edits after a peer integrates.

The focused change extends ProviderSessionBindingService's existing worktree
refresh. Under the same project append lock used by claim transitions and
integration, it verifies the exact binding, active participant and announced
claims, the original registered Git identity/generation, and control ancestry.
Peer changes must be disjoint from all retained claims. Only claim-covered
working-tree changes are replayed into a newly allocated product worktree;
raw bytes, deletions and file attributes are retained. Unclaimed/protected paths,
symlinks or escaped real paths, staged index changes, private committed
generations, unavailable authority and bounded-output/content overflow fail
closed. Managed attachments retain their existing continuity path.

The original worktree is never changed or removed. Binding publication occurs
only after replay and reinspection. Before publication, a retry uses the original;
after publication, it uses the recorded replacement with the same session and
claims. A failed preparation may leave an unused registered worktree, which is
preserved rather than cleaned during acceptance. No new MCP tool or state model
was introduced. GitProcessRunner's existing bounded raw-byte method was exposed
to preserve NUL-delimited path inspection.

Focused RED evidence reproduced WORKSPACE_STALE_DIRTY before this change.
Evidence and before copies: C:\t\FortniteGamingSeven-DirtyRefreshRepair-20261001.
A new uniquely named package/provenance boundary is required for this genuine
product repair. All previous packaged artifacts remain frozen; a future PASS
would apply to the repaired package, not retroactively to the original archive.

Final focused verification: all 19 ProviderSessionBindingService checks PASS;
two public MCP recovery/command checks PASS; format, strict compiler and
architecture gates PASS. Broad suites were not rerun. Packaging initially failed
at argument parsing before executing packaging tasks because the PowerShell
property argument was unquoted; the bounded retry quotes that argument. This is
an invocation error, not product behavior or provenance regeneration of an old
artifact.

## Dirty-refresh repaired package and fresh autonomous run

Package: 0.1.0-syn082-dirty-refresh-20261001-180600.
Source HEAD remains 813b1f1c5c3572d895444850b1e22ad1f4cc114b, master.
ZIP SHA256: 65047FCA3C4AE834C35347C4846CFFBD614C66A0686AD18F181AF11EEF7C7BBB.
CLI JAR SHA256: 7E4829DCD113BE304D385694DDCBA745581E75B20BB78029C027D96430D66C07.
Manifest SHA256: 562DD4F9B7754949BB27BA4C1BF5B3F2E03BEF0B54FEB64A55AB91AD30FEE5A6.
All 809 inputs matched before/after packaging; 198 bundle/archive files and
relevant compiled classes/JARs matched. Provenance is in the dirty-refresh repair
evidence directory. The three existing configuration-cache warnings persisted.

Fresh project: C:\t\FGS5\FortniteGamingSeven.
ProjectId: 892d7a61-4bc1-48db-8e2a-c4790f3d50e1.
Evidence: C:\t\FortniteGamingSeven-Acceptance-DirtyRefresh-20261001-180600.
Packaged init and matching provider installation created fresh state, with no
prior coordination copied. Exactly ten tools were returned by the packaged
native MCP catalog. Ordinary SESSION_BOUND, explicit command/project/cwd/MCP
Java home, 45-second startup and 120-second tool timeouts remain recorded.
Provider install reports experimental/degraded native-hook trust, manual
ATTESTED and MCP health PASS. The shared prompt is unchanged, SHA256
9616f50876d5dfd6f78eab778f660034bff3fb31f0d6e1d001940e5bb18e240e.
Four ordinary independent Codex CLI sessions A-D launched together at 18:10 local,
with gpt-6-luna/high effective model metadata verified immediately after launch.
All received the same customer brief and discover their responsibilities through
public product state. The controller has assigned no roles/files, answered no
requests and repaired no coordination state. Each session has a 25-minute deadline.

The initial dirty-refresh launch failed prerequisites: provider installation
rewrote the isolated Codex configuration to the older AppData installation.
The controller's declared frozen-package configuration therefore differed from
the actual A-D launch argv. All four sessions exited before any claim was
admitted, with MANUAL_MODIFIED_OR_OUTDATED. This is setup/configuration drift;
it is not evidence of behavior of the new artifact. The rewritten configuration
and actual launch records are preserved. No manual state repair occurred.

The bounded configuration retry uses another freshly initialized project,
C:\t\FGS6\FortniteGamingSeven, ProjectId
3a6b9577-f784-40ea-8ac5-4b1653cc76dd. Evidence root is the preceding dirty-refresh
acceptance directory plus -Retry. After matching provider installation, an
explicit launch configuration selects the frozen bundle. The launch helper now
checks that exact path before executing any provider. The original/shared prompt
bytes remain unchanged. These are new sessions in a new project, not continuation
of the closed failed-prerequisite participants. Exactly ten tools were observed
again from the frozen package. Previous project state and packages remain intact.

## Dirty-refresh retry failure and stale integration projection

In FGS6, all four exact sessions admitted disjoint responsibilities after lawful
scope negotiation: A calculation.py plus tests/test_calculation.py; B README.md;
C acceptance_check.py plus test_acceptance.py; D __main__.py plus tests/test_cli.py.
WorkGroup: c3db8f8d-9a2b-3648-af86-0036fbb47e72.
A: agt_61e591d6-bef5-36d5-b293-b513f607fc4f.
B: agt_3816e948-65e8-3b8e-b1c5-de5e6e0d7035.
C: agt_324d02fe-2fe3-34bf-ac36-f9e3c993d016.
D: agt_150da854-406a-394a-a2bb-d17b6516aa2c.

Request bc1db992-942a-47a7-9e9e-9bb69a787ebc was produced lawfully by A to the
CLI owner D, intent 579db159-8be0-35b6-a608-c7a7bed871e4, proposing the actual
calculate_invoice(rows)/InvoiceInputError calculation-to-CLI contract. D received
REVIEW_CONTRACT at 15:20:21.931Z and chose ACCEPTED through coordination_response
at 15:20:33.753Z. C's independent fixture work retained IMPLEMENT at 15:20:20.069Z;
no request response obligation was projected to C. Eleven owner-directed response
projections were audited, with zero target/current-participant mismatches. These
include scope negotiation as well as the concrete interface request; target
matching alone is not proof of capability ownership, which is separately shown
by D's exact CLI claims for the interface request.

A's four calculation tests and compileall passed, and snapshot
snap_e9dbe64713cd7db13b51f00df070311e reached INTEGRATED. B's README snapshot
snap_936a32c2fe9e4d72ab2a6166f1473f82 was INTEGRATION_BLOCKED. A stayed in WAIT
at its completed-lane continuation. At 15:21:52.490Z D's normal poll reported
INTEGRATION_TERMINALIZATION_FAILED / REVIEW_ACCEPTANCE_REQUIRED. Subsequent
polls from all sessions reported SESSION_TERMINAL_STATE_UNAVAILABLE, no permitted
operations and retrySafe false. All sessions exited fail closed within the bound.
No manual repair, human-directed coordination or provider replacement occurred.
Final integrated validation and terminal completion were not reached; this run
is FAIL. Its current durable state is not lawfully reverified after the read failure.
The frozen target is preserved rather than repaired.

Source inspection found that integration took eligible snapshots from the
caller's pre-lock store. Acquiring the append lock did not refresh that state,
although append chose the current durable hash-chain head. A concurrent peer
could therefore leave the caller validating/appending against stale eligibility.
A narrow RED test reproduced a stale caller treating an already-integrated
snapshot as eligible and appending an integration block. The product correction
reloads the store under the existing project lock, and completion reads a fresh
post-integration projection. Review acceptance, immutable snapshot provenance,
claims and lock boundaries remain enforced; no replay guard was loosened.
Evidence: C:\t\FortniteGamingSeven-IntegrationRefreshRepair-20261001.

Stale-integration repair verification: seven orchestration/queue checks PASS,
including stale retry of two genuinely integrated snapshots with no new events,
dependency wake/order and invalid-candidate isolation. Two public MCP checks PASS:
reviewer-owned artifact publication/integration/group closure, and wrong-participant,
stale-epoch, snapshot/result/replay validation fences. Format/compiler/architecture
checks PASS. Queue assertions now explicitly reload durable state rather than
relying on mutation of an obsolete in-memory store. No broad rerun occurred.

## Integration-refresh repaired package and new autonomous run

Package: 0.1.0-syn082-integration-refresh-20261001-183500.
ZIP SHA256: 07C9B2EA158DCF6196F106172FD79F0C440B89ABA7BA1F75A13ABDCEA2B6ABF2.
CLI JAR SHA256: 4A06907D35B5A7367D7D649ABB7A3F5D065736F52B078E10F2EB7E9960D6E1C2.
Manifest SHA256: E5A82F63687AAB8258E481C08766BA0FC84C1B6CE2E8CE45D081555FCEF5AE64.
Provenance: C:\t\FortniteGamingSeven-IntegrationRefreshRepair-20261001\provenance.json.
All 809 source inputs matched before/after build; 198 files matched the ZIP;
affected class/JAR payloads matched. HEAD/master unchanged, 160 dirty paths,
source .synesis absent. Previous archives remain frozen. Packaging PASS with
three existing configuration-cache warnings.

Fresh external project: C:\t\FGS7\FortniteGamingSeven.
ProjectId: 051907fa-53a5-43ab-8df1-93d5a5ce76ff.
Evidence: C:\t\FortniteGamingSeven-Acceptance-IntegrationRefresh-20261001-183500.
Matching packaged provider install followed by explicit verified frozen-package
MCP configuration; old AppData selection is recorded before replacing the
isolated launch configuration. Global user configuration remains ignored and
unmodified. Catalog: exactly ten tools. Ordinary SESSION_BOUND sessions A-D,
all gpt-6-luna/high effective metadata verified immediately. Same prompt bytes,
no roles/files assigned by the controller, bounded 25-minute sessions. No
coordination state copied or repaired.

## Integration-refresh run: bounded partial outcome

FGS7 WorkGroup c43e3ec5-3276-3136-bc0c-feb53e75d8cd remains ACTIVE. C integrated
calculator.py, D integrated __main__.py, and A integrated README plus the immutable
fixture/discovery wrapper. Ordinary ensure_session refreshes preserved active
work through disjoint peer advances; no prior dirty-recovery or replay-corruption
failure recurred. B's test_calculator.py intent remains ANNOUNCED without a
published artifact. All four sessions reached the declared 25-minute deadline
and their exact launched process trees were terminated by the deadline helper.
This was the declared bound, not manual lifecycle repair or a replacement.

Independent final packaged MCP validation passed compileall, all five discovery
acceptance tests, and the five explicit fixture tests; discovery count was five.
The resulting code therefore passes the supplied public behavior fixture, but
B's work and terminal closure are absent. This is partial evidence, not PASS.
The expanded observer also issued discovery for a non-existent tests directory;
Python resolved an installed third-party tests package and ran unrelated tests
(two import errors). That is an observer/environment error, not a project or
Synesis regression. Future optional discovery is restricted to paths actually
present in the product's tracked-file projection. Raw evidence is retained.

The stalled test participant repeatedly polled its owner inbox while awaiting an
outgoing request's decision. Both request d2f3e684-ec6f-4b8d-b5eb-80a1ba0c0909
(CLI-to-calculator) and 08c81d26-fd09-4c6a-be0d-039ce1b1f469
(tests-to-calculator) were accepted by the actual calculator owner C. The
supported public collaboration_status projection includes resolved requests;
there is no basis to claim that the capability is unavailable. The initial task
brief also allowed agents to mistake the final shared suite for a prerequisite
to publishing their independently ready artifacts. These are task/agent-workflow
issues to address through a clearer initial brief in a new project, not live
coaching, a planner, or another product patch. The frozen package is unchanged.

## Same frozen package, clarified initial task phases

The next run uses the identical integration-refresh archive/payload; no source
change or rebuild occurred. New project C:\t\FGS8\FortniteGamingSeven,
ProjectId a3f60f29-3481-49df-87fe-a77e3b42009c. Evidence:
C:\t\FortniteGamingSeven-Acceptance-PhasesClarified-20261001-190500.

Initial shared prompt SHA256:
6E584DB5378F04FEA2A63035EA9C38EF40E0DAFEE82C676BC4A63DCEBB6881B0.
The customer behavior and immutable fixture are unchanged. Two general
clarifications distinguish owned local checks from final integrated acceptance,
and direct every agent to discover outgoing request status through the existing
public collaboration_status projection. No roles, files, participant IDs,
request IDs, response choices or exact sequence are assigned by the controller.
No live session was coached. Prior sessions/projects remain preserved and are
not treated as continuations. Four new ordinary gpt-6-luna/high sessions were
launched, effective metadata verified immediately, with the same 25-minute bound.
Effective package/project/cwd/MCP Java home are recorded and checked; ten tools
were observed. This changes task setup, not coordination state or product code.

## Clarified-phase run: original coding group complete, extra verification active

FGS8 coding WorkGroup d3a1b89c-346d-3da1-bbaf-93738c4fe00f reached COMPLETED,
version 2, with all four original participants COMPLETED and all four snapshots
integrated. A README; C immutable fixture/discovery; D invoice_tool implementation
and four local tests; B test_invoice_calculations.py with four independent tests.
Independent final packaged validation: compileall PASS, all 13 discovery tests
PASS, five explicit fixture tests PASS, discovered count 13. Repository output
contains each participant's artifacts and only product-created integration commits.

Critical pending request 11a042d7-a1b9-4a80-9003-20b5a2aea064 existed from
16:11:17.719Z to the owner decision at 16:12:28.758Z. True owner D,
agt_42bb4a7c-6cf7-34a1-b16b-5e242c86a47e, intent
00cafac8-9867-3ff6-abfa-e5e3208c9919, owned invoice_tool via PATH_SUBTREE.
D received REVIEW_CONTRACT and chose ACCEPTED with the calculate_invoice(rows)
and InvoiceError interface. Non-owner A, agt_42100940-9927-3c7a-9905-bca78bc3b057,
returned WAIT five times during that same interval. C finish_lane at
16:12:27.223Z returned integration_pending / WAIT for its published immutable
fixture snapshot. A's 16:12:27.630Z WAIT projection showed that queued snapshot
INTEGRATION_PENDING, before the owner decision. This directly exercises pending
integration without a fabricated non-owner response obligation. Structured
chronology: critical-owner-nonowner-witness.json and timestamped-product-calls.jsonl.

After completing its coding lane, B recovered for further command execution and
announced a separate verification goal, creating WorkGroup
0907ea51-f777-41c0-aa74-d83cf5817025. Tests passed but this read-only intent's
completion remained IMPLEMENT. Source inspection confirms the completed inbox
already returns COMPLETED before workspace readiness; no patch to that authority
guard is warranted. Public no-change eligibility requires an explicit supported
no-mutation declaration; this verification's wording did not meet that predicate.
No guard was relaxed, no dummy change made, and no group was manually cancelled
or completed. The active extra group prevents an unqualified project-terminal PASS.

The next initial brief explicitly ends the coding goal at shared coding-group
completion and assigns final independent acceptance observation to the controller,
as permitted by the acceptance rules. It gives no roles/files/IDs/decisions and
no live agent is coached. The same frozen package is retained without rebuilding.

## Terminal-scope initial brief, same frozen artifact

Fresh project C:\t\FGS9\FortniteGamingSeven, ProjectId
8765aa30-8dcd-40b4-827e-b1a32d816ec9. Evidence:
C:\t\FortniteGamingSeven-Acceptance-TerminalScope-20261001-193500.
Shared prompt SHA256:
7095E610B5298A2AF2A0EEF7A2CD22BAC60BA365140EC111E88C6006B58679C4.
The initial brief explicitly ends the coding goal at its shared WorkGroup's
terminal completion; the controller independently performs final integrated
checks through the packaged product. It does not assign responsibilities,
claims, request IDs, decisions or an action sequence. Old active verification
state was preserved without manual cancellation or repair.

Source master/HEAD unchanged, 160 dirty paths, no source .synesis. Frozen archive
hash rechecked unchanged before setup. Exactly ten packaged tools observed.
Four new ordinary Codex sessions A-D launched at 22:13 local, each explicitly
pinned and effectively verified gpt-6-luna/high. One persistent MCP connection per
ordinary session, SESSION_BOUND, exact frozen binary/project/cwd/Java-home
configuration and 25-minute bound. No source change or packaging occurred.

## Final acceptance result — PASS on the repaired frozen package

Final project: C:\t\FGS9\FortniteGamingSeven (created/used).
ProjectId: 8765aa30-8dcd-40b4-827e-b1a32d816ec9.
WorkGroup: ebdd4819-b607-386a-9bb2-3028edaedd75, COMPLETED version 2.
Fresh independent product observations show one group, zero active intents,
zero pending requests, all four participants COMPLETED, and all four immutable
snapshots INTEGRATED. Every ordinary session exited 0 within its 25-minute bound;
no session timed out, was replaced, or required human coordination in this run.

| Session | Product participant | Self-selected responsibility |
| --- | --- | --- |
| A | agt_749c06dc-5d96-3a4f-a316-0b01a43925ed | USAGE.md |
| B | agt_f8d6adc5-76af-3509-ab86-64ced8abf1b7 | acceptance_check.py and test_acceptance.py |
| C | agt_4112710c-5976-37cb-a806-dfba768d1082 | README.md |
| D | agt_3c38cd90-d955-3112-a4c0-407f06ee07d0 | invoice_tool/ application implementation |

All sessions used ordinary Codex SESSION_BOUND integration, gpt-6-luna/high
with effective metadata verified. Their launch argv, provider thread metadata,
public participant projections, and exact product-returned session worktree paths
are preserved in acceptance-checkpoint.json. Thread IDs and process IDs are
observation metadata; actions used each persistent MCP connection's exact product
binding. No latest-session, PID, provider-thread or manual-participant fallback
was used. No managed continuity was constructed or claimed.

The coding goal was a real standard-library Python decimal CSV/JSON invoice
application with cent rounding, coupons, totals, errors, tests and documentation.
The application and acceptance harness were independently owned implementation
areas, with documentation owned separately. The immutable fixture consumes the
peer-owned CLI. A also requested confirmation of the CLI behavior contract from
its actual implementation owner before publishing its dependent usage guide.
No controller assigned roles or files; all claims came from ordinary task
announcements and lawful scope negotiation.

Request 29caf02a-3b08-4731-8775-7353565632a8 was created by A at
19:14:51.087Z, targeting D's intent 96b17963-02d5-30e0-acc8-c8c8b83bda4e.
It requested the implemented CLI/CSV/JSON/error behavior owned by D's invoice_tool
claim. D's get_next_action projected REVIEW_CONTRACT/respond_coordination; D
retained the judgment and explicitly chose ACCEPTED at 19:15:21.878Z through
coordination_response. A discovered the result through public collaboration_status
and continued its own usage artifact. Tests subsequently consumed D's integrated
implementation and passed. Snapshot propagation, binding refresh, automatic
resumption, integration and final group closure occurred through normal product
interfaces with no manual Synesis-state intervention.

### Non-owner evidence scope

Four projected response obligations in the final run were audited; every target
matched the current authenticated participant, with zero unresolved identities
and zero non-owner obligations. While the above request was pending, B's normal
projection remained IMPLEMENT for its independent acceptance-artifact responsibility
(e.g. line 35 at 19:15:06.525Z), with no response obligation for D's request.
A and C also retained their own lawful projected work. This is the explicit
allowed independent-work alternative in the user's acceptance criteria. Twenty
WAIT projections were recorded after publication while peers continued.
No simultaneous pending-request WAIT is claimed for FGS9.

The exact pending-WAIT regression witness is separately preserved from FGS8 on
this identical frozen package: owner D received REVIEW_CONTRACT for request
11a042d7-a1b9-4a80-9003-20b5a2aea064 and chose ACCEPTED; non-owner A returned
WAIT five times while it remained pending. At 16:12:27.223Z C's published snapshot
was held in integration_pending/WAIT, and A's 16:12:27.630Z WAIT showed that same
snapshot INTEGRATION_PENDING before the owner decision at 16:12:28.758Z.
That coding group subsequently completed with all 13 tests passing; its extra
post-terminal verification group is preserved as a separate incomplete outcome.
These chronologies are not merged or relabeled as a single run. The final FGS9
run independently reaches full project-terminal completion under the permitted
independent-work variant.

### Final independent validation and output audit

- Packaged compileall: PASS, exit 0.
- Default unittest discovery: five tests PASS, exit 0.
- Explicit immutable acceptance fixture: five tests PASS, exit 0.
- Missing input and output-to-directory file-error checks: PASS (code 2,
  nonempty diagnostics, no successful output for missing input).
- Provided fixture content unchanged; product contentHash matches SHA256
  b648c38541d5f47f085c9c401c74f6ec4c9460e8b484d6b619eae381ace9b99b.
- All seven integrated files read through the packaged product; whole project
  diff is seven files, 293 insertions. Each of four snapshot changes is entirely
  inside its owner's exact admitted claims; participant/lane/epoch provenance
  matches fresh product projections. No protected peer work was overwritten.
- All 176 recorded project tool calls were Synesis MCP calls. Native project
  command executions: zero.
- Final task/group and code checks are PASS. No arbitrary timeout extension,
  injected response, state editing, force completion, or manual wake occurred.

The initial checkpoint collector incorrectly retained an older INTEGRATING
projection from label-ordered historical logs. Its incomplete checkpoint is
preserved. The corrected audit uses the fresh independent product inbox, which
shows all four exact snapshots INTEGRATED; no product state was changed to obtain
that result.

### Artifact, configuration and repository closeout

Acceptance applies to 0.1.0-syn082-integration-refresh-20261001-183500, whose
ZIP SHA256 is 07C9B2EA158DCF6196F106172FD79F0C440B89ABA7BA1F75A13ABDCEA2B6ABF2.
Provenance SHA256: 60C5BB4424394BAF7CAC21604C970C596C04AD51FFEC832F81D775D72A2F8375.
Manifest SHA256: E5A82F63687AAB8258E481C08766BA0FC84C1B6CE2E8CE45D081555FCEF5AE64.
Final integrity recheck: all 809 source inputs match; all 198 bundle/archive
files match; all six frozen archives retain their recorded hashes. Earlier
packages are preserved and receive no retroactive acceptance claim. Genuine
product defects were repaired only after packaged evidence and focused RED/GREEN
verification; each repaired build has its own provenance boundary.

Effective final configuration: exact frozen bin/synesis-mcp.exe, provider codex,
explicit FGS9 --project and cwd, matching provider installation, isolated Synesis
Java user.home, process-local jdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe,
startup 45 seconds, tool 120 seconds, project checks 60 seconds, polling 10 seconds,
ordinary agent deadline 1500 seconds. Global user Codex configuration was ignored;
no provider credentials were copied. Exactly ten tools remained exposed. The
installer's older AppData binary selection was recorded and explicitly overridden
in the isolated launch configuration before work. Registry-full discovery and
experimental/degraded optional hook trust remain documented. Startup banner
metadata remains stale; payload hashes/provenance establish exercised identity.

Final source repository: master, HEAD
813b1f1c5c3572d895444850b1e22ad1f4cc114b, 160 dirty paths, source .synesis absent.
Unrelated work was preserved; no reset, clean, stash, normalization, source commit,
source push or remote mutation occurred. Disposable Git commits were created by
the product's normal baseline/snapshot/integration workflow, not by controller
commit commands. Known broad-suite denial-reason mismatch and two Javadoc warnings
were not rerun or treated as acceptance regressions. Prior failures, deadlines,
configuration retries, observer errors and task-scope clarifications remain above;
no failed or partial run is silently promoted to PASS.

Durable final checkpoint:
C:\t\FortniteGamingSeven-Acceptance-TerminalScope-20261001-193500\acceptance-checkpoint.json.
Associated evidence includes frozen-integrity-check.json,
native-and-frozen-archive-audit.json, critical-owner-nonowner-witness.json,
product-calls.jsonl, timestamped-product-calls.jsonl, launch/effective metadata,
final-observer and output-inspection. The old frozen Stage4 target was not used
or mutated and remains unverified.

Direct answers: no non-owner response obligation was observed; the true owner
retained and exercised CONTRACT judgment; the final autonomous workflow finished
correctly, with terminal product state and passing objective tests, without manual
Synesis-state intervention.
