# SYN-039 exact-rule diagnostic — CP-0519

## Result and classification

This fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
processes, the current bundled Synesis MCP, a fresh Git project, and only
complementary visible Todo coding responsibilities. Both agents reached
ready/isolated sessions, established disjoint claims, and converged on one
WorkGroup without relay or manual lifecycle intervention.

The diagnostic executed the projected REVIEW admission, owner acceptance,
single-use grant consumption, producer publication, immutable snapshot
integration, and structured ACCEPT exactly. It then stopped at the first
genuine projected-action failure: after B accepted A's snapshot, B's
`get_next_action` projected `ensure_session({})` for `workspace_stale`, but
the exact call returned `internal_failure / request_human_help`.

This is a concrete stale-dirty continuation defect, not an ignored projection.
B still owned legitimate uncommitted `test_todo.py` work. The control checkout
had advanced because A's snapshot integrated, so the existing fail-closed dirty
workspace guard correctly refused replacement, but the protocol exposed no
usable path for B to continue its own lane. No production code changed during
this diagnostic.

The conditional second ordinary acceptance was not run because the diagnostic
did not complete end-to-end.

## Fresh project and harness

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0519-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0519-001`
- Project ID: `00eecdcd-865a-4071-8df4-afc810839519`
- Initial seed commit: `dc6f240`
- Control checkout after A integration: `2563b0c`
- Control checkout status: clean
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `6E80EBD9586806B6E5B48078947220483566F1176D82A126A2DA422F62B670C8`
- CLI version: `0.1.0-dev.local`
- MCP startup evidence: version `0.1.0-SNAPSHOT`, commit `bc334ac`
- Provider: `codex`; both launchers passed the same project root and current
  bundled MCP wrapper, with `gpt-5.6-luna` and `model_reasoning_effort=high`.
- Both agent prompts contained the exact-projection rule and no manually
  supplied Synesis IDs or lifecycle transitions.

## Participants, claims, and WorkGroup

| Agent | Session worktree | Participant | Intent / epoch / claim |
|---|---|---|---|
| A | `...\\worktrees\\session-8eaea2d6-d593-41c3-ae64-1fb525caa03b` | `agt_211a8de1-ba38-3f24-bf2f-d20bc031cc34` | `e3e82daa-c076-36c0-b008-16c0973c5c9c`, epoch 1, `PATH_EXACT todo.py` |
| B | `...\\worktrees\\session-a1ead3db-6f49-4fd3-926c-e024bcbb8270` | `agt_8c918cd1-7c1c-3e77-9d0a-a527e1ffa677` | `48db986d-d194-3ce8-8ab1-496ef7bbdb12`, epoch 1, `PATH_EXACT test_todo.py` |

Shared WorkGroup: `663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf`, terminal state
`ACTIVE`.

## Exact lifecycle trace

1. Both sessions independently recovered, called claim-bearing
   `ensure_session`, and reached `ready / isolated` with the same project and
   disjoint visible claims.
2. A received ordinary `IMPLEMENT`, modified only `todo.py`, and ran
   `python -m pytest -q`: `2 passed`.
3. B received the exact REVIEW admission projection and executed:

   ```json
   {
     "kind":"work_group_join",
     "payload":{
       "intentId":"e3e82daa-c076-36c0-b008-16c0973c5c9c",
       "proposal":"Review the immutable snapshot for this work group",
       "workGroupId":"663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf"
     }
   }
   ```

   Request `d001fa9b-efb3-431e-aca5-b0559513291e` was created as pending,
   targeted at A.
4. A's `get_next_action` projected and A executed the exact owner response:

   ```json
   {"kind":"coordination_response","payload":{
     "coordinationRequest":"d001fa9b-efb3-431e-aca5-b0559513291e",
     "coordinationStatus":"ACCEPTED","proposal":"admitted"}}
   ```

5. B consumed the exact single-use REVIEW grant:
   `fed1c3f6-f8e0-3d73-bce9-9fe9f03439cb`, intent A, WorkGroup above,
   `claimEpoch=1`, target participant B. The result was `CONSUMED`.
6. A's `get_next_action` projected `snapshot_publication_required` with
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   A executed it successfully. The result was:

   - snapshot: `snap_48423ea02f57776f0064595b971197ab`
   - snapshot state: `PUBLISHED`
   - lane: `e3e82daa-c076-36c0-b008-16c0973c5c9c`
   - claim epoch: `1`
   - integration state: `integrated`
   - integrated commit: `2563b0cbfaf593ccaf2a395b64b003692f320411`

7. B received the structured review decision projection and executed:

   ```json
   {"kind":"review_validation","payload":{
     "grantId":"fed1c3f6-f8e0-3d73-bce9-9fe9f03439cb",
     "intentId":"e3e82daa-c076-36c0-b008-16c0973c5c9c",
     "claimEpoch":1,
     "snapshotId":"snap_48423ea02f57776f0064595b971197ab",
     "result":"accepted"}}
   ```

   Synesis returned `ACCEPTED` with WorkGroup status `ACTIVE`.
8. A then projected reciprocal REVIEW admission for B's still-active intent
   and executed the exact request. Request
   `a705dde9-eab2-40ec-bd4a-b30fb45a9122` was pending when A's Codex turn
   ended. This is later agent-engagement evidence, not the first production
   failure in this run.
9. After B's ACCEPT, B's next `get_next_action` projected:

   ```json
   {
     "status":"retry_required",
     "reason":"workspace_stale",
     "nextAction":"ensure_session",
     "workflow":{
       "type":"RECOVER",
       "blockers":["workspace_stale"],
       "permittedOperations":["ensure_session"],
       "recommendedTool":"ensure_session",
       "arguments":{}
     }
   }
   ```

   B executed that exact projected `ensure_session({})`; Synesis returned:

   ```json
   {"status":"failed","reason":"internal_failure",
    "nextAction":"request_human_help"}
   ```

   The same projection and failure repeated. B's earlier unprojected
   `ensure_session({"refresh":true})` after workspace-stale read/command
   failures is retained as agent-compliance evidence; it does not change the
   first exact projected-action classification.

## State at the first blocker

- WorkGroup: `663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf`, `ACTIVE`
- A: implementation intent completed and claim released after integration
- B: active intent `48db986d-d194-3ce8-8ab1-496ef7bbdb12`, epoch 1,
  `PATH_EXACT test_todo.py`
- Request B → A: `d001fa9b-efb3-431e-aca5-b0559513291e`, accepted
- Request A → B: `a705dde9-eab2-40ec-bd4a-b30fb45a9122`, pending
- REVIEW grant: `fed1c3f6-f8e0-3d73-bce9-9fe9f03439cb`, consumed once
- Published snapshot: `snap_48423ea02f57776f0064595b971197ab`, A's `todo.py`
- B snapshot: none; B's assigned worktree retains modified `test_todo.py`
  and generated `__pycache__/`
- Validation: A snapshot received structured `ACCEPTED`
- Integration: A snapshot integrated; control checkout is clean at `2563b0c`
- WorkGroup closure: not reached

Worktree observations after both Codex processes ended:

- control checkout: clean, `2563b0c`
- A worktree: clean at its managed baseline
- B worktree: `M test_todo.py`, `?? __pycache__/`, still based on the old
  control commit

## Diagnostics and verification

Fixture `synesis doctor --project ... --verbose`:

- `DEGRADED`, 6 warnings, 0 errors, 0 critical findings
- two `stale_session_lease`
- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required`

