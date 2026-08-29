# SYN-039 exact-projection diagnostic — CP-0545 review-validation boundary

Date: 2026-08-25

## Scope and harness

This was a fresh disposable Git + Synesis project with two independent GPT-5.6
Luna Codex sessions. Each agent received one complementary visible coding
prompt plus the same single diagnostic rule:

> When Synesis `get_next_action` returns a concrete executable Synesis action,
> execute that exact tool with the exact projected arguments before choosing
> another Synesis lifecycle action.

The harness did not resume either session, pass messages, accept requests,
publish snapshots, validate, integrate, repair claims, or trigger transitions.

Project root:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0548-2026-08-25-001`

Harness:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0548-2026-08-25-001`

Project ID: `fd51d8d9-619b-4238-bcf6-2011a8a9e398`

Seed: `a3c18b4680eae5a9b13eede4bc15f8e17aaf40e0`.
Synesis initialization created managed baseline commit
`cbe0849d9993dc403e42023ea6d0d010d691f98d`. Provider installation reported
`MCP_CONFIG_STATUS=INSTALLED`, `MCP_HEALTH=PASSED`, and
`WORKTREE_BINDING_STATUS=BOUND`.

Both wrappers used:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

with distinct connection IDs:

- `syn039-cp0548-diagnostic-001-agent-a`
- `syn039-cp0548-diagnostic-001-agent-b`

`synesis version` reported `SYNESIS_VERSION=0.1.0-dev.local`,
`RECORD_FORMAT=SDR2`, `RECONCILIATION_PROTOCOL=PRP1`, and
`BUILD_COMMIT=UNKNOWN`. Both MCP traces reached initialization and tools-list
completion.

## Participants and ownership

| Agent              | Participant                                | Session worktree                               | Intent                                 | Claim                     | Epoch |
|--------------------|--------------------------------------------|------------------------------------------------|----------------------------------------|---------------------------|------:|
| A / implementation | `agt_60fb5632-a261-3c08-a944-61e2b1c731f7` | `session-f8503d11-7169-48f6-828e-eb8b466edc84` | `a0953122-26c0-3556-aff7-9282c0d16014` | `PATH_EXACT todo.py`      |     1 |
| B / test           | `agt_46dc7d8b-e9a7-3bd1-b1d7-31727d064e14` | `session-9504e1a0-8954-429c-8d8f-19f9d3344ab3` | `10593860-7070-3ed4-8903-5908b27b1def` | `PATH_EXACT test_todo.py` |     1 |

Both sessions reached `ready / isolated` and converged on WorkGroup
`7dad9076-f0be-3117-9667-b5260ce1ca1e`.

## Projection and action trace

Raw traces: `logs/agent-a.jsonl` and `logs/agent-b.jsonl` under the harness
directory above.

1. A performed visible implementation work through Synesis and ran
   `python -m pytest test_todo.py`, which passed `2/2` in its isolated lane.
2. B performed visible test work and projected the exact REVIEW admission:

   `request_coordination({"kind":"work_group_join","payload":{"intentId":"a0953122-26c0-3556-aff7-9282c0d16014","workGroupId":"7dad9076-f0be-3117-9667-b5260ce1ca1e","proposal":"Review the immutable snapshot for this work group"}})`

   B executed the projection unchanged. Request
   `ff8c05b8-cbe6-41a0-8d1b-d9867723a87e` was created.
3. A received the exact owner response projection:

   `respond_coordination({"kind":"coordination_response","payload":{"coordinationRequest":"ff8c05b8-cbe6-41a0-8d1b-d9867723a87e","coordinationStatus":"ACCEPTED","proposal":"admitted"}})`

   A executed it unchanged and the request became `ACCEPTED`.
4. B received a REVIEW grant projection for grant
   `4fe63ef0-418d-3abc-a442-c768a3b73f6a`, intent
   `a0953122-26c0-3556-aff7-9282c0d16014`, WorkGroup, epoch `1`, and target
   participant A. B first called the same `request_coordination` action without
   projected `targetParticipant`; Synesis returned:

   `blocked / policy_denied / COORDINATION_FIELD_REQUIRED:targetParticipant`

   B then re-read the projection and executed the complete projected payload,
   including `targetParticipant=agt_60fb5632-a261-3c08-a944-61e2b1c731f7`.
   Synesis returned `status=CONSUMED` exactly once.
5. After consumption B received `review_validation` as
   `WAIT -> get_next_action({})` with `snapshotRequired=true`. The immutable
   snapshot did not exist, so no validation decision was executable. B later
   ran `pytest -q test_todo.py` against its current review context and received
   exit `1`; that command was not a projected lifecycle action and did not
   create a validation decision.
6. A's last lifecycle projection remained the valid
   `review_grant_consumption` polling continuation
   `WAIT -> get_next_action({})`. A's provider session ended before the
   producer snapshot-publication projection was observed and executed.

## Lifecycle state reached

- WorkGroup: `ACTIVE`
- REVIEW request `ff8c05b8-cbe6-41a0-8d1b-d9867723a87e`: `ACCEPTED`
- REVIEW grant `4fe63ef0-418d-3abc-a442-c768a3b73f6a`: consumed once by B
- Snapshots: none
- Validation decisions: none
- Integration: none
- Participant A: `ACTIVE`, `todo.py` claim retained
- Participant B: `ACTIVE`, `test_todo.py` claim retained
- WorkGroup terminal state: not reached

The control checkout remained at `cbe0849`. Control pytest reported `1 passed,
1 failed` because `todo.py` was not integrated and the baseline implementation
still raised `NotImplementedError`.

## Classification

This diagnostic is `PARTIAL` evidence and does not prove a production protocol
defect. Exact projected REVIEW admission and owner response succeeded. The
malformed grant call was rejected fail-closed, and the exact retry consumed the
single-use grant. No unchanged projected action failed.

The first blocker is provider/agent engagement: the implementation participant
stopped while a valid polling continuation remained projected, before Synesis
could project snapshot publication. The reviewer also chose a non-projected
pytest action while its review snapshot was absent. These are agent-compliance
observations, not grounds for changing lifecycle semantics.

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

The known Git subprocess stall, bootstrap migration failures, and Doctor
warnings remain separately classified. No production code changed in this
diagnostic.
