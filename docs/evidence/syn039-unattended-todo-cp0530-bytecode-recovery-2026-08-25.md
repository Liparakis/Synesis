# SYN-039 CP-0530 — pytest artifact recovery fix and post-fix diagnostic

## Result

The fresh CP-0530 baseline proved a narrow production defect at the existing
`workspace_stale -> ensure_session` continuation boundary. A normal
`python -m pytest -q test_todo.py` run left only Python bytecode under
`__pycache__/`. `get_next_action` projected the exact recovery action
`ensure_session({})`, but `ProviderSessionBindingService` classified that
known runtime artifact as user work and the action returned
`failed / internal_failure / request_human_help`.

`SnapshotArtifactPolicy` already classified `__pycache__/` as an allowed
runtime artifact and snapshot publication already omitted it. The stale
workspace classifier was inconsistent with that existing product policy.

The smallest fix adds the same narrow `__pycache__/` classification to stale
workspace cleanliness and confirmed-user-change checks. Unknown untracked
content remains fail-closed.

## Baseline reproduction before the fix

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0530-001`
- Project ID: `3811f56c-9c88-400f-b66c-d20f8b2fe3b0`
- WorkGroup: `ae8db07d-3abb-345b-a63a-d21a3170f230`
- Agent A participant/intent/claim: `agt_e069225a-e29c-3fe3-8dce-03e255f2ded6` /
  `1853637e-0c7b-376c-9c5c-74e77f6c453c` / `PATH_EXACT todo.py`, epoch 1
- Agent B participant/intent/claim: `agt_9ba38c2d-2ae5-320a-913e-46ea86dd38b7` /
  `49a7eb69-418a-33d0-8803-85482741618f` / `PATH_EXACT test_todo.py`, epoch 1
- B's review grant: `c09ecdf8-a5de-34d8-8e81-f4b4e8a48446`
- A's implementation snapshot: `snap_a9fb88c1ac38b9b787e7a6e1b4517a9d`,
  commit `f88326e9faef7c481b000c03d5c4e0930babe5b5`
- B's reciprocal grant: `03901d6c-cd94-35c2-bf94-5d38250b9df5`
- B worktree status immediately before recovery:

  ```text
  ?? __pycache__/test_todo.cpython-313-pytest-9.1.1.pyc
  ?? __pycache__/todo.cpython-313.pyc
  ```

- Exact projection:

  ```json
  {"status":"retry_required","reason":"workspace_stale",
   "nextAction":"ensure_session","result":{"workflow":{
   "recommendedTool":"ensure_session","arguments":{}}}}
  ```

- Exact executed action: `ensure_session({})`
- Exact result: `{"status":"failed","reason":"internal_failure",
  "nextAction":"request_human_help"}`

The source trace was:

1. `ProviderSessionBindingService.ensure` entered the stale-generation path
   and called `isWorktreeClean`.
2. `isWorktreeClean` treated every non-Synesis/provider untracked status line
   as dirty, including the two pytest-generated files.
3. The service threw `WORKSPACE_STALE_DIRTY` rather than reallocating the
   clean logical session.
4. `AgentSessionService.ensureSession` mapped the exception to the generic
   `internal_failure / request_human_help` response.

## Production change and regression coverage

Changed:

- `workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java`
  now recognizes only paths under `__pycache__/` as this additional ephemeral
  runtime category in both stale-cleanliness and confirmed-user-change
  classification.
- `workspace/src/test/java/org/synesis/workspace/ProviderSessionBindingServiceTest.java`
  adds deterministic coverage proving Python bytecode-only stale recovery
  preserves the session and reallocates safely, while real untracked
  `reviewer-notes.txt` still blocks recovery and preserves the dirty worktree.

The test was red before the production change (`BindingException` at the
recovery call) and green after it.

## Post-fix bounded diagnostic

Harness:

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0531-001`
- Project ID: `57ef6420-b8a5-4535-bc8d-bf8fb6e7dc1c`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0531-001`
- Seed commit: `2fe7db2`
- Managed baseline: `b361df6`
- Bundled MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`
- CLI build version: `0.1.0-dev.local`
- Bundle SHA-256:
  `462C0AC7D35C9B44EF9712D7EC9909B7A9B1625F48BACF7D0B9660CCA617C881`
- Build metadata: `BUILD_COMMIT=UNKNOWN`, build time
  `2026-08-25T00:28:23.360663200Z`, Windows x64, Java 25
