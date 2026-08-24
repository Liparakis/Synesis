# SYN-039 CP-0522 exact-action diagnostic

## Result and classification

This fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
sessions, the current bundled MCP, a new Git + Synesis project, and the exact
projection rule. Agent A completed the visible `todo.py` implementation and
passed its visible test. Agent B did not reach repository work because it
changed a projected REVIEW-admission identifier.

The first projected action was:

```json
{
  "kind":"work_group_join",
  "payload":{
    "intentId":"131672ac-5f3e-302a-b9aa-5ebf12bf1dc7",
    "workGroupId":"8672f63b-e528-3b23-9fdc-5d4c0ac09841",
    "proposal":"Review the immutable snapshot for this work group"
  }
}
```

The actual call changed `intentId` to
`131672ac-5f3e-302d-966d-2cc32c82193d`. Synesis correctly returned
`policy_denied` with `INTENT_NOT_FOUND`. `get_next_action` re-projected the
original valid arguments; B repeated the altered identifier and received the
same fail-closed error. Therefore no exact projected action failed. This is
agent-compliance evidence, not a production defect, and no production code
changed.

## Fresh project and harness

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0522-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0522-001`
- Project ID: `5f89f37c-2313-48ff-a9a2-0339194b93fd`
- Seed commit: `86df796`
- Synesis-managed baseline: `4e8a430`
- MCP executable: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `D7B8C0E533674C2BDA891FC60F2B013923A731838C1F0601EBC4E5AD3F11360C`
- MCP startup version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Agent A connection: `conn-instance-e525b47b-eb00-4c49-9efe-fb806688e1cd`
- Agent B connection: `conn-instance-00d420e0-bd0b-4b80-9ecf-fdb5258d59ea`

## Participants and claims

| Agent | Participant / intent | Claim and session worktree | Final state |
|---|---|---|---|
| A | `agt_5c7a3739-9757-302d-966d-2cc32c82193d` / `131672ac-5f3e-302a-b9aa-5ebf12bf1dc7` | epoch 1, `PATH_EXACT todo.py`, `session-891505da-586b-44ac-803a-d517c004642b` | ACTIVE |
| B | `agt_28f6dfec-2ecd-310f-90a4-0cec04a8e54e` / `9b942ac3-16c3-3b47-871a-483ad9167d63` | epoch 1, `PATH_EXACT test_todo.py`, `session-1795466e-001b-48a5-b157-5adb54aa2ff8` | ACTIVE |

WorkGroup: `8672f63b-e528-3b23-9fdc-5d4c0ac09841`, `ACTIVE`. No grant,
snapshot, validation, integration, or closure state was created.

## Exact trace

- B `get_next_action` projected `request_coordination` with the valid intent
  above; B's following call used the altered intent and returned
  `INTENT_NOT_FOUND`.
- B polled again; Synesis re-projected the unchanged valid request.
- B repeated the altered call; it returned the same fail-closed error.
- B stopped without modifying `test_todo.py`.
- A performed visible implementation work and `python -m pytest test_todo.py`
  passed (`1 passed`), then remained in ordinary IMPLEMENT polling because no
  executable lifecycle action was projected for A.

Raw JSONL preserves every projection and following action under
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0522-001\logs`.

## Final diagnostics

Doctor is `DEGRADED` with six warnings, zero errors, and zero critical
findings: two stale session leases, command namespace reconciliation, command
capacity or retention, and two provider migration warnings. The startup
traces confirm both agents used the current bundled MCP and the correct
project root; the warnings are not causal to this argument mismatch.

## Next action

Do not modify lifecycle production code for CP-0522. The next diagnostic must
preserve the exact projected argument bytes or structured values in the agent
call and continue only when the call is unchanged. Once exact admission is
executed successfully, continue until the first later projected failure or
missing usable action. Keep the CP-0521 reciprocal WAIT/early-stop result,
Git subprocess stall, bootstrap migration failures, and Doctor warnings
separately classified.

