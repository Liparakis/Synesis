# SYN-039 producer-first exact-action diagnostic — CP-0516

## Result and classification

This fresh bounded diagnostic used two independent GPT-5.6 Luna Codex
sessions, the current bundled Synesis MCP, one fresh Git + Synesis project,
and no harness relay or manual lifecycle transition. The producer was started
first so it established the shared WorkGroup before the complementary reviewer
joined.

The protocol executed exactly through producer publication and integration.
The first concrete blocker was reviewer recovery after the control checkout
advanced: the reviewer had legitimate uncommitted work in its assigned
worktree, so the existing fail-closed stale-dirty guard refused to replace or
discard it. The projected recovery action was executed exactly but returned
`failed / internal_failure / request_human_help`. The reviewer therefore could
not reach the immutable snapshot's structured validation decision. This is a
production workflow gap in safe reviewer access/recovery, not agent refusal to
follow a projection and not a reason to weaken dirty-worktree protection.

No production code changed during this diagnostic.

## Fresh project and harness

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0516-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0516-001`
- Project ID: `84886099-22fe-489a-ae5c-94fcb6159b9d`
- Seed commit: `02f3ae35fa9f8cb4a8deeb300774abf8fd9c4855`
- Final control commit: `f23a382` (`Synesis immutable lane snapshot`)
- Final control checkout: clean
- MCP executable used by both wrappers:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `15967D97D566BE1F758098A32DDD54CFB3F2EB6B8A641EA9EA47A7BB1A05544F`
- MCP protocol/version: current bundled protocol `2025-06-18`,
  `0.1.0-dev.local`; the configured catalog exposes exactly ten tools.
- Both sessions independently returned `ensure_session=ready` with
  `workspace=isolated` before visible mutation.

The producer was launched first and observed with read-only log/status checks
until its claim-bearing intent and WorkGroup existed. The reviewer was then
launched independently against the same project. No IDs or lifecycle payloads
were relayed between agents.

## Participants, claims, and WorkGroup

| Agent      | Session / worktree                                                                                                                                                                                 | Participant                                | Intent / epoch / claim                                                     |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|----------------------------------------------------------------------------|
| A producer | `session-69511693-1e0d-4c2b-975c-77a2d355256c` / `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\84886099-22fe-489a-ae5c-94fcb6159b9d\worktrees\session-69511693-1e0d-4c2b-975c-77a2d355256c` | `agt_5474ea46-8e50-3600-8cc6-08c9647a1e1c` | `f78beb42-196b-3dad-b017-156121fcb891`, epoch 1, `PATH_EXACT todo.py`      |
| B reviewer | `session-7683acda-b612-4301-9b15-944841c94890` / `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\84886099-22fe-489a-ae5c-94fcb6159b9d\worktrees\session-7683acda-b612-4301-9b15-944841c94890` | `agt_a72db411-a715-3b13-8cea-04322c9fe0f8` | `710d7afa-db77-310d-946a-e85dbe598ab1`, epoch 1, `PATH_EXACT test_todo.py` |

- WorkGroup: `f4bba8b9-1d6f-36ba-9285-5b63cbf702cd`, final state `ACTIVE`
- REVIEW request: `5b80bc40-70f7-43d9-8c98-09f70a2ee669`, `ACCEPTED`
- REVIEW grant: `9bab2075-4ef2-32a8-a46e-6da66cc7f27d`, single-use, epoch 1,
  targeted to B for A's intent; B consumed it exactly once
- Claims remained disjoint throughout; no overlapping write ownership was
  created for review.

## Exact projection/action trace

Every concrete lifecycle projection observed in the logs was followed by the
same MCP tool and arguments. Ordinary `IMPLEMENT` projections resulted in
visible repository work only.

1. A called claim-bearing `ensure_session` for `todo.py`; B called
   claim-bearing `ensure_session` for `test_todo.py`. Both were ready and
   isolated.
2. B received and executed the exact REVIEW admission projection:

   ```json
   {"kind":"work_group_join","payload":{"proposal":"Review the immutable snapshot for this work group","workGroupId":"f4bba8b9-1d6f-36ba-9285-5b63cbf702cd","intentId":"f78beb42-196b-3dad-b017-156121fcb891"}}
   ```

   The request was created as `5b80bc40-70f7-43d9-8c98-09f70a2ee669`.
3. A received and executed the exact owner projection:

   ```json
   {"kind":"coordination_response","payload":{"coordinationRequest":"5b80bc40-70f7-43d9-8c98-09f70a2ee669","coordinationStatus":"ACCEPTED","proposal":"admitted"}}
   ```

4. B received the exact targeted grant-consumption payload and executed it:

   ```json
   {"kind":"work_group_join","payload":{"grantId":"9bab2075-4ef2-32a8-a46e-6da66cc7f27d","intentId":"f78beb42-196b-3dad-b017-156121fcb891","claimEpoch":1,"workGroupId":"f4bba8b9-1d6f-36ba-9285-5b63cbf702cd","targetParticipant":"agt_a72db411-a715-3b13-8cea-04322c9fe0f8"}}
   ```

   Result: `status=CONSUMED`.
5. B received `SNAPSHOT_PENDING` with the exact continuation
   `get_next_action({})` and executed the continuation. A then received:

   ```json
   {"recommendedTool":"finish_lane","arguments":{"summary":"Publish the completed immutable snapshot"}}
   ```

   A executed those exact arguments. Result:

    - snapshot: `snap_171a6f766e26454cf60e6cebc3106f63`
    - snapshot state: `PUBLISHED`
    - integration state: `integrated`
    - lane: `f78beb42-196b-3dad-b017-156121fcb891`
    - claim epoch: `1`
6. After A's integration advanced the control checkout, B executed the exact
   recovery projection:

   ```json
   {"recommendedTool":"ensure_session","arguments":{}}
   ```

   Result: `{"status":"failed","reason":"internal_failure","nextAction":"request_human_help"}`.
   The first failure stopped the diagnostic. A later repeated projection and
   retry reproduced the same result and was not treated as a new blocker.

## First blocker: exact readiness path

At the failure boundary:

- A's snapshot was immutable and integrated into control commit `f23a382`.
- B's assigned worktree still contained B's legitimate uncommitted
  `test_todo.py` change and an untracked `__pycache__/` directory.
- B's worker HEAD remained at the managed baseline, while control had advanced.
- `ProviderSessionBindingService.ensure` detected the stale generation and
  deliberately refused `reallocatePreservingSession` because
  `isWorktreeClean(binding)` was false; the source error is
  `WORKSPACE_STALE_DIRTY`.
- `AgentSessionService.ensureSession` catches that binding failure through its
  generic exception path and translates it to `FAILED / INTERNAL_FAILURE /
  REQUEST_HUMAN_HELP`.
- The existing stale-dirty guard is correct to avoid silently discarding B's
  changes. The missing product behavior is a safe review/continuation path
  that preserves B's dirty work while allowing grant-authorized review of A's
  immutable snapshot and subsequent completion.

This is distinct from an agent choosing an unprojected action: no such choice
occurred in the diagnostic.

## Final state and diagnostics

- Agent A: `COMPLETED`; claim released after publication/integration.
- Agent B: `ACTIVE`; claim `PATH_EXACT test_todo.py` retained.
- WorkGroup: `ACTIVE`.
- REVIEW request: `ACCEPTED`.
- REVIEW grant: consumed, single-use.
- Snapshot: published and integrated for A; no B snapshot.
- Validation decision: none recorded.
- Integration: A integrated; no B integration.
- WorkGroup closure: not reached.
- Control checkout: clean and contains A's Todo implementation.
- Fixture Doctor: `DEGRADED`, six warnings, zero errors/critical findings:
  two stale session leases, command-namespace reconciliation, command
  capacity/retention, and two provider-migration warnings.

Raw agent logs are retained under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0516-001\logs`.

