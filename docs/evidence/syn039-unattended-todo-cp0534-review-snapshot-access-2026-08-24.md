# SYN-039 CP-0534 reviewer snapshot access

Date: 2026-08-24  
Status: PARTIAL; authorized review access is fixed and verified, but the
bounded two-agent diagnostic stopped at agent engagement before reciprocal
grant consumption and WorkGroup closure.

## Fresh acceptance state

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-review-access-cp0534-001`
- Project ID: `f707bc9a-3969-41d9-b3d5-ac852b820a8b`
- Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-review-access-cp0534-001\logs`
- MCP executable: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- Bundled distribution: `0.1.0-dev.local`, Windows x64; MCP catalog remains exactly
  ten tools. SHA-256: `DC9D5217889B7B49113A0608A094A57F02C9B39E1CB655C26CACE454ECA4F4F4`.
- Both agents used explicit project and connection pins:
  `syn039-cp0534-agent-a` and `syn039-cp0534-agent-b`.
- Both reached `ready / isolated` with distinct worker worktrees:
  `session-47d95378-3ebf-45dd-8641-826285e18bc6` and
  `session-c1e086cc-94b2-47d1-97c6-09758aa9c796`.
- No information was relayed and no lifecycle transition was manually
  triggered. The agents received complementary visible coding prompts and
  the exact projected-action diagnostic rule.

## Projection and action trace

1. Agent A announced intent
   `a2e6202e-7475-32e0-869b-f33c4664ef3d`, claim `PATH_EXACT:todo.py`,
   epoch 1, participant
   `agt_6fad298b-52c8-3c10-9152-1286193493a1`.
   Agent B announced intent
   `b943a231-262c-3b84-bbfc-421149e93eef`, claim
   `PATH_EXACT:test_todo.py`, epoch 1, participant
   `agt_9c3238c2-b566-375b-b67f-cd10305af8c9`.
   Both intents converged on WorkGroup
   `895e9681-8d66-37c0-b3b7-6eb88aa57838`.
2. B executed the exact projected REVIEW admission request
   `f8fbfade-8523-429d-a3a5-6711997b3670` for A's intent. A executed the
   exact projected `respond_coordination` acceptance. The request became
   `ACCEPTED`.
3. A completed `todo.py`, passed `pytest -q test_todo.py` 3/3, and executed
   the exact projected
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   Snapshot `snap_02c173a77f79573d0a2f0fbef319e624` was `PUBLISHED`, commit
   `d03ade9bd3f8c3ec7afed210d5a1d17a1da1c8b1`, and integrated into the
   control checkout at `c232ce6`.
4. B consumed A's single-use REVIEW grant
   `5d151b49-4095-32e1-a080-cfcb06554d4b` with the exact target participant,
   intent, WorkGroup, and epoch. No overlapping write ownership was created.
   B's earlier malformed attempt omitted `targetParticipant` and was rejected
   fail-closed with `COORDINATION_FIELD_REQUIRED:targetParticipant`; the
   unchanged projected grant was then consumed successfully. This is agent
   compliance evidence, not a production bypass.
5. B's `review_decision` projection exposed the exact grant, snapshot,
   intent, epoch, and structured `accepted|rejected` decision. B then used
   ordinary `read_file` for `todo.py` and `test_todo.py`; both responses
   reported `workspace=immutable_review_snapshot`, the exact grant and
   snapshot, and commit `d03ade9...`. B ran
   `python -m pytest -q test_todo.py` in that immutable workspace and received
   exit code 0, 3 passed. B submitted the exact structured ACCEPT response.
6. A requested reciprocal REVIEW admission for B's intent with request
   `6930b86c-7669-44dd-b371-add0ab48f796`; B accepted it. The resulting
   single-use grant was
   `315f0093-98fc-316a-8d51-111cbb05d8a1`, targeted to A at epoch 1.
   Agent A's Codex turn then ended instead of polling and consuming that
   projected grant. B continued receiving the exact
   `WAIT -> get_next_action({})` projection with
   `nextProtocolKind=review_grant_consumption` and remained engaged until the
   bounded observation was stopped. No manual grant consumption was performed.

