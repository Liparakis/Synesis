# SYN-039 CP-0531 — exact-projection diagnostic and reciprocal-review continuation

## Scope and bundle

This evidence records the narrow reciprocal-review gating fix and the two
post-fix acceptance observations performed before checkpoint CP-0531. No
remote state was modified, no coordination transition was manually triggered,
and no SYN-040 was created.

The current source change is:

- `workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java`
  now leaves an active lane in ordinary `IMPLEMENT` when a reciprocal REVIEW
  grant exists but the assigned worktree has no claim-covered publishable
  changes. Once such changes exist, publication remains gated on the exact
  grant/epoch/participant protocol.
- `mcp/src/test/java/org/synesis/mcp/application/McpSyn039SliceTest.java`
  adds `activeImplementerCanContinueBeforeReciprocalReviewerConsumesGrant`.
  The regression was red with `WAIT` before the fix and green after it.

The rebuilt bundled MCP used by both acceptance runs reported:

```text
version=0.1.0-SNAPSHOT
commit=bc334ac
sha256=E91A08ADD236925A42D7A11F5F89AA615E807BB23C822925BD77E17EA0D6BEFB
```

## CP-0538 ordinary post-fix acceptance

Fixture:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0538-001`

Project ID: `e573e93e-f093-41f2-8ea0-01022e286cc9`.

Participants and disjoint epoch-1 claims:

- implementation: `agt_82a1574f-a526-3eef-ba1b-325719ce8fa0`, intent
  `d008abde-a1a3-3767-a384-d6c6c6723199`, claim `todo.py`;
- test: `agt_10acf3e7-7490-3536-a022-c8d7440260ec`, intent
  `307b0d1d-5531-3179-b53b-02c67562a068`, claim `test_todo.py`;
- shared WorkGroup: `66643206-d05a-32e7-8ff4-8d650f2419e5`.

The run reached the previously blocked continuation: the test participant
performed visible work, received the exact `finish_lane` projection, and
published/integrated `snap_0a56f9c60a5ad8a0be36309da0ec58f3`. The implementation
participant then received a REVIEW grant and structured-REJECTed that snapshot,
routing it back to the test intent. The implementation participant later
published/integrated `snap_e41e7ca7a55e9521c8ddf44e51fabe5a` after implementing
`TodoList.complete`.

The run did not prove clean closure: the reciprocal review continuation ended
with provider sessions still engaged at a pending grant boundary. The control
checkout contained both integrated lane commits and its Todo tests passed, but
WorkGroup terminal closure was not observed. This was not a projected-action
failure.

## CP-0539 bounded exact-projection diagnostic

Fresh fixture:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0539-001`

Project ID: `97ddd6b2-2613-486f-aefa-48e44ada6380`.

Both launchers used the current bundled MCP with the same project root and
distinct connection IDs:

- `syn039-cp0539-001-agent-a`, participant
  `agt_30b9f0b0-503e-3803-aaf1-c6b040388055`, intent
  `1afee33d-0f3d-3c2a-a4d1-c43b2fafea8a`, claim `todo.py`, epoch 1;
- `syn039-cp0539-001-agent-b`, participant
  `agt_6a67cbe1-6985-3eee-afb8-25605d56b691`, intent
  `b48e3605-57e9-3661-9e0d-e29122382c39`, claim `test_todo.py`, epoch 1.

Shared WorkGroup: `4fe4dcb2-e4db-3974-b55f-3502d93ddfc9`.

The relevant unchanged projection/action pairs were:

1. A projected `request_coordination(work_group_join)` for B's intent. A
   executed the exact arguments; request
   `2ea76a47-45c4-4d71-801f-dc189c2c8212` was pending and replay remained
   idempotent.
2. B projected the exact owner `respond_coordination` for that request and
   executed it with `ACCEPTED`.
3. A projected the single-use REVIEW grant
   `cbf28d67-8c9e-35e3-a84d-4cbf650628a7`, targeted to A, and executed the
   exact grant-consumption request. Consumption succeeded.
