# SYN-039 unattended Todo CP-0485 exact-rule diagnostic

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Scope: bounded diagnostic only; no production change from this run

## Purpose and controls

This run tested the actual two-session Synesis product path with a fresh Git +
Synesis project and two independent GPT-5.6 Luna High Codex agents. The harness
and logs were kept outside the project. The control checkout was clean before
launch. No information was relayed between agents, and no coordination
transition, claim, grant, snapshot, validation, or integration action was
manually triggered.

Both agents received one protocol-conformance rule:

> When `get_next_action` returns a concrete executable Synesis action, execute
> that exact tool with the exact projected arguments before choosing another
> Synesis lifecycle action. When it returns ordinary `IMPLEMENT` with no
> executable action, perform visible repository work normally and do not invent
> a lifecycle action.

The diagnostic stopped at the first exact projected action failure. A second
ordinary unattended acceptance was not run because this diagnostic did not
complete end to end.

## Harness and preflight

- Project fixture:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0485-001`
- Harness directory:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0485-001`
- Initial fixture file: `todo.py`
- Clean project baseline before agent launch: commit `5286a30`
- Current bundled MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`
- Bundle SHA-256:
  `27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`
- Provider setup was committed before launch. Control status was clean.
- Both agents reached the same initialized project with exactly ten MCP tools,
  distinct session identities, and `ready / isolated` workspaces.

Agent A implemented `todo.py`; Agent B owned focused `test_todo.py` tests.

| Agent | Participant | Intent | Claim | Session/worktree |
|---|---|---|---|---|
| A | `agt_752d0d98-f3bb-3d2e-ba6a-2a992a7fc7b9` | `3aa83a5f-3b5f-3b69-87fa-a0fb890797bb` | `todo.py` | `session-252238f9-65e0-48b2-8c4f-2a3848c78885` |
| B | `agt_955f964a-202c-3a48-a252-8ba9dffeea89` | `31c298f7-2613-3d12-9021-6d3c480228f4` | `test_todo.py` | `session-503aa59e-26c1-4f83-8517-09d67febe987` |

## Projection/action evidence

The relevant sequence was captured as projection → executable arguments →
agent action → result.

| Stage | Projection and exact arguments | Actual action/result |
|---|---|---|
| Initial A | `IMPLEMENT`; no executable Synesis tool | A performed visible implementation work. |
| B admission | `REVIEW_ADMISSION_REQUIRED`; `request_coordination` with `{"kind":"work_group_join","payload":{"intentId":"3aa83a5f-3b5f-3b69-87fa-a0fb890797bb","workGroupId":"a5b6fdc4-51cb-3398-be5a-76126258984f","proposal":"Review the immutable snapshot for this work group"}}` | B executed the exact projected action. Request `4a2d5e88-22b4-40d6-95b3-2053472487b0` was created PENDING, targeted to owner A. |
| B repeated admission | Same `REVIEW_ADMISSION_REQUIRED` projection and exact arguments | B repeated the projected action. Request `e4617626-b3b8-4772-99d1-57b3b7ffea03` was created PENDING. This duplicate is retained as agent behavior evidence; it is not treated as a new production defect. |
| A response 1 | `owner_request_pending`; `respond_coordination` with `{"kind":"coordination_response","payload":{"coordinationRequest":"4a2d5e88-22b4-40d6-95b3-2053472487b0","coordinationStatus":"ACCEPTED","proposal":"admitted"}}` | A executed the exact projected action and received ACCEPTED. |
| A response 2 | `owner_request_pending`; same action shape with request `e4617626-b3b8-4772-99d1-57b3b7ffea03` | A executed the exact projected action and received ACCEPTED. |
| Post-admission A | Ordinary `IMPLEMENT`; no executable lifecycle tool or arguments | A selected unprojected `finish_lane`. This violated the diagnostic rule. It is agent-compliance evidence, not a production projection failure. The call happened before REVIEW grant consumption and before snapshot-publication projection. |
| Reviewer recovery | `retry_required`, reason `workspace_stale`, next action `ensure_session`, exact arguments `{}` | B executed `ensure_session({})` exactly. Result: `failed`, reason `internal_failure`, next action `request_human_help`. B repeated the same exact projected recovery once; it failed identically. |

The owner’s unprojected `finish_lane` produced a published snapshot and
integrated control state in this diagnostic, but that result is not accepted
as proof of the target protocol path because the action was not projected.

## Coordination and lifecycle state

- WorkGroup:
  `a5b6fdc4-51cb-3398-be5a-76126258984f`
- Project sequence: `0`
- Tasks: `0`
- Ownerships in the control status projection: `0`
- Requests accepted: `4a2d5e88-22b4-40d6-95b3-2053472487b0`,
  `e4617626-b3b8-4772-99d1-57b3b7ffea03`
- Grants: `ce12bf95-e493-38c7-a75b-fc78f5b03782` and
  `7b4f4964-8631-3b80-bb99-0552b05c67d7`, both targeted to B at epoch 1
- Snapshot/validation: no reviewer grant consumption or validation decision
  was recorded
- WorkGroup terminal state: `ACTIVE`
- Agent A state: `COMPLETED` in the final collaboration projection
- Agent B state: `ACTIVE`, stopped after the repeated recovery failure
- Final control checkout: clean, HEAD
  `166228f5a6b17208175231984f7cbce9e4090dfc`

## Doctor and classification

Final Doctor state was `DEGRADED` with six warnings: two
`stale_session_lease` warnings, `command_namespace_reconciliation_required`,
`command_capacity_or_retention`, and two `provider_migration_required`
warnings. Reconciliation was recommended and repair was available.

The first genuine protocol failure in this valid clean-harness run is the
reviewer’s exact projected `workspace_stale` recovery action returning
`internal_failure`. The existing stale lease warning is directly relevant,
but the lease, heartbeat, process-anchor, binding, and provider-process cause
has not been established. The next slice is therefore a deterministic trace
of the live reviewer session through lease renewal and `ensure_session`, not a
review, snapshot, validation, integration, cleanup, or Doctor redesign.

The CP-0484 run is preserved separately as harness-contamination evidence: its
control checkout contained launch scripts, logs, and an uncommitted provider
configuration, so its `integration_failed` result was not a valid product
failure. The known root Git subprocess stall and bootstrap migration failures
remain independent verification issues.
