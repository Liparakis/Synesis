# Next Session

## SYN-041 final real Codex closure acceptance — 2026-08-29

SYN-041 is DONE / ACCEPTED, RESULT A. The one real Codex lifecycle sealed the
session at fence sequence 7; the original runtime was dead before the sole
rejected probe, which returned `SESSION_TERMINAL` and left the final raw lease
at `TERMINAL_DISCONNECTED` after clean probe EOF.

Evidence: `docs/evidence/syn041-final-real-codex-closure-2026-08-29.md`.

Exact next action: preserve the accepted closure and do not create SYN-042 or
broaden terminal-session semantics without a separately activated task.

## SYN-041 terminal-disconnect trigger implementation — 2026-08-29

RESULT C was fixed in the existing MCP/lease seams. Java-local transport
failures now invoke PID-gated abnormal finalization; a later foreign clean
close verifies the persisted runtime and records `TERMINAL_DISCONNECTED` when
that runtime is gone. Fresh packaged acceptance passed terminal seal, exact
Java termination, `SESSION_TERMINAL` rejection, probe clean EOF, and the final
raw lease state.

Evidence: `docs/evidence/syn041-terminal-disconnect-trigger-cp0567-2026-08-29.md`.

Exact next action: review CP-0567 and await separately authorized final
provider confirmation; do not run Codex, close SYN-041, or broaden scope.

## SYN-041 CP-0563 terminal transport-history preservation — 2026-08-29

The CP-0562 defect is resolved: a rejected same-session close can no longer
rewrite a durably abnormal terminal lease as `CLOSED_CLEANLY`. Clean and
abnormal finalizers use the existing append lock and durable-state fencing;
abnormal terminalization is terminal-authority- and PID-gated, idempotent,
and metadata-preserving.

Focused source checks and fresh official-bundle/provider-independent acceptance
passed. Broader process-heavy MCP tests timed out without assertion output and
remain incomplete. No real Codex provider experiment was run, and SYN-041 is
still ACTIVE.

Evidence: `docs/evidence/syn041-terminal-transport-history-fix-2026-08-29.md`.

Exact next action: run `powershell -ExecutionPolicy Bypass -File
scripts/agent-resume.ps1` and inspect CP-0563; await separate authorization
before any final real-provider acceptance or task closure.

## SYN-041 final real Codex terminal-seal acceptance — 2026-08-28

The one actual real Codex lifecycle completed the lawful no-change path and
committed `PROVIDER_SESSION_TERMINALIZED` sequence 7 before Java/MCP exit 1;
Codex exited 0. The exact-session rebind probe returned `SESSION_TERMINAL`,
but the probe's clean close rewrote the lease to `CLOSED_CLEANLY`. This is
RESULT C: terminal transport history is not preserved across a rejected
same-connection close. The durable event remains present and no authority was
reactivated.

Evidence: `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.

Exact next action: inspect `SessionLeaseService.markClosedCleanly`, add a
focused regression test for terminal-state preservation, and run only the
narrow serialized lease/MCP tests; do not run another provider experiment,
modify Doctor, or close SYN-041.

## SYN-041 terminal-session seal implementation boundary — 2026-08-28

The first bounded implementation slice is complete. `finish_lane` remains
backward compatible and lane-only unless `terminalSession=true` is explicitly
provided. A server-validated exact-session event fence blocks rebind,
heartbeat, wake/next-action, review, continuation, coordination, and grant
authority after sealing. Clean EOF remains `CLOSED_CLEANLY`; abnormal loss
before a seal retains stale/recovery behavior; abnormal loss after a seal is
`TERMINAL_DISCONNECTED` history.

Evidence: `docs/evidence/syn041-terminal-session-seal-2026-08-28.md`.

- Exact next code action: run `git status --short` and inspect the SYN-041
  implementation/evidence diff before any future commit. Do not commit, push,
  tag, release, or run another real provider experiment.

## SYN-041 terminal-disconnect design boundary — 2026-08-28

The design-only semantics investigation is complete. No current state is an
irreversible provider-session authority seal: lane/group completion and empty
claims/intents do not fence review, wake, recovery, commands, later work, or
same-connection rebinding. RESULT C is primary. Any future implementation
must use explicit exact-session terminal intent plus an atomic authority proof,
keep clean EOF as `CLOSED_CLEANLY`, and retain abnormal transport history
separately.

Evidence: `docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.

Exact next action: stop; do not run another provider experiment or modify
production, lease, Doctor, migration, identity, or SYN-039 behavior.

## SYN-041 causal-analysis boundary — 2026-08-28

The read-only source/control analysis is complete: clean EOF is Java/MCP 0
with `CLOSED_CLEANLY`; partial EOF is Java/MCP 1 with
`MCP_PARTIAL_FRAME_EOF` and `ACTIVE`; closed stdout is Java/MCP 0. The native
launcher directly propagates Java’s nonzero exit. Evidence:
`docs/evidence/syn041-exit-code-causal-analysis-2026-08-28.md`.

Exact next action: no further provider run; preserve RESULT B with secondary
RESULT D and leave product, lease, Doctor, migration, identity, and SYN-039
behavior unchanged.

## SYN-041 final boundary — 2026-08-28

The authorized final handle-based measurement is complete and classified
RESULT C: official MCP and packaged Java each exited 1 before Codex exited 0.
The valid direct topology, provider completion, retained-handle records, and
fresh read-only Doctor result are recorded in
`docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.

Exact next action: no further SYN-041 provider run; preserve this RESULT C
evidence and leave leases, Doctor, production code, migrations, generalized
identity, and SYN-039 unchanged.

## SYN-041 measurement-design boundary — 2026-08-28

The design-only slice is complete; do not run another equivalent provider
experiment in this task. If a separately authorized continuation occurs, use
an external disposable observer that captures Codex stdout/stderr to regular
files and retains native query/synchronize handles for the direct MCP and
Java children. Do not proxy stdio, inject, terminate, mutate leases, or alter
the direct Codex -> official MCP -> Java topology.

Evidence: `docs/evidence/syn041-native-observability-design-2026-08-28.md`.
SYN-041 remains ACTIVE / RESULT E. Exact blocker: Need native,
non-interposing MCP/Java exit-code and transport-lifetime telemetry
sufficient to classify the child termination observed ~24.5 seconds before
Codex exit.

## SYN-041 real Codex clean-exit provider lifecycle validation

SYN-041 is the explicitly requested measurement task; SYN-039 and SYN-040
remain closed.

- Exact next action: preserve the final RESULT E native-topology measurement
  and do not infer MCP clean closure from process disappearance.
- Preserve all product semantics and dirty files. Do not use an empty isolated
  `CODEX_HOME`, execute provider migration, or implement a lease fix.
- No push, tag, release, cleanup, or architecture change.

The native direct-topology run captured Codex -> official MCP -> Java parentage
and child termination timing, but its polling harness lost the Codex transcript
and native EOF/child exit evidence. SYN-041 remains ACTIVE; do not run another
equivalent probe or alter lifecycle semantics from this result.

Evidence:
`docs/evidence/syn041-real-codex-native-teardown-2026-08-28.md`.

## SYN-040 post-SYN-039 provider-session and Doctor hygiene

SYN-040 was the explicitly requested follow-on lifecycle/infrastructure task
and is now DONE / VERIFIED.
SYN-039 remains `DONE / ACCEPTED` at CP-0547 and must not be reopened.

- Exact next action: preserve the completed causal classification and promote
  a future task explicitly before implementation; no SYN-040 product change is
  justified.
- Preserve the five existing dirty lifecycle files unless direct causality is
  proven. Do not add generalized identity architecture, a launcher, daemon,
  orchestrator, relay, or manual cleanup dependency.
- No new milestone was created automatically; SYN-040 was explicitly
  promoted for the user's separate request. No push, tag, or release.

Evidence:
`docs/evidence/syn040-post-syn039-doctor-hygiene-2026-08-28.md`.

## CP-0547 SYN-039 closure — final canonical acceptance

SYN-039 is `DONE / ACCEPTED` as of 2026-08-28. The final canonical evidence
proves reviewer-first order-independent admission, disjoint exact claims,
immutable S1 rejection, same-lineage epoch/version advancement, fresh G2,
accepted-only S2 integration, explicit reviewer no-change completion, and zero
residual active collaboration state.

Evidence:
`docs/evidence/syn039-final-canonical-acceptance-closure-2026-08-28.md`.

Doctor remains `DEGRADED` with six non-fatal stale-lease,
command namespace/retention, and provider migration warnings. No SYN-039
collaboration state remained active; no repair is part of this closure.

- Exact next action: select and explicitly promote the next authorized task
  before beginning implementation. Do not reopen SYN-039 for Doctor warnings,
  create a new SYN milestone for them, or change `SYN-014E`.

## CP-0545 exact-projection diagnostic boundary

Evidence:
`docs/evidence/syn039-diagnostic-cp0548-exact-rule-2026-08-25.md`.

The exact-rule diagnostic reached WorkGroup
`7dad9076-f0be-3117-9667-b5260ce1ca1e`, exact REVIEW request
`ff8c05b8-cbe6-41a0-8d1b-d9867723a87e`, owner acceptance, and grant
`4fe63ef0-418d-3abc-a442-c768a3b73f6a` consumption. The incomplete grant
payload was rejected fail-closed and the exact retry succeeded. The
implementation participant then ended at `WAIT -> get_next_action({})` before
snapshot publication; the reviewer had no snapshot to validate. No unchanged
projected action failed.

- Exact next action: preserve this as provider/agent engagement evidence and
  collect new provider-session termination evidence before changing lifecycle
  semantics. Do not push or create SYN-040.

## CP-0544 ordinary review-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0547-ordinary-review-continuation-2026-08-25.md`.

The fresh ordinary run reached one shared WorkGroup
`31941d9a-11dd-3b49-98ab-86042f5b6faa`, exact REVIEW request
`e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae`, owner acceptance, and grant
`bd3d274c-e4e6-3c63-930d-8ba19b783c5d` consumption. The first incomplete grant
payload was rejected fail-closed and the exact retry succeeded. Both provider
sessions then ended at valid `WAIT -> get_next_action({})` continuations before
snapshot publication. No unchanged projected action failed.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary visible coding prompts. Do not relay,
  manually transition, modify wait/review semantics, push, or create SYN-040.