4. A received `WAIT / review_validation / snapshotRequired=true`, then an
   unchanged `workspace_stale -> ensure_session({})` recovery projection.
   A executed the exact recovery action and returned `ready / isolated`.
5. A received `review_decision` for immutable snapshot
   `snap_daac2ca31851e9bba05ff87245d69baf`, commit
   `9757697719d73ef004f4c1d33e670d251ae76009`, with exact grant, intent, and
   epoch. `read_file` exposed the immutable snapshot without write ownership.
   Its pytest command returned exit code 1 (`2 failed, 2 passed`) because the
   test-only snapshot correctly did not contain the implementation. A
   nevertheless submitted `ACCEPT`; that decision is agent-quality evidence,
   not a protocol failure.
6. B projected `finish_lane` with summary `Publish the completed immutable
   snapshot`; B executed the exact action. It returned
   `snapshotState=PUBLISHED`, `integrationState=integrated`, snapshot
   `snap_daac2ca31851e9bba05ff87245d69baf`.
7. B projected the reciprocal REVIEW request for A's intent. B executed the
   exact request `016aa0ca-48a3-4d78-91de-e48b10e33969` and replayed it
   idempotently while A had not yet responded. A later accepted that request,
   but B's provider turn ended before it observed the resulting grant.

At the end of the retained traces:

- A had implemented `todo.py`, passed focused and full visible pytest, removed
  generated `__pycache__`, and remained at `WAIT / REVIEW_GRANT_PENDING` for
  grant `42bf4474-67a1-3efa-984c-0a571be83c49`, targeted to B;
- B had completed and integrated the test snapshot but its last durable
  projection remained the reciprocal `request_coordination` admission;
- the WorkGroup remained `ACTIVE`, no second snapshot was published, and no
  validation/integration/closure for A's implementation was observed;
- control checkout remained at the managed baseline plus the test snapshot and
  `python -m pytest -q test_todo.py` reported `2 failed, 2 passed`.

The diagnostic instruction was followed by the sessions for every concrete
projection they observed. The first stop was provider/session engagement at a
pending reciprocal request/grant boundary, not an unchanged projected action
failure and not a missing usable Synesis projection. The conditional second
ordinary acceptance was therefore not run.

Codex emitted the recurring external model-cache warning
`missing field base_instructions`; MCP startup traces still reported the
current Synesis version/commit and successful tool discovery. This warning is
separate from the Synesis lifecycle result.

## Independent verification

Passed:

- `:workspace:test --tests org.synesis.workspace.AgentNextActionServiceTest`;
- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest.activeImplementerCanContinueBeforeReciprocalReviewerConsumesGrant`;
- `:workspace:javadoc :mcp:javadoc`;
- deferred-register and fixture validators;
- `go vet ./...` in `bootstrap`;
- `git diff --check`.

The full root `check` reached `:cli:test` and reproduced the known Git
subprocess startup stall. At approximately 85 seconds, Gradle test worker
PID `7820` was `TIMED_WAITING` in
`ProcessCommandRunner.execute(ProcessCommandRunner.java:81)` through
`GitProcessRunner.runInternal:129`,
`AdministrativeStateLocator.resolveGitCommonDirectory:67`,
`RepositoryPrivateStateService.ensure:48`, and
`ProjectApplicationService.init:480`. The run was stopped after this thread
evidence; no timeout was enlarged and no hardening was weakened.

The fixture Doctor result was `DEGRADED` with 6 warnings, 0 errors, and 0
critical findings; it recommended reconciliation but performed no mutations.
The root Doctor retains its pre-existing personal-absolute-path warning.
These diagnostics are not shown to cause the reciprocal session stop.

## Conclusion and next action

The narrow reciprocal-review gating fix is verified. CP-0539 does not justify
another production change: Synesis projected usable actions and rejected no
unchanged projected lifecycle action. The next SYN-039 slice should be a fresh
ordinary unattended acceptance focused on whether normal sessions remain alive
through the already-proven reciprocal request/grant and `WAIT` continuations.
Do not add retry orchestration, a daemon, cleanup machinery, or argument repair
from this evidence.
