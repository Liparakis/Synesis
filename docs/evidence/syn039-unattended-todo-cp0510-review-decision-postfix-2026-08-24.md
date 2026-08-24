# SYN-039 CP-0510 — Review-decision projection postfix diagnostic

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Result: the review-decision projection defect is fixed and verified; the
post-fix diagnostic stopped at agent-compliance evidence before reciprocal
validation or WorkGroup closure.

## Fixture and preflight

- Fresh disposable project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0510-001`
- Harness logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0510-001\logs`
- Seed commit: `460433b`
- Managed Synesis baseline: `9d25b9bdbf6d347e2bfbc9caa3ea36a7bbe80cea`
- Project ID: `2c6e414e-4cd9-4437-b96e-8a7793bed4a3`
- MCP executable for both agents:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `74D36D7A3D4EBFBF5539FAD2E42B9F3976446C10350F5BF6B784B5300C4C8386`
- Bundle version: `0.1.0-dev.local`; protocol: `2025-06-18`; ten Synesis
  tools were exposed.
- Agent A session reached `ready / isolated` in
  `...\worktrees\session-3ace5b5c-e541-4a51-adb1-344e2b14ce2c`.
- Agent B session reached `ready / isolated` in
  `...\worktrees\session-f5a09104-2a4f-4575-8d2d-39c7df238e45`.

## Durable coordination state

- WorkGroup: `ba7d9344-fa33-3564-832d-b68222c93296` — `ACTIVE`
- Agent A participant: `agt_74b90843-3c12-34b2-ad81-32c68f3a073e`; intent
  `57cedccb-756e-36c1-aed9-892a83ed8e09`; claim `PATH_EXACT:todo.py`; epoch 1.
- Agent B participant: `agt_12c2e87c-be64-37f0-bf52-afc5671e3423`; intent
  `4d6afa91-77fe-3d74-b643-f5cf104ed7e9`; claim
  `PATH_EXACT:test_todo.py`; epoch 1.
- REVIEW request A → B:
  `c9295bca-1e58-44d2-883b-319e530d724d`, accepted by B.
- REVIEW request B → A:
  `538f87f8-68a1-4fa6-a0dc-df2dcf05c718`, pending at the stop.
- REVIEW grant targeting A for B's intent:
  `56b70ede-20ff-3705-957d-2fe239029181`, consumed once, epoch 1.
- No validation decision was recorded. B's snapshot was
  `snap_9b3fae1c10ee7f69f381d035d77d211b`, commit
  `b59037c7da99bcce41bc08d36d2c697ff5492108`, changed path `test_todo.py`,
  and integration reported `integrated`.

## Projection and action trace

1. Both agents began with ordinary `IMPLEMENT` and no executable lifecycle
   action. They performed only their assigned visible repository work.
2. Agent A received and executed the exact projected
   `request_coordination(work_group_join)` admission for B's intent. B then
   received and executed the exact owner `respond_coordination` acceptance.
3. A received and executed the exact single-use grant-consumption projection
   for grant `56b70ede-20ff-3705-957d-2fe239029181`. B then received
   `snapshot_publication_required` with exact `finish_lane` arguments and
   executed it successfully. The immutable B snapshot became integrated.
4. After recovering from a normal `workspace_stale` / `ensure_session` state,
   A received this review projection:

   ```text
   nextAction=review_decision
   nextProtocolAction=review_decision
   nextProtocolKind=review_validation
   nextProtocolPayload={
     grantId=56b70ede-20ff-3705-957d-2fe239029181,
     intentId=4d6afa91-77fe-3d74-b643-f5cf104ed7e9,
     claimEpoch=1,
     snapshotId=snap_9b3fae1c10ee7f69f381d035d77d211b
   }
   reviewDecision={field:result, allowedResults:[accepted,rejected],
                   rejectionReasonRequired:true}
   workflow.recommendedTool=<absent>
   workflow.arguments=<absent>
   ```

   This is the corrected contract: the reviewer receives the immutable
   snapshot and exact fencing context, but Synesis does not fabricate a
   choice or label an incomplete payload as executable `respond_coordination`.
5. B then received an exact projected admission request for A's intent:

   ```text
   projected intentId=57cedccb-756e-36c1-aed9-892a83ed8e09
   ```

   B called `request_coordination` with a different value:

   ```text
   actual intentId=57cedccb-756e-36c1-aed9-892a83ed7e09
   result=blocked / policy_denied / INTENT_NOT_FOUND
   ```

   The agent changed the projected argument. Synesis correctly failed closed;
   no production defect or authorization bypass is indicated. B stopped at
   that first deviation.

## Final state and diagnostics

- WorkGroup remained `ACTIVE`.
- B's `test_todo.py` snapshot was integrated; A's `todo.py` snapshot was not
  published, and no review decision was recorded.
- No manual relay, request acceptance, grant consumption, publication,
  validation, or integration was performed by the harness.
- Fresh fixture Doctor: `DEGRADED`, six warnings, zero errors, zero critical
  findings. Warning codes were two `stale_session_lease`,
  `command_namespace_reconciliation_required`,
  `command_capacity_or_retention`, and two `provider_migration_required`.
  These did not cause the projection mismatch and remain separate work.
- No ordinary second acceptance was run because the bounded diagnostic did not
  reach end-to-end completion.

## Verification after the fix

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest` — PASS.
- `:workspace:test --tests org.synesis.workspace.agent.AgentWorkflowReducerTest` — PASS.
- `:cli:platformBundle --rerun-tasks` — PASS; bundle hash above.
- `:workspace:javadoc` — PASS.
- Fixture and deferred validators — PASS.
- `go vet ./...` in `bootstrap` — PASS.
- `git diff --check` — PASS.
- Repository Doctor — structural checks PASS with the existing personal-path
  warning; fixture Doctor remained DEGRADED as described above.

## Classification and next action

The CP-0509 production defect was a mislabeled review-validation projection:
`respond_coordination` was exposed without its required reviewer-selected
`result`. It is fixed by the explicit `review_decision` state. CP-0510 proves
the corrected projection and exact REVIEW admission/publication path. The
first later failure is agent compliance with an exact projected argument, not
a new production defect.

Next: run a fresh completely ordinary two-agent acceptance with only the
complementary coding prompts, no lifecycle coaching, and no manual
intervention. Do not change production code for the CP-0510 typo.
