# SYN-039 command-scope recovery and acceptance evidence — 2026-08-24

## Result

The fresh ordinary acceptance before this slice proved a concrete MCP defect:
after a valid stale-session recovery moved a participant to a new isolated
worktree, `run_command` remained anchored to the old physical scope and
returned `command_admission_stale / MCP_PROCESS_SCOPE_CHANGED`. The affected
agent had a legitimate dirty `test_todo.py` lane and could not verify or
finish it.

The smallest fix is implemented in
`mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java`.
After a successful `ensure_session`, Synesis re-verifies the current
worktree and clears only the old command-scope anchor when the verified
physical locator changed. The MCP process identity, binding checks, worktree
verification, lease renewal, and fail-closed path remain unchanged.

The deterministic regression is
`McpSyn039SliceTest.recoveredSessionRearmsCommandScopeForItsNewVerifiedWorktree`.
It proves that a command anchored in the original worktree can recover to a
new verified worktree, complete review, and run a normal command without
`MCP_PROCESS_SCOPE_CHANGED`.

## Pre-fix reproduction

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0519-001`
- Project ID: `22afa25d-8f42-4def-be5d-907bc7664d1b`
- WorkGroup: `7fa524f9-ce4e-3609-b988-567901c6363d`, `ACTIVE`
- Original reviewer: `agt_f5b27156-e9f3-3810-af62-310724aa05e5`
- Original intent: `823a00ed-8184-3470-9fdb-bfc95975538d`, epoch 1,
  `PATH_EXACT test_todo.py`
- Original worktree: `...\worktrees\session-078eef62-a6b7-4a12-9276-e85f4afc74d4`
- Recovery worktree: `...\worktrees\session-078eef62-a6b7-4a12-9276-e85f4afc74d4-recovery-8c0b0fcd-f2ac-4003-bdac-5d228d5c730a`
- A's integrated snapshot: `snap_3046c8fbf48f0556aa467a3b11433851`,
  commit `b355adbbba6682d04506b3aaa0d97337919f7400`
- Evidence logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0519-001\logs`

B first used a normal command in the original worktree, establishing the
`ProjectCommandProcessAnchor`. After A integrated, B recovered to the new
worktree, applied its legitimate test patch, and then ran:

```text
python -m pytest -q test_todo.py
git diff -- test_todo.py todo.py
```

Both returned:

```json
{"status":"blocked","reason":"command_admission_stale",
 "nextAction":"request_human_help",
 "result":{"error":"MCP_PROCESS_SCOPE_CHANGED"}}
```

The old anchor was retained across the successful worktree rebind. This was
the first concrete product failure in that run; later recovery and engagement
deviations were not used to justify broader changes.

## Narrow implementation and regression

The handler now compares the existing anchor's physical locator with a fresh
`PhysicalWorktreeIdentity` captured after successful `ensure_session`. It
clears the anchor only when the new scope is independently verified and is
different. If post-resolution verification fails, the old fail-closed command
admission behavior remains in force.

Focused regression result:

```text
:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest.recoveredSessionRearmsCommandScopeForItsNewVerifiedWorktree
BUILD SUCCESSFUL
```

Full SYN-039 MCP slice:

```text
:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest
BUILD SUCCESSFUL
```

