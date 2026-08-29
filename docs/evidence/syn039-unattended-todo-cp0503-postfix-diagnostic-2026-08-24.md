# SYN-039 CP-0503 post-fix producer polling diagnostic

## Result

This fresh bounded diagnostic ran after the owner-side REVIEW-grant waiting
projection was implemented and the current platform bundle was rebuilt. Two
independent GPT-5.6 Luna Codex sessions used the same initialized project and
explicit per-agent MCP routes. Both passed preflight, converged on one
WorkGroup, and kept disjoint write claims.

The production fix worked. The owner accepted REVIEW, remained active through
the peer's grant consumption, received the exact `finish_lane` projection,
published an immutable snapshot, and integration reported `integrated`.

The first remaining stop was the reviewer after it consumed the grant. It
received the correct `SNAPSHOT_PENDING` → `WAIT` projection with executable
`get_next_action({})` twice, then ended its session before polling after the
producer published the snapshot. No exact projected action failed. This is
agent-compliance evidence, not a new production defect. The ordinary second
acceptance was not run because the bounded diagnostic did not reach validation
and closure.

## Harness and preflight

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0503-001`
- Project ID: `b15b5ecd-ae2b-402a-8298-b438f1762b6b`
- Seed commit: `ffc6960`
- Managed baseline: `b3f40df62e51f32e930679bc467ed0db83a5ddc1`
- Final control checkout: `7c4d11d`, clean
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0503-001`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `D27A9F4D3C833C3C5581DD012254E7AE767D96FC71F53DC2718461CBC6822CD1`
- MCP startup: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, exactly ten tools
- Connections:
    - A: `conn-instance-4f3ae725-b1d5-44f0-826d-d99e55e2391c`
    - B: `conn-instance-c5568202-0242-42a2-9406-4fdab8e0ce25`
- A session/worktree:
  `session-c26afb33-074d-41f9-a782-39cb7d3354ed`
- B session/worktree:
  `session-04d344b0-b4c2-41a1-9f86-c094fad36285`
- Both `ensure_session` calls returned `ready / isolated`.

## Participants, ownership, and WorkGroup

- A participant: `agt_775c66b9-5dd8-3683-934e-cf26602b6679`
    - intent: `15ad633a-756c-372b-b9ea-563ecbed533f`
    - claim: `PATH_EXACT todo.py`, epoch 1
- B participant: `agt_7ee67cb4-185c-32c1-831a-875b671f92e7`
    - intent: `1871005f-b6ba-3af8-a1d9-0508e6952951`
    - claim: `PATH_EXACT test_todo.py`, epoch 1
- WorkGroup: `49082d5e-ecc5-3503-82fb-3d62f37597c8`, `ACTIVE`

## Projection and action trace

1. Both agents received ordinary `IMPLEMENT` projections and performed only
   their visible assigned work. A implemented `TodoList.complete` and passed
   three tests. B added one focused regression test; its isolated test run
   correctly failed while the sibling implementation was absent.
2. A received and executed the exact owner acceptance projection for REVIEW
   request `10fe11a8-c4bc-46ae-a11f-cd70489741d2`, returning `ACCEPTED`.
3. Synesis issued single-use grant
   `c9cb80ae-679d-3290-902c-c55647723aae`, targeting B for A's intent at epoch
    1. B received and executed the exact grant-consumption request with the
       projected grant ID, WorkGroup, intent, target participant, and epoch. The
       result was `status=CONSUMED`.
4. B received two identical projections:

   ```text
   status=ready
   reason=validation_required
   nextAction=wait
   nextProtocolAction=wait
   nextProtocolKind=review_validation
   recommendedTool=get_next_action
   arguments={}
   state=SNAPSHOT_PENDING
   grant=c9cb80ae-679d-3290-902c-c55647723aae
   snapshotRequired=true
   ```

   B executed `get_next_action({})` twice, then stopped. It did not ignore a
   concrete mutation projection; it stopped while the exact WAIT continuation
   remained the only legal next action.
5. A then received the newly available exact projection:

   ```text
   reason=snapshot_publication_required
   nextAction=finish_lane
   recommendedTool=finish_lane
   arguments={"summary":"Publish the completed immutable snapshot"}
   ```

   A executed those exact arguments successfully. The result was snapshot
   `snap_5733de0976ad177cc349e9fa2fbdebcb`, `snapshotState=PUBLISHED`,
   `integrationState=integrated`, with snapshot commit
   `27af7c7b81863fa30c5a73eb42d3e7fdb7eaceb2` and changed path `todo.py`.
6. A's completed-lane projection then exposed a review-admission action for
   B's still-active sibling intent. A executed that exact request, creating
   pending request `1a39ee76-c278-40a2-98eb-ca34220eec38`. B had already
   stopped, so no owner response or second snapshot was reached.

## Final state

- WorkGroup: `49082d5e-ecc5-3503-82fb-3d62f37597c8`, `ACTIVE`
- A: completed lane; claim released; snapshot published and integrated
- B: active intent and `PATH_EXACT test_todo.py` claim; no snapshot
- REVIEW grant: `c9cb80ae-679d-3290-902c-c55647723aae`, consumed once
- Snapshot: `snap_5733de0976ad177cc349e9fa2fbdebcb`
- Validation: none recorded
- Control checkout: clean at `7c4d11d`, containing the integrated A change
- WorkGroup closure: not reached

## Doctor and independent verification issues

Fixture Doctor was `DEGRADED` with six warnings, zero errors, and zero
critical findings. The warning codes were two `stale_session_lease` findings,
`command_namespace_reconciliation_required`, `command_capacity_or_retention`,
and two `provider_migration_required` findings. They did not prevent preflight,
coordination, grant consumption, publication, or integration and remain
separately classified. The known root Git subprocess stall and bootstrap
migration failures are also independent verification issues.

Repository verification after the run:

- Focused `:mcp:test --tests McpSyn039SliceTest`: PASS.
- Focused `:workspace:test --tests AgentNextActionServiceTest`: PASS.
- `:coordination:javadoc :workspace:javadoc :mcp:javadoc`: PASS.
- Deferred and fixture validators: PASS; `go vet ./...`: PASS.
- `git diff --check`: PASS.
- `:link:formatCheck`: FAIL on pre-existing trailing whitespace in
  `docs/agent/checkpoints/CP-0488.md`, `CP-0489.md`, and
  `docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.
- Root `check` reached `:mcp:test` / `:workspace:test` and reproduced the
  known Git subprocess stall. A JDK thread dump captured
  `ProviderApplicationServiceTest.codexInstallationMaterializesHookIntoAssignedWorktree`
  sleeping in `ProcessCommandRunner.execute(ProcessCommandRunner.java:81)`
  while `synesis-process-output` remained blocked in
  `ProcessCommandRunner$OutputCollector.run(ProcessCommandRunner.java:333)`.
  The bounded check was stopped after this evidence; it was not reported
  green.

Raw agent JSONL and MCP startup logs remain under the fixture harness `logs`
directory.