## CP-0543 ordinary grant-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0544-ordinary-grant-continuation-2026-08-25.md`.

The fresh ordinary run reached reciprocal REVIEW, grant
`964ea299-c1fb-3298-a61b-d448522fb33d` consumption, snapshot
`snap_0bd17b0b5256e6a3cc6a5a9c79487085`, structured ACCEPT, and integration
commit `872b689`. A malformed grant retry was rejected fail-closed and then
the exact retry succeeded. The reciprocal grant remained pending after valid
wait polling ended. No unchanged projection failed.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary visible coding prompts. Do not relay,
  manually transition, modify wait/review semantics, push, or create SYN-040.

## CP-0542 ordinary reciprocal-review boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0543-ordinary-reciprocal-review-2026-08-25.md`.

The fresh ordinary run reached one shared WorkGroup, reciprocal REVIEW
requests, grant `f2e6b918-06f3-317c-9c3a-15b3b6cdc723` consumption, snapshot
`snap_a787bc5da5e0e7ca279b6f199625e1ed`, structured ACCEPT, and integration
commit `50651bd`. The implementation lane then executed its owner response but
both sessions ended at valid wait continuations before grant
`939017b6-16be-377e-9ff2-915dc002ffc6` consumption and implementation
publication. No unchanged projected action failed.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary visible coding prompts. Do not relay,
  manually transition, modify wait/review semantics, push, or create SYN-040.

## CP-0541 ordinary continuation diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0541-ordinary-continuation-2026-08-25.md`.

The fresh ordinary acceptance reached one shared WorkGroup, exact REVIEW
admission, owner acceptance, grant `d7d42eeb-45fb-35c1-9386-f9bfd435176d`
consumption, immutable snapshot `snap_c46638443d433f95564066fc20dce6e7`,
structured ACCEPT, and integration commit `69059ad`. The reciprocal grant
`976388e2-d7f2-373e-83a1-9f36df6045ca` remained pending after Agent A's
provider session ended at repeated valid `WAIT -> get_next_action({})` owner
polling. No unchanged projected action failed and no production defect is
proven.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary visible coding prompts. Do not relay,
  manually transition, modify wait/review semantics, push, or create SYN-040.

## CP-0540 bounded exact-action diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0540-review-contract-diagnostic-2026-08-25.md`.

The fresh exact-action diagnostic used two independent ready/isolated sessions
and one shared WorkGroup. Exact REVIEW admission, owner acceptance, grant
consumption, immutable test-snapshot publication, structured ACCEPT, and
integration succeeded. The review-decision projection was an explicit
ACCEPT/REJECT choice with exact grant/snapshot/intent/epoch context; the
reviewer executed the existing strict response successfully.

The run stopped with the implementation lane repeatedly receiving the exact
`WAIT -> get_next_action({})` continuation while the reciprocal grant remained
pending. WorkGroup `5608d46f-ba9a-3d88-8d8c-ceac20a4f8db` is ACTIVE. No
unchanged projected action failed and no production change is justified.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary visible coding prompts. Do not push or
  create SYN-040.

## CP-0539 ordinary acceptance compliance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0539-ordinary-compliance-2026-08-25.md`.

The latest fresh ordinary run used the current bundled ten-tool MCP and two
ready/isolated Codex sessions. WorkGroup
`53906f49-5d99-3726-ac2d-b155af973a7e` reached accepted reciprocal REVIEW
requests, grant consumption, snapshot
`snap_686a822915f6f230c059ddb5040fab32`, immutable review, structured ACCEPT,
and integration. The completed-lane pending-request projection fix is
verified. The remaining boundary was agent compliance: one omitted a
projected `targetParticipant` and was rejected fail-closed, then corrected;
later an agent stopped instead of following a valid `WAIT -> get_next_action`
continuation. No unchanged projection failed and no state lacked a usable
action.

- Exact next action: run another fresh ordinary unattended two-agent Todo
  acceptance with only complementary coding prompts. Do not relay, manually
  transition, push, or create SYN-040. Keep the known Git stall, bootstrap
  migration failures, and Doctor warnings separate.

## CP-0536 review projection fix and post-fix acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0536-review-gate-2026-08-25.md`.

The stale-review fallback projection defect is fixed and regression-covered.
After a validated REVIEW grant, its target intent is no longer re-emitted as
`REVIEW_ADMISSION_REQUIRED`. The fresh post-fix diagnostic reached shared
REVIEW, exact grant consumption, immutable snapshot publication, and
structured ACCEPT, then stopped because the provider did not remain engaged
for the reciprocal grant. No unchanged projected action failed.

- Exact next action: run one fresh ordinary unattended two-agent Todo
  acceptance with only complementary coding prompts against the rebuilt
  bundled MCP. Do not relay messages or trigger lifecycle transitions. Keep
  the known Doctor, Git subprocess, and bootstrap migration issues separate.

## CP-0535 continuation diagnostic and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-continuation-2026-08-25.md`.

The exact-action diagnostic completed WorkGroup
`fd42d9b3-5333-3a72-8cf0-20603ddda286` through both REVIEW grants, immutable
snapshots, structured ACCEPT, integration, and `COMPLETED` state. Every
concrete projection, including continuations returned by `finish_lane`, was
executed with unchanged arguments.

The second, ordinary acceptance reached WorkGroup
`d1815a35-a4d5-3f9c-aa89-9531ea5652f9`, integrated Agent A's snapshot, and
recorded structured ACCEPT. Agent A then ignored the exact
`request_coordination(work_group_join)` continuation returned by
`finish_lane`; no projected action failed and closure was not reached. This
remains provider/session compliance evidence, not a production defect.

- Exact next action: preserve the evidence and checkpoint locally. Do not
  change lifecycle code, push, or create SYN-040. Only investigate a new
  implementation slice if an unchanged executable continuation fails or a
  required state has no usable projection after all prior projections are
  obeyed.

## CP-0534 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-ordinary-2026-08-25.md`.

The fresh ordinary run used the current bundled MCP, two distinct
ready/isolated Codex sessions, one shared WorkGroup, and disjoint epoch-1
claims. It reached REVIEW admission, owner acceptance, grant consumption,
snapshot publication, integration, and structured ACCEPT. Agent A's
`finish_lane` result projected the exact reciprocal REVIEW admission for B's
intent, but A ended its provider turn before executing that continuation. B
then corrected one invalid review identity and repeatedly received bare
`IMPLEMENT` with no executable lifecycle action. The WorkGroup remains
`ACTIVE`; no terminal state was reached.

- Exact next action: run one bounded diagnostic with the rule “execute every
  exact projected continuation returned by a mutating tool before ending the
  turn,” in addition to the existing `get_next_action` rule. Do not change
  production lifecycle code from this run alone; keep the Git stall, bootstrap
  migration failures, and Doctor warnings separate.

## CP-0533 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0533-ordinary-2026-08-25.md`.

The fresh ordinary run used current bundled MCP, two distinct ready/isolated
Codex sessions, one shared WorkGroup, and disjoint epoch-1 claims. Agent B
executed the exact projected `request_coordination(work_group_join)` and
created pending REVIEW request `574c290b-36bd-417e-9286-dce2d9a57cc6`.
Agent A had already ended its ordinary coding turn before the request was
created, so the owner acceptance was never projected or executed. No grant,
snapshot, validation, integration, or closure state exists. This is provider
turn/session engagement evidence; no production defect was proven.

- Exact next action: run one bounded continuity diagnostic that keeps the same
  owner session alive across a delayed peer REVIEW request, with no relay,
  manual transition, retry machinery, or orchestration. Verify the owner
  receives and executes the exact projected acceptance action. Do not modify
  production code unless an unchanged projection fails or a required state has
  no usable projection.

## CP-0532 exact diagnostic closure and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0532-exact-diagnostic-and-ordinary-2026-08-25.md`.

The exact-projection diagnostic closed WorkGroup
`35931e39-9eb1-3693-b03e-b89fc7088b72` after exact admission, grants, both
immutable snapshots, structured validation, integration, and control pytest
`5 passed`. The ordinary follow-up reached WorkGroup
`4646b6ba-66bc-3760-8fda-fc04b9db1b66`, but invalid agent-selected review
arguments were rejected fail-closed and the provider sessions ended before
reciprocal grant consumption and implementation publication; control pytest was
`1 failed, 4 passed`. No production defect was proven.

- Exact next code action: run one fresh ordinary unattended two-agent Todo
  acceptance with only complementary coding prompts and the current bundled
  MCP. Do not relay, repair, trigger lifecycle transitions, or add production
  behavior for wrong agent arguments or provider-turn termination. Preserve the
  first unchanged projected-action failure or missing usable projection.

## CP-0531 reciprocal-review gating fix and exact-projection diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0531-exact-rule-diagnostic-2026-08-25.md`.

The reciprocal-review gating defect is fixed and verified. An active lane with
no claim-covered changes now remains ordinary `IMPLEMENT` instead of being
fenced by a pending peer grant; once publishable changes exist, publication
still requires exact grant/epoch/participant authorization.

The CP-0539 exact-projection diagnostic used current bundled MCP and two
independent ready/isolated GPT-5.6 Luna sessions. It reached one shared
WorkGroup, exact REVIEW admission, grant consumption, immutable snapshot
inspection, structured ACCEPT, and integration. The sessions ended with a
reciprocal request/grant unresolved; no unchanged projected action failed.
The conditional second ordinary run was not started.

- Exact next code action: run one fresh ordinary unattended two-agent Todo
  acceptance with only the complementary coding prompts and current bundled
  MCP. Do not relay, repair, trigger transitions, or coach lifecycle steps.

## CP-0530 pytest-artifact recovery fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0530-bytecode-recovery-2026-08-25.md`.

The production recovery defect is fixed and verified. A normal pytest run's
`__pycache__/` files no longer prevent an exact projected
`ensure_session({})` from preserving the session and reallocating a stale
clean worker. Real untracked user content still blocks recovery.

The post-fix diagnostic used current bundled MCP and two independent
ready/isolated GPT-5.6 Luna sessions. It reached one shared WorkGroup,
REVIEW admission, grant consumption, snapshot publication, structured ACCEPT,
integration, and 4/4 tests. A then sent a malformed reciprocal REVIEW
`intentId`, which Synesis rejected with `UUID string too large`; after a valid
retry, the provider turn ended while the reciprocal continuation remained
pending. No unchanged projected action failed, and no ordinary second run was
started because the bounded diagnostic did not complete.

