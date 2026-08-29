# SYN-039 CP-0528 — diagnostic and ordinary Todo acceptance

Date: 2026-08-25

## Scope and conclusion

This slice made no production-code changes. It ran the required fresh bounded
diagnostic with the exact projected-action rule, then the required second fresh
ordinary acceptance with only the two coding prompts.

The bounded diagnostic completed the existing protocol end to end. Exact
projected REVIEW admission, owner responses, single-use grant consumption,
snapshot publication, immutable review, structured ACCEPT decisions,
integration, and terminal WorkGroup completion all succeeded. No unchanged
projected lifecycle action failed.

The ordinary acceptance reached the same protocol through implementation
integration and one accepted review, but an agent session ended while the
reciprocal REVIEW grant targeted at that ended implementer remained pending.
The WorkGroup stayed ACTIVE. This is agent/session engagement evidence, not a
proven Synesis protocol defect; no production change is justified by this run.

## Harness and MCP identity

Both runs used fresh disposable Git + Synesis projects, two independent
GPT-5.6 Luna High Codex sessions, and the current bundled executable:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

The startup records identify the MCP distribution as version `0.1.0-SNAPSHOT`,
commit `bc334ac`, provider `codex`, with the project root and per-agent
connection instance in each process record. The current executable SHA-256 is
`776B1AA22D4EEBE566941FCCDB0F15F544555BAC8C62DB4F1128BAC03A0D9359`.
Both MCP traces completed initialize and `tools/list`; the repository contract
surface is the current ten-tool distribution.

Raw diagnostic harness:

`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0528-004`

Raw ordinary harness:

`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0528-005`

## Bounded diagnostic

Project `52ffa2e5-8752-489c-a6cb-270c32730af2`:

- WorkGroup: `f0c02558-ab10-3bf6-b369-1d21011ffe64`, final `COMPLETED`, version 2.
- Implementation participant: `agt_3d97d51a-adc3-37d9-ab74-25917b6b7833`, intent `71497258-4dd9-391b-9021-922764f2afc1`
  for `todo.py`, claim epoch 1.
- Test participant: `agt_47ec0210-187d-3662-9c16-ebf0bdd40243`, intent `92c54bc3-e9c0-3382-b450-82d040c83d5e` for
  `test_todo.py`, claim epoch 1.
- Requests: `5105381b-0305-4f89-84d1-0de161d10c3f` and `927bf92b-9163-4d20-ba20-f2d4c4da7982`; both ended `ACCEPTED`,
  kind `REVIEW`.
- Grants: `217e38ac-97be-3114-a5c2-ca15f69a4775` targeted the implementation participant for the test snapshot;
  `f3bc540e-2724-3f15-809a-a29c6278f872` targeted the test participant for the implementation snapshot. Both were
  consumed at epoch 1 and remained single-use.

The recorded projection/action sequence was:

| Projection                                                                                             | Exact following action                                                                                                                                                       | Result                                                                                                                                                              |
|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `REVIEW_ADMISSION_REQUIRED` → `request_coordination` for intent `92c54bc3-e9c0-3382-b450-82d040c83d5e` | `request_coordination({kind: work_group_join, payload: {proposal, workGroupId, intentId}})`                                                                                  | Request `5105381b-0305-4f89-84d1-0de161d10c3f` created                                                                                                              |
| owner `respond_coordination` for request `5105381b-0305-4f89-84d1-0de161d10c3f`                        | Exact projected `coordination_response` with `ACCEPTED` / `admitted`                                                                                                         | Accepted; grant `217e38ac-97be-3114-a5c2-ca15f69a4775` issued                                                                                                       |
| grant available for the implementation participant                                                     | Exact `request_coordination` containing grant `217e38ac-97be-3114-a5c2-ca15f69a4775`, intent, participant, WorkGroup, and epoch                                              | Consumed successfully                                                                                                                                               |
| `snapshot_publication_required` → `finish_lane`                                                        | `finish_lane({summary: "Publish the completed immutable snapshot"})`                                                                                                         | Test snapshot `snap_700deead5bd8470148503846942f834f` published and integrated; commit `cfac4cdb0e080dcaff155ebdeb4cf35fc6235a60`; changed path `test_todo.py`      |
| reciprocal `REVIEW_ADMISSION_REQUIRED` for intent `71497258-4dd9-391b-9021-922764f2afc1`               | Exact `request_coordination`                                                                                                                                                 | Request `927bf92b-9163-4d20-ba20-f2d4c4da7982` created                                                                                                              |
| owner `respond_coordination` for request `927bf92b-9163-4d20-ba20-f2d4c4da7982`                        | Exact `coordination_response` with `ACCEPTED` / `admitted`                                                                                                                   | Accepted; grant `f3bc540e-2724-3f15-809a-a29c6278f872` issued                                                                                                       |
| reciprocal grant available                                                                             | Exact grant-bearing `request_coordination`                                                                                                                                   | Consumed successfully                                                                                                                                               |
| implementation `snapshot_publication_required` → `finish_lane`                                         | `finish_lane({summary: "Publish the completed immutable snapshot"})`                                                                                                         | Implementation snapshot `snap_2647c5f6f676c3d09ae64067d9710ad7` published and integrated; commit `80cb504fd4b054380925db7ba4ccc0036b82f2c1`; changed path `todo.py` |
| reviewer `review_decision` for `snap_2647c5f6f676c3d09ae64067d9710ad7`                                 | `respond_coordination(review_validation, grant=f3bc540e-2724-3f15-809a-a29c6278f872, intent=71497258-4dd9-391b-9021-922764f2afc1, epoch=1, snapshotId=..., result=accepted)` | Structured ACCEPT succeeded                                                                                                                                         |
| reviewer `review_decision` for `snap_700deead5bd8470148503846942f834f`                                 | `respond_coordination(review_validation, grant=217e38ac-97be-3114-a5c2-ca15f69a4775, intent=92c54bc3-e9c0-3382-b450-82d040c83d5e, epoch=1, snapshotId=..., result=accepted)` | Structured ACCEPT succeeded                                                                                                                                         |

