# SYN-039 CP-0528 post-fix exact-action diagnostic

Date: 2026-08-24

## Scope and harness

This was a fresh bounded diagnostic after CP-0527. Both independent agents
were instructed to execute an unchanged concrete `get_next_action` projection
before choosing another Synesis lifecycle action. Ordinary `IMPLEMENT` with no
executable lifecycle action remained ordinary visible-file work. No manual
relay, request acceptance, snapshot publication, validation, or integration
was performed by the harness.

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0528-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0528-001`
- Project ID: `5f59245e-e4f1-4847-b3e8-b0a063a532a0`
- Seed commit: `5d9de21 seed Todo diagnostic acceptance`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `133E5C2D2A12ADF8FC3E72113BAF11A90DA0E7AB17FB536BF2E92C3ED0131D6C`
- MCP identity: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, exactly 10 tools
- MCP connection instances: A `syn039-cp0528-agent-a`, B
  `syn039-cp0528-agent-b`

Both sessions reported `ready` / `isolated` through the current bundled MCP
and exposed the same project. The harness was stopped after the first
post-admission lifecycle boundary; no second ordinary acceptance was run.

## Participants and shared state

- Agent A session `session-88e1f0f9-0810-48c4-9abe-35f98393c11c`, participant
  `agt_aa011217-3088-3b77-b960-f118e1e90ed0`, intent
  `966fc0d5-b62e-3947-958d-95496c817c10`, epoch 1, claim `PATH_EXACT todo.py`.
- Agent B session `session-11f54a38-2864-465e-a9be-cfc63df19f52`, participant
  `agt_4e8b4908-3e49-37e0-a134-a5d24da3a288`, intent
  `e33ccce4-7e74-3ff8-b12f-41268605ece8`, epoch 1, claim
  `PATH_EXACT test_todo.py`.
- Shared WorkGroup: `0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda`, version 1,
  terminal state `ACTIVE`.
- REVIEW request B to A:
  `d9a18f1c-ab05-444d-81d4-89ab21393f55`, later `ACCEPTED`.
- Reciprocal REVIEW request A to B:
  `442c3273-0d99-4252-b2ee-de0ac4fb9b1d`, remained `PENDING`.
- Single-use REVIEW grant:
  `a31a2ae9-30a1-352d-8335-5ec42499a268`, target participant B, target intent
  A, claim epoch 1. It was eventually consumed successfully.

## Projection/action trace

### REVIEW admission

Agent B first received `REVIEW_ADMISSION_REQUIRED` and the executable
projection:

```json
{
  "recommendedTool": "request_coordination",
  "arguments": {
    "kind": "work_group_join",
    "payload": {
      "intentId": "966fc0d5-b62e-3947-958d-95496c817c10",
      "workGroupId": "0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda",
      "proposal": "Review the immutable snapshot for this work group"
    }
  }
}
```

B executed those arguments unchanged. Synesis created request
`d9a18f1c-ab05-444d-81d4-89ab21393f55`; A then executed the projected
`respond_coordination` acceptance exactly and the request became `ACCEPTED`.
This confirms the CP-0527 admission path works under separate live sessions.

### Grant consumption

After admission, B received this exact grant projection:

```json
{
  "recommendedTool": "request_coordination",
  "arguments": {
    "kind": "work_group_join",
    "payload": {
      "grantId": "a31a2ae9-30a1-352d-8335-5ec42499a268",
      "intentId": "966fc0d5-b62e-3947-958d-95496c817c10",
      "claimEpoch": 1,
      "workGroupId": "0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda",
      "targetParticipant": "agt_4e8b4908-3e49-37e0-a134-a5d24da3a288"
    }
  }
}
```

B first omitted `targetParticipant` and received the expected fail-closed
response:

```json
{"status":"blocked","reason":"policy_denied",
 "result":{"error":"COORDINATION_FIELD_REQUIRED:targetParticipant"}}
```

This is agent-compliance evidence, not a production defect: the projection
contained the field, and B later executed the unchanged projection and
received `{"status":"CONSUMED","grantId":"a31a2ae9-30a1-352d-8335-5ec42499a268"}`.

### Publication and integration

A received:

```text
get_next_action -> status=ready, reason=snapshot_publication_required,
nextAction=finish_lane,
arguments={"summary":"Publish the completed immutable snapshot"}
```

A executed the exact arguments successfully. The result reported:

- snapshot `snap_2d4def43740098712b51e82199d84153`;
- snapshot state `PUBLISHED`;
- snapshot commit `a4af95b008cb5dd891eaa22fecc97a597b386483`;
- changed path `todo.py`;
- integration state `integrated`.

The control checkout ended at integration commit `7a96c85` and its visible
Todo tests passed `2 passed`.

### Reviewer validation boundary

After the snapshot was visible in coordination state, B received:

```json
{
  "status":"ready",
  "reason":"validation_required",
  "nextAction":"review_decision",
  "nextProtocolKind":"review_validation",
  "nextProtocolPayload":{
    "grantId":"a31a2ae9-30a1-352d-8335-5ec42499a268",
    "intentId":"966fc0d5-b62e-3947-958d-95496c817c10",
    "claimEpoch":1,
    "snapshotId":"snap_2d4def43740098712b51e82199d84153"
  },
  "reviewDecision":{
    "required":true,
    "field":"result",
    "allowedResults":["accepted","rejected"],
    "rejectionReasonRequired":true
  }
}
```

The workflow was `REVIEW_CONTRACT` with permitted operations
`respond_coordination` and `get_next_action`; it did not provide a
`recommendedTool` or copy-ready `arguments`. That omission is intentional in
the current reducer because the result is an agent-selected ACCEPT/REJECT
decision, not a value Synesis may guess. The projection nevertheless exposed
the exact immutable identifiers, decision field, allowed values, and required
rejection reason.

B did not submit a `review_validation` response. It instead selected an
unprojected `git show` operation to inspect the snapshot. Synesis correctly
returned `workspace_stale` for that command, and the subsequent exact
`ensure_session(refresh=true)` recovery returned `internal_failure /
request_human_help`. No validation decision was recorded.

## Final state and diagnostics

Read-only final status showed:

- WorkGroup `0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda`: `ACTIVE`;
- A: `COMPLETED`, no remaining claim;
- B: `ACTIVE`, claim `test_todo.py` remains;
- A-to-B reciprocal REVIEW request remains `PENDING`;
- A's single-use grant was consumed and A's snapshot was published/integrated;
- no reviewer validation decision and no WorkGroup closure.

The control checkout was clean at `7a96c85`, and `pytest` passed `2 passed`.

Doctor was `DEGRADED` with six warnings: two stale session leases, command
namespace reconciliation, command capacity/retention, and two provider
migration warnings. These remain separately classified; this run does not
show that they caused the review decision boundary.

Raw agent JSONL and MCP stderr/trace logs are retained under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0528-001`.

## Classification and next slice

CP-0528 proves that the CP-0527 REVIEW admission failure is not a persistent
backend defect: the exact projected admission succeeds with separate session
services and live Codex connections. It also proves that grant consumption,
owner acceptance, snapshot publication, integration, and fail-closed grant
argument validation work.

The first incomplete lifecycle boundary is reviewer decision submission. No
unchanged concrete Synesis projection failed. The reviewer ignored the
structured `review_decision` contract and chose an unprojected Git inspection;
the resulting stale-worktree failure is therefore recorded as
agent-compliance/engagement evidence, not as permission to weaken workspace
readiness or add a new tool. The next slice should first audit and, only if
the contract is genuinely ambiguous for ordinary agents, minimally clarify
the agent-facing review-decision guidance before another fresh acceptance.
