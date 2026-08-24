# SYN-039 CP-0515 exact-action diagnostic — post-grant continuation

Date: 2026-08-24

## Harness and project

- Fresh project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0515-001`
- Project ID: `1a23af34-9cbd-4556-88bd-2e2cd95b337a`
- Seed commit: `b5f53f2`; control checkout remained clean at
  `5a7a5f8b24d38d8e6880645c40b9f969da3fce72`
- Current bundled MCP executable:
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256:
  `15967D97D566BE1F758098A32DDD54CFB3F2EB6B8A641EA9EA47A7BB1A05544F`
- MCP version `0.1.0-dev.local`, protocol `2025-06-18`, exactly ten raw tools
- Provider `codex`, project pinned with `--project <project-root>`
- Diagnostic rule supplied to both agents: execute every concrete
  `get_next_action` recommendation unchanged; execute projected
  `WAIT -> get_next_action({})` after a bounded wait and remain engaged while
  the WorkGroup or unresolved coordination state is active. No relay or
  manual lifecycle transition was used.

Agent A used session `session-44799414-2d73-4e58-867c-2023dfe540e7`,
participant `agt_9389220c-7bae-3c1d-b988-2305f7ad3103`, isolated worktree
`...\\worktrees\\session-44799414-2d73-4e58-867c-2023dfe540e7`.
Agent B used session `session-c0b43534-7519-4a77-ba27-a273f1a560bd`,
participant `agt_4768a4f2-4a85-3d52-9a80-b559ab981131`, isolated worktree
`...\\worktrees\\session-c0b43534-7519-4a77-ba27-a273f1a560bd`.

## Claims and WorkGroup

- A implementation intent: `060beebf-2595-35b6-9b3a-ad56bbf5c4ee`, epoch 1,
  claim `path_exact:todo.py`.
- B test intent: `5c69c2e9-8eb8-38ed-bb8b-ba89593ffdc0`, epoch 1, claim
  `path_exact:test_todo.py`.
- WorkGroup: `ed155087-41fd-39e6-8380-d2c5663aae64`, `ACTIVE`.
- REVIEW request: `beafd523-da12-4128-90e1-c1107aa5f684`, accepted by the
  owner.
- REVIEW grant: `d401e6d4-fc52-3055-9355-ef083aeb48ad`, single-use, epoch 1,
  targeted at A participant `agt_9389220c-7bae-3c1d-b988-2305f7ad3103`.

B established the WorkGroup first, so the logical review request targeted B's
test intent. A still completed the implementation in its own disjoint lane;
the run is evidence of protocol reachability, not a claim that the task roles
were semantically ideal.

## Projection-to-action trace

1. A received `retry_required / session_not_ready / ensure_session` and
   executed the exact `ensure_session({})` recovery. It then sent a
   claim-bearing `ensure_session` for `todo.py` and reached `ready / isolated`.
2. B independently sent a claim-bearing `ensure_session` for `test_todo.py`
   and initially received ordinary `IMPLEMENT`.
3. A's `get_next_action({})` projected `request_coordination` with
   `kind=work_group_join`, WorkGroup
   `ed155087-41fd-39e6-8380-d2c5663aae64`, B intent
   `5c69c2e9-8eb8-38ed-bb8b-ba89593ffdc0`, and the immutable-snapshot review
   proposal. A executed those arguments successfully.
4. B's `get_next_action({})` projected the exact owner response
   `respond_coordination` for request
   `beafd523-da12-4128-90e1-c1107aa5f684` with `ACCEPTED / admitted`. B
   executed it successfully.
5. B's next projection was the exact continuation
   `WAIT -> get_next_action({})` with `review_grant_consumption`; B executed
   two such polls while the grant was pending.
6. A's `get_next_action({})` projected exact grant consumption through
   `request_coordination(kind=work_group_join)` with grant
   `d401e6d4-fc52-3055-9355-ef083aeb48ad`, B intent, WorkGroup, epoch 1, and
   `targetParticipant=agt_9389220c-7bae-3c1d-b988-2305f7ad3103`. A executed
   the complete projected payload and Synesis returned `status=completed,
   status=CONSUMED`.
7. A then received `WAIT -> get_next_action({})` with
   `nextProtocolKind=review_validation`, `state=SNAPSHOT_PENDING`, and
   `snapshotRequired=true`. A executed three exact continuation polls and
   stopped.

No exact projected lifecycle call failed. No `finish_lane` publication action
was projected to either participant during the observed run. No snapshot,
validation decision, integration, or WorkGroup terminal transition occurred.

## Final diagnostics and classification

Final collaboration status showed the two active participants with disjoint
claims, accepted REVIEW request, active WorkGroup, and the targeted grant
record. The control checkout remained clean at the seed commit. Final Doctor
was `DEGRADED` with six warnings, zero errors, and zero critical findings:
two stale session leases, command namespace reconciliation, command capacity or
retention, and two provider migration warnings.

The first blocker is still agent continuation/compliance, not a proven
production protocol defect: both agents executed the exact projected WAIT
continuations, but stopped before the producer's next publication projection
was observed. No production code changed for CP-0515.

Raw agent JSONL and MCP logs are retained at:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0515-001`.