- Exact next action: run one fresh bounded exact-projection two-agent Todo
  diagnostic with only the current complementary prompts plus the exact
  projection rule. Capture every projection/action pair. Do not relay, repair,
  or modify production code for agent-selected arguments or turn termination.
  A new production slice requires an unchanged projected action to fail or a
  necessary state to have no usable projected action.

## CP-0529 continuity probe and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0529-continuity-and-ordinary-2026-08-25.md`.

The continuity probe preserved both non-ephemeral sessions and executed the
projected `finish_lane`, publishing
`snap_3e21542358dd37d57cb6963d6f128557`. Reviewer recovery then failed closed
on ordinary pytest-generated `__pycache__` files under the existing dirty
worktree rule. The fresh ordinary run reached shared WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`, integrated
`snap_d678f31fc5591c897c7a648c41d4322d`, and recorded ACCEPT, but B ignored
the exact projected reciprocal `request_coordination` action and its turn
ended. No unchanged projected action failed; no production change is
justified.

- Exact next action: run one fresh ordinary unattended two-agent Todo
  acceptance with only complementary coding prompts and the current bundled
  MCP. Do not provide lifecycle coaching, relay messages, trigger transitions,
  or repair state manually. Keep the Git stall, bootstrap migration failures,
  and Doctor warnings separate. Do not push or create SYN-040.

## CP-0528 bounded diagnostic and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-and-ordinary-2026-08-25.md`.

The bounded diagnostic closed WorkGroup
`f0c02558-ab10-3bf6-b369-1d21011ffe64` after exact REVIEW admission, both
grants, snapshots, structured ACCEPT, integration, control pytest 4/4, and
terminal completion. The ordinary run reached WorkGroup
`b319999f-7060-360b-a26b-0a0891e23be1`, integrated the implementation
snapshot, and accepted it, but ended with reciprocal grant
`abacded1-ee9c-354f-9271-dabcd00bffa5` targeted at the ended implementer.
No unchanged projected action failed; WorkGroup remains ACTIVE.

- Exact next action: run one fresh supported non-ephemeral Codex
  provider-session continuity probe across this ordinary pending-grant
  `WAIT -> get_next_action({})` boundary, retaining both sessions and making
  no manual lifecycle transition. If the provider ends again, preserve the
  external agent/session classification and leave production unchanged. Do
  not push or create SYN-040.

## CP-0527 REVIEW replay fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0527-review-replay-fix-2026-08-25.md`.

Commit `81aa2f6` fixes the proven released-lane REVIEW replay defect. The
fresh post-fix diagnostic reached shared WorkGroup admission, exact owner
responses, grant consumption, immutable snapshot publication, integration,
and structured rejection. WorkGroup
`ff42da2a-719f-34cb-8851-de17edb9aba8` remained ACTIVE only because the Codex
provider turn ended before reciprocal grant
`6eb5cd9c-f949-3080-9bc1-5391a6db17cd` was consumed. No unchanged projected
action failed after the fix.

- Exact next action: run one fresh completely ordinary unattended two-agent
  Todo acceptance with only the real complementary coding prompts and the
  current bundled MCP. Do not provide lifecycle coaching, relay messages,
  trigger transitions, or repair state. Stop at the first unchanged
  projected-action failure or missing usable action. If it completes, inspect
  WorkGroup cleanup and Doctor. Do not push or create SYN-040.

## CP-0526 provider-session continuity boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-003-bounded-and-ordinary-2026-08-25.md`.

The bounded diagnostic completed WorkGroup
`18f226ad-d28b-3fd6-b8aa-3afb83429f4b`. The ordinary run reached WorkGroup
`0d6e6301-e6d1-3084-b0be-abbca3cdaa10`, integrated A's snapshot, and accepted
it from B, but A's ephemeral provider turn ended before the reciprocal grant
targeted at A was consumed. B remained in exact WAIT; no unchanged projected
action failed.

- Exact next action: run one supported non-ephemeral Codex provider-session
  continuity probe in a fresh disposable project, preserving both sessions
  across a durable `WAIT -> get_next_action({})` boundary. Do not manually
  invoke lifecycle actions, create a replacement intent, relay coordination,
  modify production code, push, or create SYN-040. If the provider ends again,
  classify the external session boundary and leave production unchanged.

## CP-0525 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-002-bounded-and-ordinary-2026-08-24.md`.

The bounded exact-action run completed the existing review/snapshot/
validation/integration lifecycle and closed WorkGroup
`52ceb172-4e63-332b-ac6a-a5d932acd03d`. The ordinary run used only the two
coding prompts, integrated both snapshots, and passed control pytest 3/3, but
left WorkGroup `5e0a82d7-635d-3e47-9e3e-5a4c37d83822` ACTIVE when the Codex
session stopped during a valid projected continuation. No unchanged projected
action failed.

- Exact next action: run one bounded provider-session continuation check from
  the preserved ordinary CP-0525 `WAIT` boundary, retaining the same
  participant/intent and executing only exact `get_next_action({})`. Do not
  create a new coding intent or relay state. If the provider ends again, keep
  production unchanged and classify the external session limitation; do not
  push or create SYN-040.

## CP-0523 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-third-ordinary-2026-08-24.md`.

The third ordinary acceptance remains partial at the agent/session boundary:
A ignored a durable `WAIT → get_next_action({})` projection and selected
unprojected reads/recovery, while B's exact snapshot publication succeeded.
No unchanged projected lifecycle action failed; WorkGroup
`e769b143-f9b0-337f-b06a-9eb1603c8cc9` remains ACTIVE.

- Exact next action: run a bounded provider-session continuity diagnostic at a
  fresh durable WAIT boundary without lifecycle coaching. Implement only an
  unchanged projected-action failure; otherwise preserve the external
  agent/session limitation. Do not push or create SYN-040.

## CP-0522 third ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-third-ordinary-2026-08-24.md`.

The third ordinary run used project `a7163c0d-1946-45d1-91e2-aa0efa82875d`
and WorkGroup `e769b143-f9b0-337f-b06a-9eb1603c8cc9`. A followed exact
admission and grant consumption, then ignored `WAIT → get_next_action({})`
and selected unprojected reads/recovery. B exact-published and integrated
`snap_012bbfe1bc5f22b8e69d51e9638b4c05`; A rejected it because the test-only
snapshot failed without A's unpublished implementation. WorkGroup remains
ACTIVE; no unchanged projected action failed.

- Exact next action: run focused SYN-039 tests, validators, Doctor, and
  `git diff --check`, then create CP-0523. Do not modify production code for
  this compliance boundary, push, or create SYN-040.

## CP-0522 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-valid-diagnostic-and-ordinary-2026-08-24.md`.

The bounded diagnostic completed WorkGroup
`eaa7631f-ce23-310f-b94c-d44db63b8eda` through reciprocal REVIEW, immutable
snapshots, structured ACCEPT, integration, and terminal completion. The
ordinary run reached WorkGroup `0f999cd8-e9b2-38cc-a382-ab6722b76139`,
integrated A's snapshot, and received B's ACCEPT, but A's Codex turn ended
after a repeated concrete `request_coordination` projection; B correctly
remained in exact WAIT. No unchanged projected action failed.

- Exact next action: run one third fresh ordinary unattended two-agent Todo
  acceptance with the same two actual complementary coding prompts and no
  lifecycle coaching. Capture every projection and following action. If the
  same turn-ending boundary repeats, classify it as the external
  agent/session blocker and do not change production code.
- Run `scripts/agent-resume.ps1` first. Do not relay messages, trigger
  transitions, push, or create SYN-040.

## CP-0521 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-invalid-seed-continuation-2026-08-24.md`.

The continuation diagnostic used an invalid seed: `todo.py` already
implemented `TodoList.complete`. A correctly made no edit and remained in
`IMPLEMENT`; B waited at `SNAPSHOT_PENDING`, later passed 4/4, and no snapshot
or closure occurred.

- Exact next action: create a fresh bounded diagnostic with a genuinely missing
  no-op `TodoList.complete` implementation, then retain completed sessions only
  for already projected REVIEW actions. If it closes, run ordinary acceptance
  with only the actual coding prompts.
- Do not change production code, push, or create SYN-040 for this fixture
  issue.

## CP-0520 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-ordinary-completed-lane-2026-08-24.md`.

The fresh ordinary run reached one shared WorkGroup, exact REVIEW admission,
snapshot publication/integration, and B's structured ACCEPT. The corrected
harness did not create a new intent after A's lane completed, but A ended
before polling its reciprocal REVIEW grant. B correctly remained in exact
`WAIT` with `get_next_action({})`; WorkGroup
`5c1609bd-f88d-36e5-845b-0f07677e9ffe` is still `ACTIVE`.

- Exact next action: run one bounded no-code continuation diagnostic in a fresh
  project; retain the existing completed participant only to execute an
  already projected REVIEW action, without announcing a new intent or relaying
  coordination. If it closes cleanly, rerun ordinary acceptance.
- No unchanged projected action failed, so do not modify production lifecycle
  behavior, push, or create SYN-040 for this evidence.

## CP-0519 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-command-scope-recovery-2026-08-24.md`.

The narrow MCP command-scope recovery fix is present and verified. The exact
post-fix diagnostic closed WorkGroup
`89fea014-9f5b-326b-8521-5d2218cc55fc`; the required ordinary acceptance
reached integration and review but retained an extra active continuation lane
in WorkGroup `dfc93a1a-de2e-3db4-859e-c0eb7d60eaab`.

- Exact next action: run one fresh ordinary unattended Todo acceptance while
  retaining both sessions but suppressing continuation of an already
  completed coding lane as a new intent.
- Preserve no-relay/no-manual-transition behavior. Do not modify production
  lifecycle code unless a clean run executes an unchanged projection that
  fails or Synesis exposes no usable action for a valid active lane.
- Run `scripts/agent-resume.ps1` first. Do not push or create SYN-040.

## CP-0518 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0536-bounded-and-ordinary-2026-08-24.md`.