- Both sessions used the same project root and current bundled MCP, with
  distinct connection IDs `syn039-cp0531-001-agent-a` and
  `syn039-cp0531-001-agent-b`.

Participants and claims:

| agent | participant                                | intent                                 | claim                     | epoch |
|-------|--------------------------------------------|----------------------------------------|---------------------------|------:|
| A     | `agt_bdb82963-3bf7-3718-b16c-7bbc61b83d85` | `68330d78-5f7c-3fff-8d92-159412e4c238` | `PATH_EXACT todo.py`      |     1 |
| B     | `agt_60894f8a-09d3-30ee-b16b-9d936e5026c7` | `242ab48e-bd24-3481-9e15-ac7cb3dcf4d5` | `PATH_EXACT test_todo.py` |     1 |

Shared WorkGroup: `ab7d068e-f6cb-3c88-84e7-be59bf3e2c20`.

The exact observed progression was:

1. Both agents independently reached `ready / isolated` and one shared
   WorkGroup. A implemented `todo.py`; B added
   `test_complete_unknown_title_is_a_noop`.
2. B executed the exact projected REVIEW admission. Request
   `bf1059af-7647-4205-baa9-3893e91f87b3` was accepted by A.
3. B consumed single-use grant
   `900dacd9-bbbb-37fc-b487-bf3065206772`, epoch 1, targeted to B.
4. B received `workspace_stale -> ensure_session` after pytest generated
   `__pycache__/`. It executed the exact `ensure_session({})` projection and,
   after the fix, received `ready / isolated` with recovery worktree
   `...\\worktrees\\session-5e30e017-bdbd-48d0-9e64-917f0652da32-recovery-4601a14d-76d0-4177-bbc6-89da9f225946`.
   This is the direct post-fix proof that the production defect is corrected.
5. B executed the exact structured review decision for A's immutable snapshot:

   ```json
   {"kind":"review_validation","payload":{
     "grantId":"900dacd9-bbbb-37fc-b487-bf3065206772",
     "intentId":"68330d78-5f7c-3fff-8d92-159412e4c238",
     "claimEpoch":1,
     "snapshotId":"snap_63278948cfcb644fcfa0755903d87348",
     "result":"accepted"}}
   ```

   Synesis returned `ACCEPTED`.
6. A received a concrete reciprocal REVIEW admission projection with exact
   intent `242ab48e-bd24-3481-9e15-ac7cb3dcf4d5`, but first sent the malformed
   intent `242ab48e-bd24-3481-9e15-acb7-4535-bc8d-bf3065206772`. Synesis
   rejected that wrong argument fail-closed with `UUID string too large`.
   This was not the exact projected action and is agent-compliance evidence,
   not a production defect. A later sent the exact projected request, which
   created request `dd44fab6-30ec-4412-b0ae-a576c2063855`.
7. B consumed reciprocal grant
   `dd380c6f-2bfb-3965-a683-77e9f19b0b99` targeted to A after the valid
   admission. B published and integrated its test snapshot
   `snap_7c3eb3f3a089cfacae21dcb727d40584`; the corresponding snapshot ref
   points to commit `3ec09bfe470bd10c14a3ac409aaf62930b2b8848`.
8. A's Codex turn ended while its exact continuation still showed
   `SNAPSHOT_PENDING` / `get_next_action({})` for the reciprocal review. No
   second validation or terminal WorkGroup closure was observed.

Control checkout after the run contained both snapshots and visible Todo
tests passed 4/4. The host-side inspection itself generated a disposable
`__pycache__/` in the control checkout; this was not used to advance Synesis
state and is retained as run evidence.

## Final diagnostics and classification

Fixture Doctor was `DEGRADED`: 6 warnings, 0 errors, 0 critical findings,
with reconciliation recommended. The warnings were two stale session leases,
command namespace reconciliation, command capacity/retention, and two
provider migration warnings. They did not cause the recovery failure and
remain separately classified.

The bounded diagnostic did not complete end to end, so the conditional second
ordinary acceptance was not run. The first post-fix stop was agent/session
engagement and exact-argument compliance after the concrete recovery defect
was fixed. No lifecycle, ownership, grant, snapshot, validation, integration,
cleanup, or orchestration redesign is justified by this run.

Raw JSONL, prompts, launchers, and MCP traces remain in the harness directory
above.