## Verification after the diagnostic

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`: PASS
- `:workspace:test --tests org.synesis.workspace.ProviderSessionBindingServiceTest`: PASS
- `:coordination:javadoc :workspace:javadoc :mcp:javadoc`: PASS
- `scripts/agent-validate-deferred.ps1`: PASS
- `scripts/agent-validate-fixtures.ps1`: PASS
- `go vet ./...`: PASS
- `git diff --check`: PASS
- `go test -count=1 ./...`: FAIL, three existing bootstrap migration failures
  (`TestBootstrapInstallUpdateRollbackDoctorAndUninstall`,
  `TestLegacyLayoutMigration`, and
  `TestPreparedVersionedUpdateRetainsPayloadAndRollsBack`), all reporting
  `update migrations not prepared`.
- Root Git subprocess stall and the repository's existing Doctor warnings
  remain separately classified; neither caused the acceptance boundary above.

## Next narrow implementation slice

Trace and fix only the stale-reviewer continuation boundary. The smallest
candidate is to let a grant-authorized reviewer continue durable review
projection/validation after sibling integration without reopening or
discarding its dirty write worktree; any path that would mutate or replace
dirty work must remain fail-closed. Add deterministic coverage for dirty
reviewer work, control-head advancement, exact review projection, preserved
claim/epoch/grant fencing, and rejection of conflicting recovery.