Commit `c18073d` records the terminal-WorkGroup guard, deterministic
coordination/workspace regressions, the corrected MCP continuation fixture, and
the CP-0536 acceptance/state evidence. CP-0518 is the clean local checkpoint.
The bounded diagnostic closed WorkGroup
`62241cb0-1e0d-3030-a945-e7f2dc5c37fb`; the ordinary run remains agent
compliance evidence with WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a` ACTIVE and grant
`4ba34d35-976a-3d55-bc40-0d7c9656f46b` unresolved.

- Exact next action: run one fresh ordinary unattended Todo acceptance with the
  harness retaining both independent Codex sessions across durable WAIT and
  projected-action continuations.
- Preserve the no-relay/no-manual-transition rule. Do not change production
  code unless an unchanged projected action fails or Synesis projects no
  usable action for required progress. Do not push or create SYN-040.

## CP-0536 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0536-bounded-and-ordinary-2026-08-24.md`.

The terminal-WorkGroup guard and fresh-default-group regression are present
and focused tests pass. The bounded exact-action run closed WorkGroup
`62241cb0-1e0d-3030-a945-e7f2dc5c37fb` after both snapshot/validation paths;
the ordinary run stopped at Agent B's changed/ignored projected
`request_coordination` action with WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a` still ACTIVE and grant
`4ba34d35-976a-3d55-bc40-0d7c9656f46b` unresolved. No further lifecycle code
change is justified.

- Exact next documentation action: create the next checkpoint after the local
  commit; CP-0536 evidence already contains the final command results.
- Exact next code action: commit the already verified terminal-WorkGroup guard,
  its two deterministic regressions, and the necessary MCP fixture correction;
  do not alter production lifecycle behavior unless a new unchanged projected
  action fails.
- Keep the Git subprocess stall, bootstrap migration failures, and Doctor
  warnings separately classified. Do not relay, repair the disposable
  acceptance state, push, or create SYN-040.

## CP-0534 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-review-snapshot-access-2026-08-24.md`.

Commit `a03abe0` fixes authorized reviewer access to an immutable snapshot
after the control checkout advances. The reviewer can use the existing
`read_file` and `run_command` tools in a disposable read-only review workspace
without rebinding or discarding its own dirty lane. Wrong participant and
mismatched snapshot references remain fail-closed.

The fresh diagnostic reached shared WorkGroup convergence, exact REVIEW
admission, owner response, grant consumption, snapshot publication,
integration, and structured ACCEPT after actual snapshot inspection. Agent A
then ended before consuming the reciprocal REVIEW grant; B remained in exact
WAIT. WorkGroup `895e9681-8d66-37c0-b3b7-6eb88aa57838` is ACTIVE, so no clean
ordinary acceptance was run.

- Exact next action: run a fresh bounded diagnostic with both independent
  agents retained through reciprocal grant consumption, the second snapshot,
  validation, integration, cleanup, and terminal WorkGroup state.
- Exact code action: none unless an engaged participant executes an unchanged
  projected action that fails or a required state has no usable projection.
- Keep the Git subprocess stall, bootstrap migration failures, and Doctor
  warnings separately classified. Do not relay, repair state, push, or create
  SYN-040.

## CP-0533 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0533-engaged-diagnostic-2026-08-24.md`.

The engaged CP-0533 diagnostic reached both exact projected
`finish_lane` calls, immutable snapshots, integration, structured review
responses, and WorkGroup `cf3f65dd-c43b-3ad1-855b-0d72c68a419a` `COMPLETED`.
The control checkout is clean and pytest passes 4/4. The review decisions are
not trustworthy validation evidence: after control advanced, both reviewers'
snapshot reads returned `workspace_stale`; B's attempted
`ensure_session({"refresh":true})` returned `internal_failure`.

- Exact next documentation action: preserve the reviewer stale-read and
  recovery trace as the next SYN-039 blocker and keep the root format failure,
  Git stall, bootstrap migration failures, and Doctor warnings separate.
- Exact next code action: reproduce the reviewer snapshot-read and
  `ensure_session` recovery transition in a deterministic MCP fixture; make no
  production change until the binding/worktree/lease cause is identified.
- Do not relay, consume grants, repair state, push, or create SYN-040.

## CP-0532 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0531-ordinary-2026-08-24.md`.
Post-fix acceptance trace:
`docs/evidence/syn039-unattended-todo-cp0532-ordinary-2026-08-24.md`.

Commit `b249790` fixes snapshot staging so root and nested Python `__pycache__`
artifacts classified by the existing policy cannot enter immutable snapshots.
The deterministic two-lane integration regression, focused tests, Javadocs,
validators, Go vet, bundle rebuild, and diff check pass. Bootstrap migration
tests still have the three known failures; the MCP Git launch stall remains
separate.

The fresh CP-0532 ordinary run reached one shared WorkGroup, exact reciprocal
REVIEW requests, grant consumption, exact `finish_lane`, immutable snapshot
publication, and integration at `20ab964` without the prior conflict. Agent A
then ended after an unchanged reciprocal request projection; B remained in
exact WAIT polling for A's grant and did not publish its own test change.
WorkGroup `f81adf5d-1845-31b6-9eda-199dbcf8cb6f` is ACTIVE and Doctor is
DEGRADED with six warnings.

- Exact next documentation action: run a bounded diagnostic with both agents
  kept engaged through the reciprocal request and WAIT projections; capture
  whether the active reviewer later receives its own implementation/publication
  action.
- Exact next code action: none until an engaged participant executes an unchanged
  projection that fails or Synesis reaches a required state with no usable
  action.
- Do not relay, consume grants, repair state, push, or create SYN-040.

## CP-0530 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0530-exact-rule-diagnostic-2026-08-24.md`.

The fresh exact-action diagnostic reached one shared WorkGroup, exact REVIEW
admission, owner response, grant consumption, snapshot publication,
integration, and structured ACCEPT. Agent A then ended after an unchanged
reciprocal `request_coordination` projection; Agent B remained in exact
`WAIT -> get_next_action({})` polling for A's unresolved reciprocal grant.
The WorkGroup stayed ACTIVE. No unchanged projected Synesis action failed and
no production code changed. A separate ordinary precheck is recorded at
`docs/evidence/syn039-unattended-todo-cp0529-ordinary-precheck-2026-08-24.md`.

- Exact next action: classify the Codex turn-ending boundary and run one fresh
  ordinary acceptance only if confirmation is needed.
- Exact code action: none from CP-0530; preserve fail-closed lifecycle,
  workspace, ownership, epoch, and cleanup behavior.
- Keep Doctor warnings, the Git subprocess stall, bootstrap migration failures,
  and agent-compliance evidence separately classified. Do not push or create
  SYN-040.

## CP-0528 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-2026-08-24.md`.

The fresh exact-action diagnostic reached one shared WorkGroup, exact REVIEW
admission, owner acceptance, grant consumption, immutable snapshot publication,
and integration. B once omitted `targetParticipant` from a projection that
contained it; the exact retry succeeded. B then received the structured
`review_decision` payload for snapshot `snap_2d4def43740098712b51e82199d84153`
but chose an unprojected Git read, which returned `workspace_stale`; exact
recovery returned `internal_failure / request_human_help`. No unchanged
concrete projection failed and no validation or closure occurred.

- Exact next action: audit the existing provider/manual guidance for
  `review_decision`; if it is clear, run a fresh ordinary unattended
  acceptance; otherwise make only a minimal agent-facing clarification.
- Exact code action: none until that audit proves a contract defect. Preserve
  fail-closed review, workspace, ownership, and epoch checks.
- Keep Doctor warnings, the Git subprocess stall, bootstrap migration failures,
  and cleanup separately classified. Do not push or create SYN-040.

## CP-0527 continuation

## CP-0527 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0526-projection-diagnostic-2026-08-24.md`
and
`docs/evidence/syn039-unattended-todo-cp0527-projection-diagnostic-2026-08-24.md`.

CP-0526's claim-aware publication fix is present and focused tests pass. The
fresh CP-0527 diagnostic reached one WorkGroup, exact REVIEW admission
projection, and then the unchanged projected request returned
`policy_denied / INTENT_NOT_FOUND`. No grant, snapshot, validation,
integration, or closure state exists.

- Exact next action: reproduce the projection-to-admission transition with
  durable per-call timing and participant/intent state, distinguishing a
  stale projection from wrong admission-state resolution.
- Exact code action: add or change production behavior only after that trace
  proves the narrow root cause; preserve fail-closed participant, intent,
  epoch, ownership, and WorkGroup checks.
- Keep Doctor warnings, the Git subprocess stall, bootstrap migration failures,
  cleanup, detached-agent retention, and orchestration separately classified.
  Do not push, relay, broaden SYN-039, or create SYN-040.

## CP-0524 continuation

## CP-0524 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0524-recovery-fix-diagnostic-2026-08-24.md`.

Commit `dd9f0eb` preserves the exact provider session identity when a clean
worker already contains the advanced control HEAD; the deterministic
`ProviderSessionBindingServiceTest` regression and focused verification pass.
The fresh exact-action run reached one WorkGroup, exact REVIEW admission,
owner acceptance, and grant issuance, then both agents stopped while polling;
no exact projected action failed and no snapshot or validation state exists.

- Exact next action: run a fresh ordinary unattended two-agent Todo acceptance
  with no lifecycle-conformance prompt beyond the repository contract.
- Exact code action: none unless that run proves an unchanged projected action
  failure or a required state with no usable projection.
- Keep the Git stall, bootstrap migration failures, Doctor warnings, and
  agent-engagement stop separately classified. Do not relay, push, broaden
  SYN-039, or create SYN-040.

# Next Session

## CP-0522 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-ordinary-2026-08-24.md` and
`docs/evidence/syn039-unattended-todo-cp0522-exact-rule-diagnostic-2026-08-24.md`.

CP-0521 ordinary acceptance reached one shared WorkGroup, A's snapshot
`snap_e426d4bc75881c0ef58ad2a0d7bdad08`, integration, and B's structured
ACCEPT, then stopped because A ended before consuming the reciprocal grant
`d72045c7-3761-3d92-8a84-71b9ab1dfba5`. CP-0522 exact-action diagnostic
stopped earlier because B changed the projected intent ID and received the
fail-closed `INTENT_NOT_FOUND`; no exact projected action failed.

- Exact next action: run a fresh bounded exact-action two-agent diagnostic and
  preserve every projection beside the unchanged actual arguments until the
  first real protocol failure or missing usable action.
