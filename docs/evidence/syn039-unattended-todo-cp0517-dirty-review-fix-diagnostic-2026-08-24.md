# SYN-039 dirty-review continuation diagnostic — CP-0517

## Result and classification

The stale-reviewer production slice is implemented and its deterministic MCP
regression passes. A fresh two-agent diagnostic then confirmed shared-project
readiness, disjoint claims, one WorkGroup, and exact REVIEW admission request
execution. It stopped before owner acceptance because Agent A's Codex turn
ended after its last ordinary `IMPLEMENT` projection; Agent B's later REVIEW
request remained pending. No projected action was executed unsuccessfully, and
no backend lifecycle contradiction was reached in this run. The first fresh
acceptance blocker is agent engagement/compliance after a coordination request
is created asynchronously, not a reason to change production lifecycle code.

## Production slice verified

`AgentNextActionService` now permits a narrowly bounded review-only projection
when all of the following are true:

- the exact provider binding is still `BOUND`;
- readiness reports `CONTROL_BASE_ADVANCED`;
- the assigned worktree has confirmed non-managed uncommitted work; and
- durable review state contains a grant-backed action for this participant.

The path never reopens, replaces, reads, or mutates the stale worktree. It
returns the existing `review_decision` projection, and
`ReviewValidationService` retains the exact participant, grant, intent, epoch,
snapshot, and single-use authorization checks. Clean stale worktrees continue
to use the existing `ensure_session` recovery path. If worktree dirtiness
cannot be confirmed, the path remains fail-closed.

Regression coverage:

- `McpSyn039SliceTest.dirtyReviewerReceivesDurableReviewDecisionAfterControlCheckoutAdvances`
  proves a dirty reviewer sees `validation_required` / `review_decision`,
  executes the structured ACCEPT decision, retains its dirty file, and keeps
  the same worktree binding.
- `reviewerRecoveryPreservesConsumedGrantAfterControlCheckoutAdvances` still
  proves clean stale recovery rebinds through `ensure_session`.
- `ProviderSessionBindingServiceTest` still proves stale dirty workspace
  replacement is rejected.

## Fresh diagnostic fixture

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0517-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0517-001`
- Seed commit: `e32dbcc2544aab50a6df3bfca2be1d15f46bd7d`
- Project ID: `d670cb0d-c17d-4d33-9c3a-afe37d5bb138`
- Control checkout at observation: `7e182cb`
- MCP executable used by both wrappers:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `6E80EBD9586806B6E5B48078947220483566F1176D82A126A2DA422F62B670C8`
- MCP version: `0.1.0-dev.local`; protocol/tool preflight used the current
  bundled distribution and the existing ten-tool contract.

Both sessions reached `ready / isolated` through the current MCP. Agent A
was launched first; Agent B was launched only after A's claim and WorkGroup
were visible. No IDs, messages, or lifecycle payloads were relayed manually.

| Agent | Session | Participant | Intent / epoch / claim |
|---|---|---|---|
| A producer | `session-48f9b2c0-8b89-4a9a-bb1e-47f7c07e3af1` | `agt_0e07bc02-780b-3178-902e-cdd0c1434514` | `b309e499-7903-3687-b291-3fa168476f67`, epoch 1, `PATH_EXACT todo.py` |
| B reviewer | `session-464bff19-a9ff-4b77-9e56-84fc3a1e4661` | `agt_1985dd6f-3214-3887-b07b-81fa48e4c4d5` | `e4e7f529-3008-3e9f-a7fc-5230d7e5dcf1`, epoch 1, `PATH_EXACT test_todo.py` |

Shared WorkGroup: `fde62e9a-5f84-370a-84db-36a21117d1f7`, `ACTIVE`.

## Exact lifecycle trace

1. A executed claim-bearing `ensure_session` and received `ready / isolated`.
   Its first `get_next_action` projected ordinary `IMPLEMENT` with no
   executable lifecycle tool. A implemented `TodoList.complete` in its own
   worktree and ran `pytest`; result `2 passed`.
2. B first received `RECOVER → ensure_session({})`, executed it, then
   announced the exact disjoint `PATH_EXACT test_todo.py` intent. Its visible
   test correctly exposed the unimplemented producer behavior (`2 passed,
   1 failed`, `NotImplementedError`).
3. B's `get_next_action` then projected the exact admission action:

   ```json
   {
     "kind":"work_group_join",
     "payload":{
       "workGroupId":"fde62e9a-5f84-370a-84db-36a21117d1f7",
       "intentId":"b309e499-7903-3687-b291-3fa168476f67",
       "proposal":"Review the immutable snapshot for this work group"
     }
   }
   ```

   B executed that exact `request_coordination` action. Request:
   `41f48a24-8ff1-4638-bcd2-ce25f90ce369`, status `PENDING`, kind `REVIEW`,
   requester B, target A.
4. A's last `get_next_action` occurred before B created the request and still
   projected ordinary `IMPLEMENT` with no executable lifecycle action. A then
   ended its Codex turn after reporting its local tests. It did not observe or
   ignore a later concrete `respond_coordination` projection.

No REVIEW grant, grant consumption, snapshot, validation decision, integration,
or WorkGroup closure was created in this run. The durable WorkGroup remained
`ACTIVE`; the control checkout remained at the managed baseline because no
lane reached `finish_lane`.

Raw logs are retained under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0517-001\logs`.

## Diagnostics and verification

Fixture Doctor at the end of the run:

- `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

These warnings did not prevent the two agents from reaching ready/isolated
sessions or creating the shared WorkGroup, so they remain separately
classified.

Passing verification:

- focused MCP SYN-039 tests, including the new dirty-reviewer regression;
- focused workspace binding tests;
- `:coordination:javadoc :workspace:javadoc :mcp:javadoc`;
- `:cli:platformBundle --rerun-tasks`;
- `scripts/agent-validate-deferred.ps1`;
- `scripts/agent-validate-fixtures.ps1`;
- `bootstrap`: `go vet ./...`;
- `git diff --check`.

Known independent failures:

- `bootstrap`: `go test -count=1 ./...` still fails the three existing
  migration tests: `TestBootstrapInstallUpdateRollbackDoctorAndUninstall`,
  `TestLegacyLayoutMigration`, and
  `TestPreparedVersionedUpdateRetainsPayloadAndRollsBack`, each reporting
  `update migrations not prepared`.
- Full root `check` again reached the known Git subprocess startup stall.
  Thread dumps showed `ProcessCommandRunner` waiting around Git startup in
  `WorkspaceMutationBrokerTest.test07SymlinkEscape` and
  `CapabilityNegotiationTest.setUp`; the run was stopped after preserving that
  evidence. No larger timeout or behavior weakening was applied.
- `:link:formatCheck` separately reports pre-existing trailing whitespace in
  older checkpoint/evidence documents, including CP-0488 through CP-0503 and
  earlier SYN-039 evidence files.

## Next action

Do not change production lifecycle code for this run. The next acceptance
slice is a fresh ordinary two-agent run that verifies whether normal Codex
sessions remain engaged long enough to poll after the peer creates the REVIEW
request. If the agents reach a concrete projected owner response, continue
capturing the first later lifecycle blocker; if they stop again with no
projection ignored, preserve it as agent-compliance evidence and strengthen
the acceptance harness only if the repository contract is demonstrably
ambiguous.