The control checkout was clean and `python -m pytest -q test_todo.py` passed
`4 passed`. Final coordination status was `PASS`, sequence `0`, with no
tasks or ownerships. Final Doctor was `DEGRADED` with 6 warnings, 0 errors,
0 critical findings, `CLEANUP_RECOMMENDED=false`, and
`RECONCILIATION_RECOMMENDED=true`.

After terminal completion, one agent made a post-terminal read/recovery
selection and received fail-closed `coordination_intent_required`; that was
outside the required completed lifecycle and is not counted as a projected
action failure.

## Ordinary acceptance

Project `610ccc81-8e8b-4021-bebb-2dfb57b116e7`:

- WorkGroup: `b319999f-7060-360b-a26b-0a0891e23be1`, final `ACTIVE`, version 1.
- Implementation participant: `agt_efe89920-1571-33c3-9c24-19509fe76d00`, intent `322e3e72-05e5-3acc-aec1-6a10e911c91e`
  for `todo.py`, claim epoch 1.
- Test participant: `agt_8e603148-e581-3a22-a909-a0d75ffa95b4`, intent `6fe97771-ab72-3e02-b55f-a34293df4424` for
  `test_todo.py`, claim epoch 1.
- Requests `a08820ad-036e-466a-8ef7-12c888f02f7f` and `6b240edc-8c31-4328-a4e3-ab4eb55ff140` ended `ACCEPTED`, kind
  `REVIEW`.
- Grant `a9c7fe68-7c6e-3541-a395-50878d629fe4` targeted the test participant and was consumed to review the
  implementation snapshot.
- Reciprocal grant `abacded1-ee9c-354f-9271-dabcd00bffa5` targeted the implementation participant, epoch 1, single-use,
  and remained unresolved when that participant's Codex turn ended.
- Implementation snapshot `snap_3596291c5018036277f7a780da877dea` was published and integrated from commit
  `77882a9e9b96ccc003cac2edf97b1a3b293fd10b`, changing only `todo.py`.
- The reviewer submitted structured ACCEPT for that snapshot with the exact grant, intent, epoch, snapshot ID, and
  reason; `pytest test_todo.py` passed 3 tests in the implementation snapshot.
- The implementation participant then projected and executed the reciprocal REVIEW request, but its turn ended while the
  resulting grant was pending. The reviewer correctly remained in exact projected `WAIT → get_next_action({})`.

The control checkout contains the integrated `todo.py` implementation but not
the test-lane snapshot; control pytest is `3 passed`, and the working tree is
clean. Final coordination status was `PASS`, sequence `0`, with no tasks or
ownerships. Final Doctor was `DEGRADED` with the same 6 warnings, 0 errors,
and 0 critical findings.

This is a provider/session engagement boundary and ordinary-agent completion
evidence, not a reason to weaken grant fencing, auto-consume grants, or add an
orchestrator. No exact projected lifecycle action returned an error.

## Independent verification classification

- No production files changed in CP-0528.
- Current focused SYN-039 tests and existing validators remain the relevant
  repository checks; the diagnostic/ordinary control tests passed as recorded
  above.
- The recurring root Git subprocess startup stall, bootstrap migration test
  failures, and Doctor warnings remain separately classified. Nothing in these
  two runs connected them causally to the acceptance boundary.
- Nothing was pushed and no SYN-040 was created.

## Raw evidence

- Diagnostic agent traces:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0528-004\logs\agent-a.jsonl` and `agent-b.jsonl`.
- Ordinary agent traces: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0528-005\logs\agent-a.jsonl`
  and `agent-b.jsonl`.
- Diagnostic MCP startup traces: `...\harness-diagnostic-cp0528-004\logs\mcp-a.stderr.log` and `mcp-b.stderr.log`.
- Ordinary MCP startup traces: `...\harness-ordinary-cp0528-005\logs\mcp-a.stderr.log` and `mcp-b.stderr.log`.