- Exact next code action: none; do not modify production code for CP-0521 or
  CP-0522 agent-compliance evidence.
- Do not relay IDs, manually transition lifecycle state, push, broaden
  SYN-039, or create SYN-040.

## CP-0520 continuation

## CP-0520 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-stale-projection-diagnostic-2026-08-24.md`.

The stale-dirty continuation projection is implemented and regression-covered.
Focused MCP/workspace tests, Javadocs, validators, bundle rebuild, Go vet, and
diff checks pass. A fresh exact-rule diagnostic reached one shared WorkGroup,
both REVIEW admissions and grants, both immutable snapshot integrations, and
one structured ACCEPT. It stopped before closure because A ended after exact
WAIT polling before observing B's second snapshot; malformed arguments were
agent-compliance evidence and later exact actions succeeded.

- Exact next action: run a fresh ordinary unattended two-agent Todo acceptance
  with no protocol-conformance instruction; preserve the first concrete
  projected-action failure or missing continuation.
- Exact next code action: run that fresh ordinary acceptance and preserve the
  first concrete projected-action failure or missing continuation; do not
  change production code for agent-compliance evidence.
- Do not relay IDs, manually accept requests, trigger transitions, push,
  broaden SYN-039, or create SYN-040.

# Next Session

## CP-0519 continuation

Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-exact-rule-diagnostic-2026-08-24.md`.

The fresh exact-rule run used project
`00eecdcd-865a-4071-8df4-afc810839519`, WorkGroup
`663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf`, request
`d001fa9b-efb3-431e-aca5-b0559513291e`, consumed grant
`fed1c3f6-f8e0-3d73-bce9-9fe9f03439cb`, snapshot
`snap_48423ea02f57776f0064595b971197ab`, and integrated control commit
`2563b0c`. A's implementation lane completed and integrated. B accepted the
snapshot but still owns dirty `test_todo.py` work. Its exact projected
`ensure_session({})` after `workspace_stale` failed with
`internal_failure / request_human_help`; the WorkGroup is ACTIVE and B's
reciprocal snapshot has not been published.

- Exact next code action: reproduce this post-ACCEPT dirty-lane state in a
  deterministic fixture, then implement the smallest existing-model
  continuation projection for publication or authorized review. Preserve all
  fail-closed checks and do not discard dirty work.
- Exact next verification action: focused MCP/workspace regression, bundle
  rebuild, validators, then the same exact diagnostic. Only after that passes
  should the ordinary unattended acceptance be rerun.
- Do not relay IDs, manually accept requests, trigger transitions, push, or
  create SYN-040.

## CP-0517 continuation

The stale-reviewer fix is implemented in
`workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java`
and
`workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java`,
with deterministic coverage in
`mcp/src/test/java/org/synesis/mcp/application/McpSyn039SliceTest.java`.
Focused tests, Javadocs, validators, bundle rebuild, and diff checks pass.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0517-dirty-review-fix-diagnostic-2026-08-24.md`.

The fresh exact-action CP-0517 diagnostic used project
`d670cb0d-c17d-4d33-9c3a-afe37d5bb138`, WorkGroup
`fde62e9a-5f84-370a-84db-36a21117d1f7`, and REVIEW request
`41f48a24-8ff1-4638-bcd2-ce25f90ce369`. B executed the exact projected
`request_coordination(work_group_join)`; A's previous ordinary `IMPLEMENT`
poll predated that request and its Codex turn ended. No grant, snapshot,
validation, integration, or closure was reached.

- Exact next code action: none; do not modify production code for this agent-
  engagement result.
- Exact next acceptance action: run a fresh ordinary two-agent Todo test with
  no lifecycle-conformance instruction, then preserve the first concrete
  projection/action or missing-action boundary.
- Do not relay IDs, manually accept requests, trigger lifecycle transitions,
  push, or create SYN-040.

## CP-0516 continuation

CP-0516 producer-first exact-action evidence reached WorkGroup
`f4bba8b9-1d6f-36ba-9285-5b63cbf702cd`, request
`5b80bc40-70f7-43d9-8c98-09f70a2ee669`, consumed grant
`9bab2075-4ef2-32a8-a46e-6da66cc7f27d`, exact producer `finish_lane`, snapshot
`snap_171a6f766e26454cf60e6cebc3106f63`, and integration. The first later
blocker is reviewer B's exact projected `ensure_session({})` recovery after
control advanced. B's dirty `test_todo.py` worktree was correctly protected
by `WORKSPACE_STALE_DIRTY`, but the response was
`internal_failure / request_human_help`; no review decision or closure was
reached. Evidence:
`docs/evidence/syn039-unattended-todo-cp0516-producer-first-diagnostic-2026-08-24.md`.

Exact next action: implement and test the smallest safe grant-authorized
reviewer continuation after sibling control integration. Preserve dirty work;
do not replace or reopen it unsafely. Keep the existing participant, claim,
epoch, grant, snapshot, and fail-closed checks. Then rebuild the bundle and
rerun the producer-first exact-action diagnostic. Do not relay, manually
transition, push, or create SYN-040.

## CP-0515 continuation

CP-0515 exact-action evidence reached WorkGroup
`ed155087-41fd-39e6-8380-d2c5663aae64`, exact REVIEW admission, owner
acceptance, and consumed grant
`d401e6d4-fc52-3055-9355-ef083aeb48ad`. Both agents executed observed
`WAIT -> get_next_action({})` projections, but the producer stopped before
polling after grant consumption and the reviewer stopped at
`SNAPSHOT_PENDING`. No snapshot, validation, integration, or closure was
reached; no exact projected call failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0515-exact-action-diagnostic-2026-08-24.md`.

Exact next action: run one fresh bounded exact-action diagnostic with the
implementation producer launched/established first and the complementary
reviewer second. Keep both agents unattended, execute concrete projections
unchanged, and preserve the first lifecycle failure or missing usable action.
Do not relay, manually transition, change lifecycle code, push, or create
SYN-040.

## CP-0514 continuation

The post-contract ordinary acceptance used the current bundled MCP and two
independent GPT-5.6 Luna agents. Both claim-bearing `ensure_session` calls
reached ready/isolated, one shared WorkGroup formed, REVIEW admission and owner
acceptance succeeded, and grant
`91988d4d-9f80-311d-860f-55d46a3a5eff` was consumed. Agent A stopped after
repeated exact `WAIT -> get_next_action({})` polls while the grant was still
pending; Agent B stopped after two exact `SNAPSHOT_PENDING -> WAIT` polls. No
snapshot, validation, integration, or closure was reached. B's omitted
`targetParticipant` was rejected fail-closed and corrected. Evidence:
`docs/evidence/syn039-unattended-todo-cp0514-ordinary-claims-contract-2026-08-24.md`.

Exact next action: create a fresh bounded diagnostic with the explicit rule
that every concrete `get_next_action` recommendation is executed unchanged and
that both agents remain engaged through every projected WAIT while the
WorkGroup is active. If an exact projected action fails, preserve it as the
next production blocker; if agents stop or omit arguments again, classify that
as compliance evidence. If it reaches end-to-end completion, run a second
ordinary unattended acceptance. Do not modify lifecycle code, push, or create
SYN-040.

## CP-0512 continuation

CP-0512 exact-action diagnostic reached two reciprocal REVIEW admissions,
grant consumption, both `finish_lane` snapshot publications/integrations,
and one structured ACCEPT. The WorkGroup remained ACTIVE because Agent A
stopped after repeated `WAIT -> get_next_action` projections before polling
again after B's second snapshot. CP-0511 ordinary acceptance separately
shows A using `get_next_action({integrationCheck:{...}})` after coding and
stopping before lifecycle progress. Evidence:
`docs/evidence/syn039-unattended-todo-cp0512-exact-action-diagnostic-2026-08-24.md`
and `docs/evidence/syn039-unattended-todo-cp0511-ordinary-2026-08-24.md`.

Exact next action: inspect the current `get_next_action` catalog schema,
generated `AGENTS.md`, and provider manual wording for the legal timing of
`integrationCheck` and the required continuation semantics for projected
`WAIT -> get_next_action`. If ambiguity is proven, make only that narrow
agent-facing clarification and deterministic contract coverage; otherwise
record CP-0511/CP-0512 as agent-compliance evidence and run the next fresh
ordinary acceptance. Do not modify lifecycle code, push, or create SYN-040.

## CP-0510 continuation

CP-0509 proved and fixed a projection defect where review validation was
misreported as executable `respond_coordination` without the required
reviewer-selected result. The rebuilt bundle now exposes
`nextAction=review_decision`, exact snapshot/grant/intent/epoch context,
explicit `accepted`/`rejected` choices, and no fabricated executable tool
arguments.

CP-0510 fresh diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0510-001`, one shared
WorkGroup `ba7d9344-fa33-3564-832d-b68222c93296`, and two independent
ready/isolated Luna agents. REVIEW admission, grant consumption, exact
`finish_lane`, snapshot publication, and integration passed. The first later
failure was Agent B changing the projected intent ID and receiving the
expected fail-closed `INTENT_NOT_FOUND`; no new production defect is proven.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0510-review-decision-postfix-2026-08-24.md`.

Exact next action: run a fresh completely ordinary unattended two-agent Todo
acceptance with only the complementary coding prompts and no lifecycle
coaching or manual intervention. Do not change production code for the
CP-0510 argument typo, push, or create SYN-040.

## CP-0508 continuation

The CP-0507 review-result projection fix is committed as `ca9a2f3`. The fresh
CP-0508 diagnostic used the rebuilt bundle, exactly ten MCP tools, two
independent GPT-5.6 Luna agents, one shared WorkGroup
`e0ef5af5-844c-3f77-b4ad-29767b4b13c3`, and disjoint epoch-1 claims.

Agent A published and integrated snapshot
`snap_806145a00668f970adaaf4af734a9d81`. Agent B consumed its REVIEW grant,
passed four tests, saw the corrected non-executable review decision contract,
and submitted `accepted`; Synesis returned `ACCEPTED`. B first omitted the
projected `targetParticipant` during grant consumption and received the
expected fail-closed error before correcting it. A later stopped before
polling to consume reciprocal grant
`f879b4ff-047c-3dc8-8b70-2568a5d4a4a3`, so the WorkGroup stayed ACTIVE and no
second snapshot or closure was reached. Evidence:
`docs/evidence/syn039-unattended-todo-cp0508-review-decision-postfix-2026-08-24.md`.

Exact next action: run a fresh bounded diagnostic with the same current
bundle and exact-projection rule, but preserve both agent sessions after
reciprocal REVIEW acceptance until the targeted grant is consumed and the
second lane reaches publication, validation, integration, and closure. If an
exact complete projected action fails, capture it as the next production
blocker. If an agent omits or ignores a projected action again, record agent
compliance evidence without changing production code. Do not push or create
SYN-040.

- Exact next code action: run the fresh bounded exact-projection diagnostic;
  do not change production code unless a complete projected action fails.

## CP-0507 continuation

The fresh CP-0507 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0507-001`, the rebuilt
current bundled MCP (`0.1.0-SNAPSHOT`, protocol `2025-06-18`, commit
`bc334ac`, ten tools), and two independent GPT-5.6 Luna sessions. Both
reached distinct `ready / isolated` sessions and one WorkGroup
`9b605c00-d45c-34e6-a9dd-f0ad4d31be3b` with disjoint claims.

