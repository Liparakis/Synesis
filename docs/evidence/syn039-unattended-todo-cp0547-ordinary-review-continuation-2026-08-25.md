# SYN-039 ordinary Todo acceptance — CP-0544 review-continuation boundary

Date: 2026-08-25

## Scope and harness

This was a fresh disposable Git + Synesis project with two independent Codex
sessions. The agents received only complementary visible coding prompts:

- Agent A: implement `TodoList.complete` in `todo.py`; do not modify
  `test_todo.py`.
- Agent B: add one meaningful `TodoList.complete` regression test in
  `test_todo.py`; do not modify `todo.py`.

No lifecycle instruction, message passing, request acceptance, snapshot
publication, validation, integration, claim repair, or session continuation was
performed by the harness.

Project root:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0547-2026-08-25-001`

Harness:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0547-2026-08-25-001`

Project ID: `12683cce-8f72-4d1f-8aaa-8ffd07c0c5cd`

The seed was `a3c18b4` (`prepare fresh Synesis seed`). Synesis initialization
created managed baseline commit `36560970844c63fff84ed5932a76ddff9dcfa624`.
Provider installation reported `MCP_CONFIG_STATUS=INSTALLED`, `MCP_HEALTH=PASSED`,
and `WORKTREE_BINDING_STATUS=BOUND`.

Both wrappers resolved to the current bundled executable:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

The wrapper connection IDs were:

- `syn039-cp0547-ordinary-001-agent-a`
- `syn039-cp0547-ordinary-001-agent-b`

`synesis version` reported `SYNESIS_VERSION=0.1.0-dev.local`,
`RECORD_FORMAT=SDR2`, `RECONCILIATION_PROTOCOL=PRP1`, and
`BUILD_COMMIT=UNKNOWN`. Both MCP traces reached `initialize_response_written`
and `tools_list_received`.

## Participants and ownership

| Agent | Participant | Session worktree | Intent | Claim | Epoch |
|---|---|---|---|---|---:|
| A / implementation | `agt_6d0b065d-b353-358f-9ec0-08da6531a3db` | `session-57ff7758-a07d-47f3-8bc8-408ac09fcf7d` | `a39919b5-72d6-3152-ae8b-cb2e344ffbf3` | `PATH_EXACT todo.py` | 1 |
| B / test | `agt_169f9a4c-61ed-3767-a080-bce7c3138d04` | `session-db84e866-b311-4509-97d1-9cf3a327b849` | `079b28ef-9f3a-3c72-af42-a56894089302` | `PATH_EXACT test_todo.py` | 1 |

Both sessions reached `ready / isolated`. Claims were disjoint and the two
participants converged on one WorkGroup:

`31941d9a-11dd-3b49-98ab-86042f5b6faa`

## Projection and action trace

The raw traces are `logs/agent-a.jsonl` and `logs/agent-b.jsonl` under the
harness directory above.

1. A initially received `session_not_ready` with the exact recovery action
   `ensure_session({})`; it executed that action with the claim-bearing task
   and reached an isolated session.
2. A received the executable REVIEW admission projection:

   `request_coordination({"kind":"work_group_join","payload":{"intentId":"079b28ef-9f3a-3c72-af42-a56894089302","workGroupId":"31941d9a-11dd-3b49-98ab-86042f5b6faa","proposal":"Review the immutable snapshot for this work group"}})`

   It executed those arguments unchanged. Request
   `e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae` was created.
3. A then received `WAIT -> get_next_action({})` with
   `review_admission`, the request ID, target participant, WorkGroup, and
   claim epoch. It polled repeatedly; it did not replay the request.
4. B received the exact executable owner response:

   `respond_coordination({"kind":"coordination_response","payload":{"coordinationRequest":"e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae","coordinationStatus":"ACCEPTED","proposal":"admitted"}})`

   It executed unchanged and the request became `ACCEPTED`.
5. B then received `WAIT -> get_next_action({})` for
   `review_grant_consumption`, with grant
   `bd3d274c-e4e6-3c63-930d-8ba19b783c5d`, intent, epoch `1`, target A, and
   `snapshotRequired=true`. It polled the projected continuation several
   times.
6. A received the exact single-use REVIEW grant projection. Its first call
   omitted projected `targetParticipant` and Synesis returned:

   `blocked / policy_denied / COORDINATION_FIELD_REQUIRED:targetParticipant`

   A re-read the projection and retried with the exact complete payload,
   including `targetParticipant=agt_6d0b065d-b353-358f-9ec0-08da6531a3db`.
   The retry returned `status=CONSUMED` for grant
   `bd3d274c-e4e6-3c63-930d-8ba19b783c5d`.
7. After consumption, A received the valid review continuation
   `WAIT -> get_next_action({})` with `review_validation`, the consumed grant,
   WorkGroup, intent, epoch, and `snapshotRequired=true`. No snapshot existed
   yet, so no validation action was executable.

The provider sessions then exited. No exact projected action failed. The last
projected action available to B before its session ended was the safe polling
continuation; the session did not remain engaged long enough to observe and
execute the later producer snapshot-publication projection after A consumed
the grant.

## Lifecycle state reached

- WorkGroup: `ACTIVE`
- Request `e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae`: `ACCEPTED`, kind `REVIEW`
- REVIEW grant `bd3d274c-e4e6-3c63-930d-8ba19b783c5d`: consumed once by A
- Snapshots: none
- Validation decisions: none
- Integration: none
- Participant A: `ACTIVE`, `todo.py` claim retained
- Participant B: `ACTIVE`, `test_todo.py` claim retained
- Coordination sequence: WorkGroup state was active; no terminal closure

Control checkout remained at managed baseline commit `3656097`. Its visible
tests reported `1 passed, 1 failed`: the implementation was not published, so
`TodoList.complete` still raised `NotImplementedError` in the control checkout.

## Classification

This run is `PARTIAL` acceptance evidence and does not prove a production
protocol defect. Synesis exposed valid exact actions, preserved ownership and
fail-closed grant authorization, and returned an explicit polling continuation
while the WorkGroup remained active. The first blocker is ordinary provider
engagement: the sessions stopped at valid `WAIT -> get_next_action({})`
continuations instead of remaining engaged until the next lifecycle action.

The initial discarded fixture setup that used a Windows CRLF checkout is not
part of this acceptance result. The corrected fixture used a canonical LF
checkout and completed Synesis initialization successfully.

## Diagnostics and separate verification issues

Fixture Doctor:

```text
DOCTOR_RESULT=DEGRADED
FINDINGS=6
CRITICAL=0
ERRORS=0
WARNINGS=6
CLEANUP_RECOMMENDED=false
RECONCILIATION_RECOMMENDED=true
REPAIR_AVAILABLE=true
MUTATIONS_PERFORMED=0
NEXT_ACTION=prepare_repair_plan
```

The known focused MCP test stall reproduced independently after compilation.
At the captured point, Gradle test worker PID `2108` was in
`McpSyn039SliceTest.rejectedReviewRoutesActionableWorkToTheImplementer` line
`801`, blocked in `ProviderSessionBindingService.verifyWorkspaceTrust` →
`GitProcessRunner.run` → `ProcessCommandRunner.execute` during
`ensureSession`. It was stopped without increasing the timeout.

Repository validators and `git diff --check` passed. Bootstrap `go vet ./...`
passed; bootstrap `go test ./...` retained the known three
`update migrations not prepared` failures. These issues were not causal to the
acceptance lifecycle stop.