## Post-fix exact-action diagnostic

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0519-002`
- Harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0519-002`
- Project ID: `27571d2c-8603-4922-b3c6-7888f126b2b9`
- Seed commit: `040559a05423f2da762455bade2c9c970d961703`
- Current bundled MCP:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`
- MCP SHA-256: `81B64BB6C12006C19F335AD4F850B14196BF14A113BB28DBE81D7BA5164D9864`
- Both wrappers used the same initialized project and retained independent
  Codex sessions. MCP startup traces contain repeated initialize and
  `tools_list_received` events for both connections.

Participants and claims:

| Agent | Participant | Intent / epoch | Claim |
|---|---|---|---|
| A | `agt_6cad368b-7e75-371e-b820-a6cd67065105` | `f9534cb1-8d03-310a-aaa8-2be52503bb67` / 1 | `PATH_EXACT todo.py` |
| B | `agt_b30a7488-0382-3663-b5b5-2c98bebef49f` | `8b210b5e-f0ff-3c6a-95ff-a709df512dc3` / 1 | `PATH_EXACT test_todo.py` |

Shared WorkGroup: `89fea014-9f5b-326b-8521-5d2218cc55fc`, terminal
`COMPLETED`, version 2.

The diagnostic reached and exercised:

1. Independent claim-bearing session establishment and one shared WorkGroup.
2. Exact projected REVIEW admission and owner response.
3. Single-use grants `1e74b37f-3e74-348c-936b-f98368d4431d` and
   `ebe6c8a2-7b71-3c59-9ba2-d434b9e012f8`, both consumed with epoch 1 and the
   correct target participant.
4. Immutable snapshots:
   - `snap_e06aa78a05912ffa11153bbba8d6045d`, `test_todo.py`, snapshot commit
     `9e7a2452c979e554a696d7d64b17598c2ee6f391`; structured REJECT was
     returned with an actionable route.
   - `snap_afc55fe525ce695509ece91089617f2a`, `todo.py`, snapshot commit
     `ccea346a366167f1083ed388f5a1b74610795348`; reviewer read and pytest
     validation completed, then structured ACCEPT was returned.
5. Control checkout integration commits `b673c1d` for `test_todo.py` and
   `932c21a` for `todo.py`; final control checkout was clean and `pytest`
   passed `3/3`.
6. No manual relay, request acceptance, grant consumption, snapshot
   publication, validation, or integration was performed by the harness.

The raw JSONL traces are retained under the harness path above. They show
each `get_next_action` projection and the following agent action. The agents
made several malformed or reordered attempts before later executing the
unchanged projected action; those fail-closed responses are agent-compliance
evidence, not additional production defects.

## Second ordinary acceptance

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0519-002`
- Project ID: `fc4bc78f-c9af-454a-ba80-69ec664befc3`
- WorkGroup: `dfc93a1a-de2e-3db4-859e-c0eb7d60eaab`, terminal state `ACTIVE`
- Original participants:
  - `agt_0b734871-3a84-3ade-a845-dc803b9773a5`, intent
    `853af37c-1a19-3959-9a05-18a977ed96c5`, `todo.py`, completed
  - `agt_025c7ddc-dd2e-3e40-b920-13b8c4044bb1`, intent
    `499f29de-8a62-36b9-be34-a77963fa7163`, `test_todo.py`, completed
- Retained-session continuation participant:
  `agt_dc780e6e-7cc6-3a4e-9a59-a28ff00984f5`, intent
  `6cbd2db3-d95c-3bfc-b703-051188571f26`, `todo.py`, still `ACTIVE`
- Integrated snapshots included `todo.py` and `test_todo.py`; control
  `pytest` passed `3/3` and the control checkout was clean after removing a
  pycache artifact created by the post-run inspection.

The ordinary run reached shared WorkGroup formation, reciprocal review
requests, grant consumption, snapshot publication, integration, and review
validation. It did not close because the retained harness resumed A after its
original lane had completed. A created a new overlapping continuation lane,
ignored one projected request argument (`INTENT_NOT_FOUND`), and later exact
`ensure_session({})` recovery for that contaminated continuation returned
`internal_failure`. B remained in projected `WAIT` while the extra A lane
remained active. This is agent/session engagement evidence, not a clean
ordinary product pass and not a basis for another production change.

## Diagnostics and remaining classification

Final fixture Doctor for both fresh projects was `DEGRADED` with six warnings,
zero errors, and zero critical findings:

- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

These warnings did not prevent the post-fix diagnostic from reaching terminal
WorkGroup completion. The root Git subprocess startup stall, bootstrap
migration test failures, and documentation format findings remain separate
verification issues.

## Next action

Preserve the ordinary-run session-continuation evidence and run the next fresh
ordinary acceptance with the retained-session harness corrected only so it
does not create a new coding lane after a completed lane. Do not add lifecycle
machinery or change production behavior unless a clean run executes an
unchanged projection and it fails, or Synesis projects no usable action for a
valid active lane.