The CP-0506 guard is verified: Agent A's exact projected `finish_lane`
published and integrated snapshot
`snap_760b1bf37251e2c2f64e92e73ece42a9`. The first later blocker is the
reviewer validation projection: it exposed literal `result: "accepted|rejected"`.
Agent B executed the exact projected `respond_coordination` arguments and
received `policy_denied` / `COORDINATION_RESPONSE_INVALID_RESULT`. The
WorkGroup remains ACTIVE; no validation decision or closure was reached.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0507-review-result-projection-2026-08-24.md`.

- Exact next code action: trace `reviewActions`, `AgentWorkflowReducer`, and
  the MCP response contract, then make the smallest projection fix that
  exposes valid structured ACCEPT/REJECT choices without choosing for the
  reviewer.
- Add deterministic coverage for valid ACCEPT, valid REJECT, invalid result,
  stale grant/snapshot/epoch, wrong participant, and replay behavior before
  rerunning the exact-projection acceptance.
- Do not push, create SYN-040, or broaden cleanup, ownership, Doctor, or
  orchestration. Keep the Git stall, bootstrap migration failures, and
  unrelated Doctor warnings separate unless proven causal.

## CP-0505 continuation

The fresh CP-0505 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0505-001`, the current
bundled MCP, and two independent GPT-5.6 Luna sessions. Both reached
ten-tool `ready / isolated` sessions and one WorkGroup
`35aa138a-a6bf-389a-a4b5-e7bbe66024ec` with disjoint claims at epoch 1.

Exact REVIEW admission, idempotent request replay, owner acceptance, and
single-use grant consumption all succeeded. Grant
`a92067d7-7d0f-365b-b514-7b3efb314428` was consumed exactly once. Both agents
then stopped after executing the exact `WAIT` → `get_next_action({})`
continuation; the producer did not poll again after grant consumption, so no
snapshot, validation, integration, or closure was reached. No exact projected
action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0505-exact-rule-diagnostic-2026-08-24.md`.

- Exact next action: run one fresh bounded diagnostic with both agents kept
  alive after grant consumption and after peer-side snapshot publication;
  capture the first later projection and immediately following action.
- If an exact projected tool fails, preserve its complete arguments and state
  as the next production blocker. If an agent stops while an exact
  `get_next_action` continuation remains, record agent-compliance evidence and
  do not change lifecycle code.
- Do not push or create SYN-040. Keep Doctor warnings, the Git subprocess
  stall, and bootstrap migration failures separate unless directly causal.

## CP-0503 continuation

The CP-0502 owner-side projection defect is fixed and covered. CP-0503 proves
the owner now remains active through grant consumption, executes the exact
`finish_lane` projection, and publishes/integrates snapshot
`snap_5733de0976ad177cc349e9fa2fbdebcb`. Evidence:
`docs/evidence/syn039-unattended-todo-cp0503-postfix-diagnostic-2026-08-24.md`.

The reviewer consumed grant
`c9cb80ae-679d-3290-902c-c55647723aae` and received exact
`SNAPSHOT_PENDING` → `WAIT` → `get_next_action` twice, but stopped before
polling after publication. No projected action failed; do not change
production code from this agent-compliance result.

- Exact next action: run one fresh bounded two-agent diagnostic with both
  agents kept alive after every WAIT and after peer-side publication, then
  capture the reviewer validation projection and the first later lifecycle
  blocker.
- If a concrete projected action fails, preserve its exact arguments and
  state as the next defect. If an agent stops again without such a failure,
  record compliance evidence and stop the slice.
- Do not push or create SYN-040. Keep Doctor warnings, the Git subprocess
  stall, and bootstrap migration failures separate unless directly causal.

Verification note: focused MCP/workspace tests, Javadocs, validators, `go vet`,
and `git diff --check` pass. Root `check` remains non-green because of the
pre-existing `:link:formatCheck` trailing-whitespace findings and the captured
Git subprocess stall in `ProviderApplicationServiceTest` /
`ProcessCommandRunner`; bootstrap `go test` retains three migration failures.

## CP-0501 continuation

The fresh diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0501-002`, the current
bundled MCP, and two independent GPT-5.6 Luna sessions. Both reached ten
tools, `ready / isolated`, and one WorkGroup
`1f8bc962-fbb5-376b-9f72-1e0b4135a495` with disjoint claims. Exact REVIEW
admission, owner acceptance, and grant consumption all succeeded.

The producer stopped after a normal `IMPLEMENT` response with no executable
action while grant `e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7` was still pending.
The reviewer later consumed it and received exact `WAIT` →
`get_next_action`, but the producer was no longer alive to receive the
post-consumption `finish_lane` projection. No projected action failed and no
production change is justified. Evidence:
`docs/evidence/syn039-unattended-todo-cp0501-producer-polling-diagnostic-2026-08-24.md`.

- Exact next action: run a fresh bounded two-agent diagnostic with both agents
  explicitly continuing after ordinary no-action/WAIT states and after every
  peer-side progress event, then capture the owner `finish_lane` projection
  after grant consumption. Do not manually trigger lifecycle actions or relay
  messages.
- If an exact projected action fails, preserve that as the next defect. If an
  agent stops again without a projected action failure, record compliance
  evidence and do not change production code.
- Do not push or create SYN-040. Keep the Git stall, bootstrap migration
  failures, and Doctor warnings separate unless directly causal.

## CP-0500 continuation

The fresh post-fix diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0500-002`, the
rebuilt current bundled MCP, and two independent GPT-5.6 Luna sessions. Both
agents exposed ten tools, reached `ready / isolated`, held disjoint claims,
and converged on WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`.

The repeated REVIEW admission projection was fixed narrowly: all replays
returned request `90ab5c3b-e663-4230-94df-5f0077015508`, with no duplicate
request or grant. The run reached exact acceptance, grant consumption,
`finish_lane`, snapshot `snap_6b8ee8837a67aca57c5c28baed57a8a2`, integration,
and structured ACCEPT. Evidence:
`docs/evidence/syn039-unattended-todo-cp0500-review-admission-idempotency-2026-08-24.md`.

Agent A then ignored two repeated concrete review-admission projections after
request `d9d89b66-c0bf-46ac-958f-926c411564e7` and stopped. B later accepted
the request and received grant `b1b5b243-b6a5-308d-af57-bce3d3fc63d4`, but A
was no longer polling to consume it. The WorkGroup remains ACTIVE with B's
active claim and no B snapshot. Treat this as agent-compliance evidence; do
not change lifecycle production code from it.

- Exact next code action: run a fresh bounded two-agent diagnostic with the
  same exact-projection rule and verify both agents continue polling after the
  idempotent REVIEW request until the second grant, B snapshot, validation,
  integration, and WorkGroup closure. If a concrete projection is ignored
  again, record compliance evidence and stop the slice.
- Do not broaden cleanup, ownership, Doctor, or orchestration. Do not push or
  create SYN-040.

## CP-0499 continuation

The fresh CP-0499 post-fix diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0499-003`, the current
bundled MCP, and two independent GPT-5.6 Luna sessions. Both agents used the
same project pin, exactly ten tools, and distinct `ready / isolated` sessions.
They converged on WorkGroup
`3621a4f6-6b2b-3379-9174-9cdcb45b8186` and executed exact projected REVIEW
admission, owner acceptance, grant consumption, producer snapshot
publication, integration, and structured ACCEPT validation. No exact
projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.

The first unresolved state is that Agent B's own active `test_todo.py` intent
remains live after ACCEPT, but its final `get_next_action` returns ordinary
`IMPLEMENT` with no executable lifecycle action. The WorkGroup remains
`ACTIVE`; B's test snapshot was never published and clean closure did not
occur. The same run also generated three requests and three single-use grants
for repeated identical REVIEW admission projections.

- Exact next code action: reproduce this post-ACCEPT active-reviewer no-action
  state, trace why the existing model does not project B's publication/finish
  or a terminal closure action, and cover the repeated-admission projection in
  the same focused regression. Do not modify cleanup, ownership, Doctor, or
  broad orchestration. Do not run the ordinary second acceptance until this
  bounded slice is understood. Do not push or create SYN-040.

## CP-0498 continuation

The fresh CP-0498 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0498-001`, the current
bundled MCP, and two independent GPT-5.6 Luna sessions. Both sessions reached
the same pinned project, ten tools, and ready/isolated bindings. WorkGroup
`1d24011b-99a6-37bd-b56b-ca09eab8edef` reached exact admission, grant
consumption, projected `finish_lane`, snapshot publication, integration,
reviewer recovery, and exact ACCEPT decisions. ACCEPT now reports the durable
group status (`ACTIVE`) correctly. Evidence:
`docs/evidence/syn039-unattended-todo-cp0498-completed-review-continuity-diagnostic-2026-08-24.md`.

The first remaining production blocker is that B's completed binding becomes a
terminal `COMPLETED` response while A's sibling implementation lane remains
active. B therefore cannot discover/request review admission for A, and A
cannot receive the review grant needed to publish its snapshot. The next code
slice is restricted to same-WorkGroup review-only continuity: project the
existing admission/grant/validation actions for a completed participant and
allow only the existing exact review authority checks; completed write
mutation must remain closed.