## Final state

- WorkGroup `895e9681-8d66-37c0-b3b7-6eb88aa57838`: `ACTIVE`, version 1.
- A: `COMPLETED`; B: `ACTIVE`, claim `test_todo.py` retained.
- Both REVIEW requests: `ACCEPTED`.
- Grant `5d151b49-4095-32e1-a080-cfcb06554d4b`: consumed by B.
- Grant `315f0093-98fc-316a-8d51-111cbb05d8a1`: issued to A and unconsumed.
- Snapshot: A's immutable snapshot above; B's snapshot does not exist.
- Control checkout: clean at `c232ce6`; `pytest -q test_todo.py` passed 3/3.
- Coordination CLI: `PASS`, sequence 0, tasks 0, ownerships 0.
- Doctor: `DEGRADED`, six warnings, zero errors/critical findings,
  `reconciliationRecommended=true`. Findings are two stale session leases,
  command namespace reconciliation, command retention/capacity, and two
  provider migration warnings.

## Production slice and deterministic coverage

Commit `a03abe0` adds `ReviewSnapshotAccessService`. It authorizes access only
when the exact participant has a consumed single-use REVIEW grant, the grant
matches the snapshot WorkGroup/lane/claim epoch, and the named snapshot ref
resolves to the recorded commit. It creates a detached disposable worktree
outside the control checkout and grants no write ownership. `read_file` and
bounded `run_command` route to that workspace only for the authorized review;
the reviewer's own dirty lane remains untouched. Review decisions remove the
exact disposable worktree best-effort.

The deterministic MCP coverage is:

- `dirtyReviewerCannotReadAuthorizedImmutableSnapshotAfterControlAdvances`:
  dirty reviewer lane is preserved while snapshot reads and pytest succeed.
- `reviewSnapshotAccessFailsClosedForWrongParticipantAndMismatchedRef`:
  wrong participant and ref/commit mismatch receive no access.
- Existing review-validation coverage continues to reject wrong participant,
  stale epoch, wrong snapshot, invalid result, replay, and conflicting replay.
- Projection/manual/catalog assertions expose the immutable review workspace,
  exact snapshot, read tools, and protected write lane without changing the
  ten-tool surface.

## Verification

- Focused MCP reviewer-access, wrong-participant/ref, review-validation, and
  REVIEW-admission tests: PASS.
- `:workspace:test --tests org.synesis.workspace.AgentNextActionServiceTest`:
  PASS.
- `:workspace:test --tests org.synesis.workspace.AgentWorkflowReducerTest`:
  PASS.
- `:workspace:test --tests org.synesis.workspace.application.provider.ProviderManualServiceTest`:
  PASS.
- `:mcp-contract:test --tests org.synesis.mcp.contract.McpToolCatalogTest`:
  PASS.
- `:cli:platformBundle --rerun-tasks`: PASS.
- `javadoc`: PASS with `-Werror`.
- Deferred and fixture validators: PASS.
- `go vet ./...`: PASS.
- `go test ./...`: reproduces the three known bootstrap migration failures;
  no new failure was introduced.
- `git diff --check`: PASS.
- Full `check`: stopped after exact thread evidence of the recurring Git
  subprocess stall at `WorkspaceCliTest.setUp:74` →
  `ManagedBaselineTransactionService.synchronizeRealIndex` →
  `GitProcessRunner` → `ProcessCommandRunner`; timeout behavior was not
  changed.

## Classification and next action

The stale reviewer read/recovery defect is fixed narrowly. The fresh run
proved an authorized reviewer can inspect and test the immutable snapshot
without rebinding or discarding its own work. The next blocker is agent
engagement/compliance: A ended before consuming its exact reciprocal REVIEW
grant, so B correctly remained in WAIT. Do not change production lifecycle
code for that deviation. The next implementation/acceptance action is a
fresh bounded diagnostic or ordinary acceptance that keeps both agents
engaged through reciprocal grant consumption, B snapshot publication,
validation, integration, cleanup, and terminal WorkGroup state.
