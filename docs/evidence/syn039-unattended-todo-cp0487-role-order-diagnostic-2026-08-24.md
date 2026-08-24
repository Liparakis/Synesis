# SYN-039 unattended Todo CP-0487 role-order diagnostic

## Classification

CP-0487 reached a real shared WorkGroup, exact REVIEW admission, owner
responses, and single-use grant consumption. It did not prove a new Synesis
protocol defect. Agent B established the WorkGroup first, so B's test intent
was the producer/owner and Agent A's implementation intent became the
reviewer. The resulting `SNAPSHOT_PENDING` projection for A was correct. B
was not queried again after A consumed the grant, so no evidence exists that a
projected producer publication action failed or was missing when requested.

The run used a bounded diagnostic rule forbidding unprojected lifecycle and
integration actions. No manual relay or lifecycle transition was performed.

## Harness and preflight

- Fresh project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0487-001`.
- External harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0487-001`.
- Project ID: `94e7b869-4b26-4669-bbd2-5de2e344d018`.
- Seed commit: `532ae0a Seed unattended Todo acceptance fixture`.
- Managed baseline: `fb239063154e445ee58d9fdbc166b0e3b143298a`.
- Provider setup commit: `aed120f Record provider setup for acceptance fixture`.
- Current MCP: bundled `synesis-mcp.exe`, SHA-256
  `8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`.
- Both GPT-5.6 Luna sessions used the same explicit project-pinned MCP and
  reached `ready / isolated` with distinct worktrees.

## Participants and claims

- Agent A session `session-71afd7c2-5a78-482a-bfb2-aa005acec0d4`, participant
  `agt_da705a23-70c4-34a1-ae38-8b490604f7c9`, implementation intent
  `fbf387b9-17d5-3f21-993b-946b4757e91d`, epoch 1, exact claim `todo.py`.
- Agent B session `session-295de21d-d331-414e-a734-9e1e5695174a`, participant
  `agt_79796286-cf9e-3e85-800a-fbb4af7f9e8f`, test intent
  `ee1f3200-e10f-3649-bf30-761e5e6105d4`, epoch 1, exact claim
  `test_todo.py`.
- Shared WorkGroup: `a273e5df-a157-3ec7-ae93-211828d0acc2`.

## Exact lifecycle trace

1. Agent A's `get_next_action` projected:
   `request_coordination` with
   `{"kind":"work_group_join","payload":{"workGroupId":"a273e5df-a157-3ec7-ae93-211828d0acc2","proposal":"Review the immutable snapshot for this work group","intentId":"ee1f3200-e10f-3649-bf30-761e5e6105d4"}}`.
2. A executed that exact action successfully, creating requests
   `d530b437-e35b-40cd-9c23-b571278f0771` and, after the same retry-safe
   projection was repeated, `a679764d-df40-42b0-87f0-19b3dfa0a120`.
3. B received exact projected `respond_coordination` actions and accepted both
   requests. Grants were issued for reviewer A:
   `5ba56aa7-3887-3ee1-8973-919669144888` and
   `7907440e-cc5d-39a2-a4b6-b228290ff381`, both single-use at epoch 1.
4. A executed the exact projected grant-consumption action for grant
   `5ba56aa7-3887-3ee1-8973-919669144888`; the result was `CONSUMED`.
5. A's subsequent exact `get_next_action` projection was:
   `SNAPSHOT_PENDING` → `wait` → `get_next_action`, with the consumed grant
   and `snapshotRequired=true`. This is the correct reviewer-side state while
   the producer's immutable snapshot is absent.
6. B's last `get_next_action` occurred before A consumed the grant and
   returned ordinary `IMPLEMENT` with no executable lifecycle tool. B created
   only `test_todo.py`, observed the expected local `2 passed, 1 failed` result
   because its isolated worktree did not contain A's implementation, and did
   not invoke integration or human intervention. B did not poll again after
   A's later grant consumption.
7. A corrected an inline-check indentation error, passed its focused checks,
   and ended with the same reviewer-side `SNAPSHOT_PENDING` projection. It did
   not call an unprojected lifecycle action.

## Terminal state

- WorkGroup remained `ACTIVE`.
- No snapshot, validation decision, integration result, or closure occurred.
- Two pending-review grants existed; no control-checkout Todo change was
  integrated. Control remained at `aed120f`.
- Final fixture Doctor was `DEGRADED` with six warnings: two
  `stale_session_lease`, `command_namespace_reconciliation_required`,
  `command_capacity_or_retention`, and two `provider_migration_required`.

The duplicate requests/grants resulted from the agent following a repeated
retry-safe projection before the owner responded. This is recorded for later
idempotency/cleanup review, but it was not the first failed exact action in
this run.

## Verification and conclusion

The current repository focused SYN-039 tests, Javadocs, validators, Doctor
structural checks, and `git diff --check` remain green from CP-0485. The known
root Git subprocess stall, bootstrap migration failures, and unrelated Doctor
warnings remain separate. The next diagnostic must establish the
implementation intent first, then continue without manual intervention to
exercise producer publication and reviewer validation.