These warnings did not prevent ready/isolated sessions, WorkGroup formation,
review admission, grant consumption, snapshot publication, validation, or one
integration. They remain separately classified.

Focused verification after the run:

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`: PASS
- `:workspace:test --tests org.synesis.workspace.ProviderSessionBindingServiceTest`: PASS
- `scripts/agent-validate-deferred.ps1`: PASS
- `scripts/agent-validate-fixtures.ps1`: PASS
- `git diff --check`: PASS

Known independent verification issues remain unchanged: the root Git
subprocess startup stall, the three bootstrap migration test failures, and
pre-existing `:link:formatCheck` trailing-whitespace findings.

## Smallest next implementation slice

Trace the post-ACCEPT path for a bound participant whose own worktree is dirty
and whose control base advanced. Make `get_next_action` expose an existing
authorized continuation for that participant's own lane—publication when its
dirty work is publishable, or the already-authorized review decision when
appropriate—instead of projecting stale-workspace recovery that cannot be
executed without discarding work. Preserve the existing claim, participant,
epoch, grant, snapshot, ownership, and fail-closed workspace checks. Add a
deterministic regression for the exact `workspace_stale -> ensure_session({})
-> internal_failure` transition before changing the unattended harness.

Raw Codex JSONL, MCP startup logs, wrappers, and prompts are retained under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0519-001`.
