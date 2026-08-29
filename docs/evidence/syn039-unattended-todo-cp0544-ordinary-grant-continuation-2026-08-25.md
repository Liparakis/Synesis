# SYN-039 ordinary unattended Todo grant continuation — CP-0543

Date: 2026-08-25
Task: SYN-039 — Autonomous Workgroup Completion
Result: the fresh ordinary run reached one shared WorkGroup, reciprocal REVIEW
admission, grant consumption, immutable test-snapshot publication,
structured ACCEPT, and integration. The implementation snapshot was not
published because the provider sessions ended at valid wait continuations.
No unchanged projected lifecycle action failed and no new production defect
was proven.

## Fixture and preflight

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0544-2026-08-25-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0544-2026-08-25-001`
- Project ID: `292c8905-aca7-4b1e-8f5e-e8494a90e8ce`
- Initial managed baseline: `f290b60`
- Both wrappers used the current bundled
  `synesis-mcp.exe`; both sessions reached `ready / isolated` and the MCP
  traces reached `tools_list_received`.
- Prompts described only visible coding responsibilities: implementation of
  `todo.py` and one regression test in `test_todo.py`.

## Durable coordination state

- WorkGroup: `f82161ed-39f9-3662-851d-d90f07245a46`, final `ACTIVE`, version 1.
- Agent A / implementation: `agt_0e7113c9-dac7-3b7c-970e-15ec463209c8`,
  intent `3f764bb7-ee02-3b8a-8bcf-ebfaaefc0f55`, claim
  `PATH_EXACT:todo.py`, epoch 1, final `ACTIVE`.
- Agent B / test: `agt_031d6339-0f8b-39ff-9ef1-adb019ff3310`, intent
  `b376f614-4c70-3279-acfd-7b240768d116`, claim
  `PATH_EXACT:test_todo.py`, epoch 1, final `COMPLETED`.
- REVIEW requests, both `ACCEPTED`:
    - A → B: `07f0dc30-e297-4a6d-b77c-6b454057349b`
    - B → A: `45c6d0c6-d71a-4ec4-8194-626114ef4d5c`
- REVIEW grants:
    - `964ea299-c1fb-3298-a61b-d448522fb33d`, target A, consumed once after
      one malformed retry.
    - `c7d4b141-32fd-3547-9928-c8cf191cc1b8`, target B, still pending.

## Projection and action trace

1. A and B executed exact REVIEW admission and owner-response projections.
2. A received the exact grant-consumption projection for
   `964ea299-c1fb-3298-a61b-d448522fb33d` but first omitted the projected
   `targetParticipant`. Synesis correctly returned
   `policy_denied / COORDINATION_FIELD_REQUIRED:targetParticipant`. A then
   retried with the unchanged projected arguments and the grant was consumed.
   This is agent compliance evidence; fail-closed authorization worked.
3. A followed recovery and received the explicit review decision contract for
   snapshot `snap_0bd17b0b5256e6a3cc6a5a9c79487085`. Its review pytest command
   returned non-zero against the test-only snapshot because `todo.py` was not
   yet integrated; A submitted the existing structured ACCEPT choice and
   Synesis returned `ACCEPTED`.
4. B received `snapshot_publication_required` with the exact
   `finish_lane({summary:"Publish the completed immutable snapshot"})`
   projection. B selected a different descriptive summary, then published
   and integrated snapshot `snap_0bd17b0b5256e6a3cc6a5a9c79487085` (control
   integration commit `872b689`). The altered summary is informational agent
   compliance evidence, not a safety or protocol failure.
5. B executed the exact reciprocal REVIEW request and then received repeated
   `WAIT -> get_next_action({})` owner-response projections. A accepted that
   response and then received repeated wait projections while grant
   `c7d4b141-32fd-3547-9928-c8cf191cc1b8` remained targeted at B. Both provider
   sessions ended before the reciprocal grant was consumed and the
   implementation snapshot was published.

No unchanged concrete projected tool call failed. The wait projections are
intentional peer-dependent continuations, and the generated agent contract
already instructs agents to remain engaged while grants or WorkGroup state
remain unresolved.

## Final state and verification

- Control checkout `pytest -q`: `1 passed, 2 failed`; only the test snapshot
  integrated, so the implementation remained `NotImplementedError`.
- WorkGroup remained `ACTIVE`; implementation claim remained active and grant
  `c7d4b141-32fd-3547-9928-c8cf191cc1b8` remained pending.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  reconciliation recommended, repair available, no mutations performed.
- Existing Git subprocess stall, bootstrap migration failures, and Doctor
  warnings remain separately classified.

## Classification

This is provider/session engagement and agent-action compliance evidence, not
a new production lifecycle defect. No production code changed, nothing was
pushed, and no SYN-040 was created.

Raw traces:

- `...\harness-ordinary-cp0544-2026-08-25-001\logs\agent-a.jsonl`
- `...\harness-ordinary-cp0544-2026-08-25-001\logs\agent-b.jsonl`
- `...\harness-ordinary-cp0544-2026-08-25-001\logs\mcp-a.trace`
- `...\harness-ordinary-cp0544-2026-08-25-001\logs\mcp-b.trace`