Run focused workspace/MCP tests, rebuild the bundled MCP, then run one fresh
bounded diagnostic and (only if it reaches clean closure) the ordinary
unattended acceptance. Do not push or create SYN-040.

## CP-0497 continuation

The fresh CP-0497 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0497-001`, the
repository-built current MCP, and two independent GPT-5.6 Luna sessions. Both
preflighted against the same project, ten-tool catalog, and distinct
ready/isolated bindings.

The run reached WorkGroup `7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, exact REVIEW
admission, owner acceptance, grant consumption, exact projected producer
`finish_lane`, immutable snapshot
`snap_3eb0df616deb0c00e78540f63877b1c2`, integration, reviewer stale recovery,
and two exact projected ACCEPT decisions. No exact projected action failed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0497-review-continuity-diagnostic-2026-08-24.md`.

The first concrete blocker is a truthful-status defect: ACCEPT returns
`workGroupStatus=COMPLETED` even though final durable status is `ACTIVE` with
Agent A's separate active intent and two duplicate REVIEW grants. The run did
not qualify for the second ordinary acceptance. The next exact code action is:
add a deterministic `ReviewValidationService` regression for ACCEPT with live
intents/grants, return the durable WorkGroup status instead of unconditional
`COMPLETED`, run focused coordination/MCP tests, then rerun a fresh bounded
single-producer/reviewer diagnostic. Do not broaden cleanup or ownership, push,
or create SYN-040.

## CP-0494 continuation

The fresh CP-0494 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0494-001`, external
harness `harness-cp0494-001`, and the rebuilt current bundle. Both independent
GPT-5.6 Luna sessions used the same current MCP executable and project pin,
reached distinct `ready / isolated` sessions, and the direct bundle control
preflight reported protocol `2025-06-18`, server `0.1.0-SNAPSHOT`, and exactly
ten tools.

The run reached WorkGroup `471a4f65-5210-327f-ad5a-ba2897d022ab`, exact REVIEW
admission, accepted owner responses, grant consumption, and exact projected
producer publication. Agent A published snapshot
`snap_3e7c0ee281c5190f43bcd2102a5853f7` and integrated the control checkout to
`45fc60a`. The CP-0493 review-projection defect is fixed: the executable
`review_validation` payload no longer contains rejected `workGroupId` or
`targetParticipant` fields. The CP-0490 Python `__pycache__` snapshot defect is
also fixed and covered.

Agent B then received the corrected exact projection:
`respond_coordination({kind: review_validation, grantId:
2d616273-a235-3cec-b2fd-054a855fb8c6, snapshotId:
snap_3e7c0ee281c5190f43bcd2102a5853f7, intentId:
8e631b01-115b-35c6-8e4a-d9dd0e8a27c1, claimEpoch: 1, result: accepted|rejected})`.
It selected unprojected `read_file("todo.py")` instead, which produced
`workspace_stale`; no validation decision or closure occurred. This is
agent-compliance evidence, not a new production defect. WorkGroup remained
ACTIVE; Doctor remained DEGRADED with six warnings. Evidence:
`docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.

- Exact next action: run another fresh bounded diagnostic and capture whether
  an ordinary reviewer executes the corrected projected validation action. If
  it executes and a later transition fails, implement only that proven narrow
  defect. If it ignores the projection again, preserve compliance evidence
  without modifying production lifecycle code.
- Do not run the second ordinary acceptance until this bounded diagnostic
  completes. Do not push or create SYN-040.

## CP-0489 continuation

The fresh CP-0489 diagnostic used project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0489-001`, an
external harness, and the current bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
`8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`). Both
independent Luna sessions reached `ready / isolated`, held disjoint exact
claims, and converged on WorkGroup
`2176bfbd-6199-303f-805c-a91c382b92ff`.

The run reached exact REVIEW admission, exact owner acceptance, grants
`215ba3af-5cf9-352a-ac5e-5685438a7d12` and
`d831734a-d597-3457-b817-ae5b3f7e6e70`, and exact consumption of the first
grant. The reviewer correctly waited for the absent producer snapshot. The
producer's last `get_next_action` was before grant consumption and ordinary
`IMPLEMENT` with no concrete action; it did not poll again afterward. No
projected producer action failed and no production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0489-role-order-diagnostic-2026-08-24.md`.

- Exact next action: launch a fresh bounded diagnostic with both agents
  required to return to `get_next_action` after a wait or peer-side state
  change, then capture producer `snapshot_publication_required` → exact
  `finish_lane` → reviewer validation without relay or manual transition.
- Do not modify production code unless an exact projected action fails. Do
  not run the ordinary unattended acceptance until this diagnostic reaches a
  terminal result. Do not push or create SYN-040.

## CP-0487 continuation

The CP-0487 role-order diagnostic used fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0487-001` and the
current bundled MCP. Both sessions reached distinct `ready / isolated`
bindings. Agent B established WorkGroup
`a273e5df-a157-3ec7-ae93-211828d0acc2` first, so B's test intent was the
producer and Agent A's implementation intent became the reviewer.

The exact path reached REVIEW admission, owner acceptance, grants
`5ba56aa7-3887-3ee1-8973-919669144888` and
`7907440e-cc5d-39a2-a4b6-b228290ff381`, and exact consumption of the first
grant. A correctly received `SNAPSHOT_PENDING` → `wait`. B's last
`get_next_action` was before grant consumption and ordinary `IMPLEMENT`; it
did not poll after the later reviewer action. No projected producer action
failed, and no grant, snapshot, validation, integration, or closure completed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0487-role-order-diagnostic-2026-08-24.md`.

- Exact next code action: run a fresh bounded diagnostic with the
  implementation agent launched first, then capture every projection/action
  pair through producer publication and reviewer validation without relaying
  or manually triggering transitions.
- Do not modify production code unless an exact projected action fails. Keep
  duplicate retry-safe requests/grants separately classified for later
  idempotency/cleanup review.
- Run the ordinary unattended acceptance only after the bounded diagnostic
  reaches its terminal result. Keep Git stalls, bootstrap migration failures,
  and Doctor warnings separate.
- Do not push or create SYN-040.

## CP-0486 continuation

The CP-0486 exact-rule diagnostic used fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0486-001`, an
external harness, and the current bundled MCP (SHA-256
`8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`). Both
independent GPT-5.6 Luna sessions reached distinct `ready / isolated`
bindings, held exact disjoint `todo.py` / `test_todo.py` claims, and converged
on WorkGroup `9527b8ec-0971-3f33-995c-ac0833d506c7`.

Agent A executed the exact projected `request_coordination(work_group_join)`
action and completed its visible `todo.py` implementation without calling
unprojected `finish_lane`. Agent B independently supplied an unprojected
`integrationCheck` while its isolated worktree correctly lacked A's
unintegrated implementation. Synesis returned `integration_conflict` /
`TESTS_FAILED` and `request_human_help`; no grant, snapshot, validation,
integration, or closure was reached. This is agent-compliance evidence, not a
new production lifecycle defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0486-exact-rule-diagnostic-2026-08-24.md`.

- Exact next code action: run a fresh bounded two-agent diagnostic with the
  current bundle and a rule forbidding unprojected integration checks or
  lifecycle transitions during ordinary `IMPLEMENT`; capture every
  `get_next_action` projection and following action.
- Do not modify production code unless an exact projected action fails. If the
  agents choose another unprojected action, preserve it as compliance
  evidence. Run the second ordinary unattended acceptance only after the
  diagnostic completes.
- Run focused SYN-039 tests, validators, Javadocs, Doctor, and
  `git diff --check`; keep the root Git stall, bootstrap migration failures,
  and unrelated Doctor warnings separate.
- Do not push or create SYN-040.

## CP-0485 continuation

The clean-harness exact-rule diagnostic used fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0485-001`, with the
harness outside the project and a clean control checkout before launch. Both
independent GPT-5.6 Luna sessions used the current bundled MCP
(`0.1.0-SNAPSHOT`, SHA-256
`27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`), reached
the same project in distinct `ready / isolated` sessions, held disjoint claims,
and converged on WorkGroup `a5b6fdc4-51cb-3398-be5a-76126258984f`.

The reviewer executed the exact projected `request_coordination` admission
action. The owner executed the exact projected `respond_coordination` action
for requests `4a2d5e88-22b4-40d6-95b3-2053472487b0` and
`e4617626-b3b8-4772-99d1-57b3b7ffea03`; grants
`ce12bf95-e493-38c7-a75b-fc78f5b03782` and
`7b4f4964-8631-3b80-bb99-0552b05c67d7` targeted the reviewer at epoch 1.
The owner subsequently chose unprojected `finish_lane` during ordinary
`IMPLEMENT`; preserve this as agent-compliance evidence only.

