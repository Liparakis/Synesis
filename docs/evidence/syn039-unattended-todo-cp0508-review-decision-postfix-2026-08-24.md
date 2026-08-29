# SYN-039 CP-0508 — Review Decision Projection Postfix Diagnostic

Date: 2026-08-24
Task: SYN-039 — Autonomous Workgroup Completion
Result: the invalid review-result projection is fixed; the diagnostic later
stopped on agent-compliance evidence before clean WorkGroup closure

## Fixture and preflight

- Fresh disposable project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0508-001`
- Harness logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0508-001\logs`
- Seed commit: `8477d47`
- Managed baseline: `d6838ed1c9930e3cbace0c43ac1bb1e408c84b9a`
- Project ID: `a78f34d6-2e57-4865-bd93-f93db8326296`
- MCP executable for both agents:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `49D0FFB4696EABE62F3C54BF98ED0011FDC7F4CDD63B842DC2E23BF7511E36B8`
- Both agents used GPT-5.6 Luna through independent Codex processes,
  confirmed exactly ten Synesis tools, and reached `ready / isolated`.
- Agent A worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\a78f34d6-2e57-4865-bd93-f93db8326296\worktrees\session-80261ec2-4545-4235-b0db-80684d351225`
- Agent B worktrees:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\a78f34d6-2e57-4865-bd93-f93db8326296\worktrees\session-a19e8aca-ed5f-4c1b-8ce4-a8f32589601a`
  and its fail-closed recovery allocation
  `session-a19e8aca-ed5f-4c1b-8ce4-a8f32589601a-recovery-7c989cfa-6501-49ae-a8af-d6f75c434be2`.

## Durable coordination state

- WorkGroup:
  `e0ef5af5-844c-3f77-b4ad-29767b4b13c3` — `ACTIVE`
- Agent A participant:
  `agt_9a5eeaee-d047-3438-a7d0-28db888c63be`; intent
  `6d5b7e98-88e9-3868-9516-58f90df55a7f`; claim `PATH_EXACT:todo.py`;
  epoch `1`
- Agent B participant:
  `agt_cea8260d-12f2-3d26-8230-b78dabf5ca10`; intent
  `9766b0bf-606e-39ba-b4ad-1e5b03064168`; claim
  `PATH_EXACT:test_todo.py`; epoch `1`
- REVIEW requests:
    - B → A: `9dd58277-6772-43d3-a54a-e6f04cb25ed7`, `ACCEPTED`
    - A → B: `8e4a3a80-0276-40a7-8ab4-29fd14c298cc`, `ACCEPTED`
- REVIEW grants:
    - `c82b8803-5fd7-3e97-82a3-f5d8a7302ac7`, target B, A intent, epoch 1;
      consumed once
    - `f879b4ff-047c-3dc8-8b70-2568a5d4a4a3`, target A, B intent, epoch 1;
      still available at the end of the run

## Progress and exact projection evidence

1. Both agents initially received ordinary `IMPLEMENT` with no executable
   lifecycle action. A implemented `todo.py`; B waited for review admission.
2. A accepted the projected REVIEW request from B. B then received the exact
   grant-consumption projection including `grantId`, `intentId`, `claimEpoch`,
   `workGroupId`, and `targetParticipant`.
3. B first omitted the projected `targetParticipant` while consuming grant
   `c82b8803-5fd7-3e97-82a3-f5d8a7302ac7`. Synesis correctly returned
   `policy_denied` / `COORDINATION_FIELD_REQUIRED:targetParticipant`. B then
   retried with the complete projected payload and consumption succeeded.
   This is agent-compliance evidence, not a production authorization defect.
4. A followed the exact `WAIT` → `get_next_action` continuation and then the
   exact publication projection:

   ```text
   get_next_action: snapshot_publication_required
   recommendedTool: finish_lane
   arguments: {"summary":"Publish the completed immutable snapshot"}
   finish_lane: completed
   snapshotState: PUBLISHED
   integrationState: integrated
   ```

   Snapshot: `snap_806145a00668f970adaaf4af734a9d81`
   Snapshot commit: `24702bb2b8f1287e14f05da7f88f2c0b925e2b7b`
   Base commit: `d6838ed1c9930e3cbace0c43ac1bb1e408c84b9a`
   Changed path: `todo.py`

5. After B consumed the grant and recovered its stale worktree, B received
   the corrected review-validation projection repeatedly:

   ```text
   nextAction: respond_coordination
   nextProtocolKind: review_validation
   nextProtocolPayload:
     grantId: c82b8803-5fd7-3e97-82a3-f5d8a7302ac7
     intentId: 6d5b7e98-88e9-3868-9516-58f90df55a7f
     claimEpoch: 1
     snapshotId: snap_806145a00668f970adaaf4af734a9d81
   reviewDecision:
     field: result
     required: true
     allowedResults: [accepted, rejected]
     rejectionReasonRequired: true
   workflow.recommendedTool: absent
   workflow.arguments: absent
   ```

   B selected `accepted` after inspecting the visible implementation and
   passing four tests, then called the existing `respond_coordination` tool
   with a valid structured payload. Synesis returned `ACCEPTED` and preserved
   the WorkGroup as `ACTIVE` while the reciprocal lane remained unresolved.
   This proves the CP-0507 invalid `accepted|rejected` projection is fixed.

6. B accepted A's reciprocal REVIEW request and correctly followed the
   projected `WAIT` → `get_next_action` continuation. The reciprocal grant
   targeting A remained available. A had already completed its own lane and
   stopped before polling again to consume that grant; therefore B could not
   publish its own test-only snapshot and the WorkGroup did not close.

## Final state and classification

- Control checkout contains the integrated A snapshot; its latest visible
  integration commit is `9b33f54` (`Synesis immutable lane snapshot`).
- Todo tests in B's isolated worktree passed `4` tests.
- Validation: A snapshot accepted by B; reciprocal validation not reached.
- Integration: A snapshot integrated; B snapshot not published.
- WorkGroup: `ACTIVE`.
- Available grant: `f879b4ff-047c-3dc8-8b70-2568a5d4a4a3`, targeted to A.
- Doctor: `DEGRADED`, six warnings, zero errors, zero critical findings:
  two `stale_session_lease`, `command_namespace_reconciliation_required`,
  `command_capacity_or_retention`, and two `provider_migration_required`.
  These warnings did not cause the review projection failure.

The first observed deviation was an agent omitting a projected grant field;
the later stop was an agent not polling after peer-side acceptance. Every
server action that was executed with the complete projected arguments either
succeeded or failed closed as designed. No new production defect is proven by
CP-0508, so no production change follows from this run and no ordinary second
acceptance was started.

Raw traces:

- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0508-001\logs\agent-a.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0508-001\logs\agent-b.jsonl`
