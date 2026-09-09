# SYN-049 fresh unattended two-worker acceptance — runs #45–#50

Date: 2026-09-06
Classification: **PARTIAL**
Repository source checkout: `0901417067ca11f9a8c2f5b922bca6ba16051031` before the final evidence
checkpoint
Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`

## Scope and boundaries

These were fresh disposable task-tracker fixtures. No historical Synesis
coordination state was reused, no old lane was repaired, no substitute Worker
B or A2 was created, and no provider credentials or raw managed proof were
read, copied, or recorded. The validation JVM used only the process-local
JDK25 property:

`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`

The installed-layout native MCP executable was used:
`cli/build/install/synesis/bin/synesis-mcp.exe`. This corrected the run-44
harness packaging error, where the raw native launcher lacked its adjacent
runtime layout.

## Run #45 — production projection failure

Run #45 was fresh and reached lawful generation-1 managed startup for both
workers, real provider turns, A's exact claimed implementation, capability
publication, and the existing SYN-040 provider-native wake on the original
Thread B. The owner-side `get_next_action` projection returned a
`respond_coordination` `implementation_validation` action without the strict
required `result` field. The real MCP boundary correctly rejected it with
`COORDINATION_RESPONSE_FIELD_REQUIRED:result`.

Read-only source tracing identified the mismatch: `AgentWorkflowReducer`
manufactured the response payload from handle/revision metadata, while
`McpProtocolHandler.normalizeStrictResponse` requires the provider to choose
`accepted` or `revision_required`. The projection was corrected to expose an
explicit decision contract and exact `inboxItemId`/handle/revision metadata,
without guessing acceptance. A focused reducer regression passed.

## Run #47 — harness continuation gate

Run #47 was fresh and proved the corrected projection through the real
provider path. It reached:

- generation-1 same-process `prepareFirst → START` for A and B;
- original Thread B provider-native wake;
- capability `AWAITING_OWNER → ACCEPTED → IMPLEMENTATION_AVAILABLE → VALIDATED`;
- A and B lifecycle completion on their original provider threads;
- exact A/B participants remaining `ACTIVE` and intents `ANNOUNCED`.

The run did not reach finalization because the disposable harness required a
continuation grant for A even though the WorkGroup had no grant and both
participants were still active. The harness was stopped normally and the
provider was uninstalled through the supported flow.

## Run #48 — bounded provider continuation stall

Run #48 used a fresh target:
`C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-48`

Project: `beb5dc32-d890-4bbd-a746-84494bbe8d82`
WorkGroup: `9c015232-a400-39ca-a754-ed706624ee11`
Participant A: `agt_a6331d8a-6ed9-3dfd-a741-72495b155a45`
Intent A: `407458cf-f6ff-3224-bbd4-4675f86f89a4`
Binding A: `session-18852f25-47a6-4a0e-93a7-8443fca5325d`
Thread A: `01a0752e-067a-7080-a934-c2e5cec4ba7a`
Participant B: `agt_23264f1d-5ff1-3c24-9ea5-db74a091aca2`
Intent B: `da04b7f2-c58c-3323-acfe-92bd56e49161`
Binding B: `session-ccd4e47d-a7e8-4584-ba08-552e60658db7`
Thread B: `01a0752c-b55d-7082-bc6a-2a98d474103d`
Dependency: `tasktracker.domain.persistence`

Both bindings were prepared and started in one live validation JVM. Both
attachments were generation 1 and `PENDING_ACTIVATION` before START, then
reached ACTIVE through the production launcher. The provider roots and MCP
children were contained by the production Job path. B's initial turn reached
`NEEDS_CAPABILITY`; A implemented and published the exact capability. B then
woke through SYN-040 on its original Thread B, submitted the strict validation
decision, reached `VALIDATED`, implemented its assigned application/API
claims, and completed its dependent provider turn on the same Thread B.

The harness then submitted A's ordinary same-thread post-dependency
continuation. No response arrived before the bounded stop; A's durable
lifecycle record remained at its prior completed turn. No final review,
integration, completion request, lane finish, or WorkGroup terminalization was
claimed. The provider was uninstalled successfully afterward, with unrelated
configuration preserved.

## Runs #49–#50 — completion-boundary diagnosis

Run #49 was a fresh target and reached the exact A finish projection. A
published and integrated immutable snapshot `snap_df1ca7adead8f5e1ff8ad7585acd2aca`
and received a server-issued single-use review grant. The harness had no
lawful branch for a completed-but-live A to consume that grant, so the run
stopped with A `COMPLETED`, B `ACTIVE`, B intent `ANNOUNCED`, and the WorkGroup
`ACTIVE`. No state was repaired and no replacement was invoked.

Run #50 was a fresh target with the corrected harness and post-fix installed
distribution. It proved, in one live JVM, both generation-1 `prepareFirst →
START` paths, exact original provider threads, original-Thread-B
provider-native wake, capability admission through `VALIDATED`, real A and B
claimed work, and same-thread A/B continuations. Its final bounded state was
B participant `ACTIVE`, B intent `ANNOUNCED`, and WorkGroup `ACTIVE`; final B
review/finish, lane terminalization, and WorkGroup terminalization remain
unproven. Run #50 was stopped normally and provider installation was removed
through the supported flow. No A2, replacement, substitute B, manual
state/worktree copy, cherry-pick, credential access, or push occurred.

Exact run #50 identities: project
`9a5b3f1b-2b9f-4914-9179-3caa4d256196`, WorkGroup
`ec9e5b6b-3cc9-3d2c-b23f-a61050c807de`, A binding
`session-e6053b3d-5cb8-4218-9620-7cdc382ae1f2`, A participant
`agt_b3e3a515-bda6-35af-9ec1-4c3817e42650`, A intent
`74c0e2d7-60dc-3fa8-8011-b61c19c3195e`, Thread A
`01a07559-e432-7fe0-95c6-4c89949c43b2`, B binding
`session-7d65c4ea-cca8-4269-8139-c8db1849ec46`, B participant
`agt_f313c651-c532-3ef3-91cc-789a8bd0fddd`, B intent
`2d5583df-6e2d-331e-919f-2e0acad5767b`, and Thread B
`01a07558-e9b8-7691-b8e4-2a39ca3c32ed`. The dependency was
`tasktracker.domain.persistence`; A owned domain/persistence source and test
subtrees, and B owned application/API source and test subtrees.

## Source change and verification

The narrow production correction is in
`workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java`
and
`workspace/src/main/java/org/synesis/workspace/application/agent/AgentWorkflowReducer.java`.
The regression is in
`workspace/src/test/java/org/synesis/workspace/agent/AgentWorkflowReducerTest.java`.

Passing focused verification:

` :workspace:test --tests org.synesis.workspace.agent.AgentWorkflowReducerTest `

The broader `AgentNextActionServiceTest` and `McpSyn039SliceTest` invocation
compiled but stalled during test execution and is recorded as incomplete, not
passing acceptance evidence. A clean JDK25 build of the install distribution
and platform bundle passed with the same process-local property. The fresh
post-build hashes were recomputed before run #48; the installed-layout native
MCP was used by hash, not by the raw build-output path.

## Result

The corrected capability-validation projection, provider-native wake path, and
same-thread dependent work are proven in fresh real runs. SYN-049 remains
**ACTIVE / PARTIAL** because the final B-side review/finish/terminalization
boundary was not reached in run #50. SYN-051 remains **COMPLETE / PASS-A**.
No push was performed.