The first exact projected-action failure was reviewer recovery:
`workspace_stale` projected `ensure_session({})`, and two exact retries both
returned `internal_failure` / `request_human_help`. No grant consumption,
snapshot review, validation, or closure occurred. Final WorkGroup state was
ACTIVE; the integrated control checkout was clean at `166228f5`. Doctor was
DEGRADED with six warnings, including two `stale_session_lease` warnings.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0485-exact-rule-diagnostic-2026-08-24.md`.

- Exact next code action: reproduce the live reviewer stale-session recovery
  in a deterministic two-session fixture and trace lease, heartbeat,
  connection, binding, worktree, process-anchor, and provider-process state
  through `ensure_session`; fix only a proven fail-closed readiness defect.
- Do not run the second ordinary acceptance until the bounded diagnostic
  completes, and do not modify production code for the owner's unprojected
  lifecycle choice.
- Run focused session/readiness/MCP tests, SYN-039 tests, validators, Javadocs,
  Doctor, and `git diff --check`; keep the root Git stall, bootstrap migration
  failures, and unrelated Doctor warnings separate.
- Do not push or create SYN-040.

## CP-0483 continuation

The fresh post-fix diagnostic fixture
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0483-001` used the
current bundled MCP and two distinct `ready / isolated` GPT-5.6 Luna sessions.
They held disjoint claims and converged on WorkGroup
`af1807bc-ab46-3c98-8908-7073a807a7a6`. Agent A published snapshot
`snap_2ecbf452a75a69a8048168e6a1f177f2`; the reviewer intent was recorded
first, and the reviewer then received ordinary `IMPLEMENT` with no usable
REVIEW admission action despite the visible implementation snapshot. No
request, grant, validation, integration, or closure state was created.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0483-active-reviewer-projection-2026-08-24.md`.

- Exact next action: reproduce the reviewer-first ordering deterministically,
  trace `reviewActions` producer selection, and minimally project the existing
  review admission action from WorkGroup/intent/epoch/snapshot provenance.
- Focused MCP/workspace tests, workspace Javadocs, validators, Doctor
  structural checks, and `git diff --check` pass. Commit `9e6d971` is local.
- Agent A and B both made unprojected lifecycle choices; preserve those as
  compliance evidence, not as production failures.
- Do not push or create SYN-040. Keep the Git subprocess stall, bootstrap
  migration failures, and Doctor warnings separate.

## CP-0482 continuation

The fresh bounded diagnostic `syn039-cp0481-001` proved the first concrete
post-implementation projection defect. Both agents used the current bundled
MCP, reached distinct ready/isolated sessions, converged on WorkGroup
`ffd58516-2313-3ccc-a402-b20c921d2f8f`, and completed disjoint visible work.
They obeyed the exact-action rule. Repeated `get_next_action` calls still
projected ordinary `IMPLEMENT` with no executable tool or arguments; no REVIEW,
publication, grant, validation, or integration action was exposed.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

- Exact next action: trace and minimally fix the completion-state projection in
  `AgentNextActionService`/coordination projections, with deterministic coverage
  for the existing REVIEW or publication action and exact arguments.
- Preserve fail-closed ownership/epoch/grant/snapshot behavior. Do not push or
  create SYN-040.

## CP-0481 continuation

The corrected post-fix fixture `syn039-cp0480-006` used the current bundled
MCP, the same initialized project, distinct ready/isolated sessions, and
disjoint `todo.py` / `test_todo.py` claims. Both agents converged on WorkGroup
`f0666aa0-31db-3025-a7e7-2e46f3fad1de`. Agent A published snapshot
`snap_0c58f76fb959553d7d64d64ce7b0d21c`, but selected unprojected
`finish_lane`; integration returned `integration_failed` and no REVIEW action
was projected. Evidence is in
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

- Exact next action: run a fresh bounded diagnostic with disjoint claims and
  execute every concrete `get_next_action` projection exactly; preserve the
  first post-publication projection/action mismatch or exact projected failure.
- Do not change production behavior based on unprojected agent actions. Do not
  push or create SYN-040.

## CP-0480 continuation

CP-0480 confirmed deterministic WorkGroup convergence and fixed the narrower
projection defect where REVIEW admission exposed `request_coordination` with
empty executable arguments. Evidence:
`docs/evidence/syn039-unattended-todo-cp0480-convergence-projection-2026-08-24.md`.

Immediate next action: run fresh post-fix diagnostic and ordinary unattended
Todo acceptances with the rebuilt current MCP. Capture exact projections and
the first later lifecycle blocker. Keep Git, bootstrap migration, Doctor, and
cleanup issues separate. Do not push or create SYN-040.

- SYN-039 is ACTIVE: Autonomous Workgroup Completion.
- SYN-038 remains DONE at CP-0458; its prior App Server history, tag,
  acceptance evidence, and `turn_interrupted_command_remained_active`
  limitation remain preserved.
- Repository branch: master.
- The reviewer-validation and producer-publication SYN-039 slices are
  implemented and verified by focused deterministic tests; the full root check
  is incomplete because the recurring Git subprocess stall reproduces in
  `McpServerTest`.
- Primary failure input: the user-supplied unattended Todo smoke test. The
  reproduced baseline is recorded in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Evidence: `docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`
  and `docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; then rerun the explicit two-agent acceptance with
  strict exact `get_next_action` execution and no manual relay.
- Exact next code action: preserve the first typed result after the reviewer
  submits the projected `request_coordination(work_group_join)` action. No
  production change is authorized by CP-0477. Keep `SYN-014E` paused; do not
  create SYN-040.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and SYN-039
  must not add a daemon, UI, Fleet system, central orchestrator, or launcher.

## CP-0479 continuation

The agent-facing clarification is implemented and verified in the managed
manual and `get_next_action` description. `IMPLEMENT` without a concrete
`recommendedTool`/`arguments` now explicitly means ordinary coding in the
visible assigned worktree; `.synesis/**` remains protected. Evidence and both
acceptance outcomes are in
`docs/evidence/syn039-unattended-todo-cp0479-contract-and-ordinary-acceptance-2026-08-24.md`.

The bounded diagnostic reached shared WorkGroup review admission, grants,
snapshot publication, and integrated control checkout commit `24ed805`, but
the owner once chose unprojected `finish_lane` and had to retry; no structured
review validation decision was captured and three worktrees remained.

The second ordinary acceptance did not converge the agents into one shared
WorkGroup. Both published separate snapshots and hit `integration_blocked`; a
non-projected integration-check payload also returned `TESTS_FAILED` despite
green pytest. Treat this as acceptance evidence, not a speculative production
fix.

Immediate next action: inspect the existing ordinary peer/WorkGroup discovery
contract and identify the smallest evidence-backed discoverability/convergence
slice. Preserve the separate Git stall, bootstrap migration failures, and
Doctor warnings. Do not push or create SYN-040.

## CP-0478 continuation

Evidence is recorded in
`docs/evidence/syn039-unattended-todo-cp0478-protocol-diagnostic-2026-08-24.md`.
Both agents used the current bundled MCP, the same project root, distinct
connection IDs, exactly ten tools, and reached `ready / isolated`. Their
initial `IMPLEMENT` projections exposed only permitted operation classes; no
specific lifecycle action was available before a WorkGroup existed. Both
agents then selected the unprojected `read_file(".synesis/project.json")`
path and received `blocked / invalid_path`. Agent B confirmed the project
metadata using `git show HEAD:.synesis/project.json` and stopped. No
coordination state was created, so no second ordinary-agent acceptance was
run.

This is agent-selected hidden-path inspection, not a proven production
protocol defect. Immediate next action: assess the MCP hidden-metadata path
contract, then rerun only a bounded diagnostic whose initial repository
inspection uses valid operations. Do not change lifecycle production code,
push, or create SYN-040.

## CP-0471 continuation

The owner REVIEW-acceptance projection is implemented and covered by
`McpSyn039SliceTest`: the exact request, WorkGroup, intent, epoch, and strict
`respond_coordination` arguments are now exposed. Focused verification is
green. The root check remains incomplete at the Git subprocess stall in
`WorkspaceCliTest.setUp:74`; Doctor remains DEGRADED. The fresh unattended
rerun stopped before this slice at an existing `overlapping_claim` admission
blocker, so it must be rerun with an isolated initial owner before drawing a
SYN-039 lifecycle conclusion. Do not push yet; do not create SYN-040.

Immediate next action: run
`powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
launch the exact fresh two-agent Todo acceptance with isolated initial
ownership and no manual relay. Preserve the next concrete lifecycle failure.

## CP-0472 continuation

The fresh two-agent run stopped before WorkGroup creation: both agents received
`workspace_not_ready` and a projected `ensure_session` recovery action. The
fixture remained at coordination sequence zero, with no claims, requests,
grants, snapshots, validation, integration, or closure. Evidence:
`docs/evidence/syn039-unattended-todo-workspace-not-ready-2026-08-23.md`.
Do not change production code yet. First reproduce the same state using a
deterministic per-project MCP/session fixture and inspect readiness binding.
Keep the recurring Git stall separate, do not push, and do not create SYN-040.

## CP-0473 continuation

The Codex provider configuration now pins the initialized project root with
`--project`; deterministic provider/session tests pass and direct MCP returns
`ready/isolated`. Evidence:
`docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.

The fresh unattended rerun still stopped before lifecycle creation because the
agent harness used an incompatible/stale MCP distribution and reported schema
v2 as unsupported. Immediate next action: install/use the current bundled
Synesis MCP distribution for both agents, rerun the exact unattended Todo test
with no relay or manual transitions, and preserve the first lifecycle blocker.
Keep the Git stall and bootstrap migration-test failures separate. Do not
push, create SYN-040, or broaden the task.

## CP-0474 continuation

The current-bundle rerun is recorded in
`docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`. Both independent
Luna High agents used the rebuilt project-pinned MCP executable and reached
`ready / isolated`. The reviewer discovered the WorkGroup, consumed REVIEW
grant `496f1893-ca32-3939-82a1-24f860dea86a`, and the implementer passed four
pytest tests. The first real lifecycle blocker is the implementer's projected
`PUBLISH` action remaining at `snapshot_publication_required`; no immutable
snapshot or validation was reached.

Exact next action: reproduce this owner-side `PUBLISH` stop deterministically,
trace why the implementer does not execute the already-projected snapshot
publication path, and implement only that narrow protocol fix if confirmed.
Then rerun the exact unattended Todo test. Keep cleanup, Doctor, ownership,
integration redesign, the Git stall, and bootstrap migration failures
separate. Do not push or create SYN-040.

## CP-0475 continuation

The executable `finish_lane` projection now carries the existing summary from
`nextProtocolPayload`. The deterministic MCP fixture published a real immutable
snapshot and exposed its ID in reviewer coordination status. Evidence:
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

The fresh agent-harness attempts did not produce a valid shared WorkGroup: one
stopped at `workspace_not_ready`, one saw no peer WorkGroup, and a second retry
remained non-terminal until bounded shutdown. Immediate next action: run the
exact two-agent Todo acceptance with both independent MCP processes verified
against the current bundled executable and project root, then preserve the
first post-publication lifecycle blocker. Do not modify production behavior
speculatively, push, or create SYN-040.

## CP-0476 continuation

Evidence is recorded in
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.
The exact current bundled MCP passed an explicit two-connection control
preflight for the fresh fixture: protocol `2025-06-18`, ten tools, same project
ID, distinct isolated worktrees, and `ensure_session(refresh=true)=ready`.

Both independent Luna High agent harnesses failed before Todo work with three
repeated `retry_required / workspace_not_ready / ensure_session` results. No
WorkGroup or lifecycle state was created. Immediate next action: reproduce the
agent-route difference and record its effective MCP executable, startup line,
project arguments, connection identity, and readiness trace. Only fix a
provider/harness distribution or project-pin defect if that evidence proves
one; do not change production lifecycle code speculatively. Keep the Git stall,
bootstrap migration failures, and Doctor warnings separate. Do not push or
create SYN-040.
