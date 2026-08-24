# SYN-039 unattended Todo CP-0489 diagnostic

## Classification

This bounded diagnostic reached one shared WorkGroup, exact REVIEW admission,
owner acceptance, two REVIEW grants, and exact consumption of one single-use
grant. It did not reach snapshot publication, validation, integration, or
closure. No exact projected lifecycle action failed, and no production defect
is proven by this run.

The first stop is agent-side lifecycle polling: the producer's last
`get_next_action` occurred after owner acceptance but before the reviewer
consumed the grant. It returned ordinary `IMPLEMENT` with no executable
lifecycle action. The producer ended without polling again after the later
reviewer consumption, so the producer publication projection was never
requested. The reviewer correctly received `wait` / `get_next_action` while
the producer snapshot was absent.

The required second ordinary unattended acceptance was not run because the
diagnostic did not complete end to end.

## Fixture and MCP route

- Fixture project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0489-001`
- External harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0489-001`
- Project ID: `bceaf899-f1a3-4a65-8538-4f303a072e5d`
- Seed commit: `70dd800`
- Managed baseline: `e5a531f`
- Provider setup commit: `f27bbec`
- Current MCP executable:
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`
- MCP SHA-256:
  `8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`
- CLI version metadata: `0.1.0-dev.local`, record format `SDR2`,
  reconciliation protocol `PRP1`, platform `windows-x64`
- Both Codex launches used `gpt-5.6-luna`, `--ephemeral`,
  `--ignore-user-config`, the same project `-C` root, and an explicit
  project-pinned MCP command. The MCP wrapper passed
  `mcp --provider codex --project <fixture-root>`.
- Both sessions returned `ready` / `isolated` from `ensure_session` with
  distinct assigned worktrees:
  - A: `...\worktrees\session-da28873b-b1df-4669-a360-bef6f4839a20`
  - B: `...\worktrees\session-4076204d-548b-4451-99f8-7fc61952f5eb`

The repository contract and current bundle expose the ten-tool MCP boundary;
the agent JSONL did not independently enumerate the catalog during this run.

## Participants, claims, and WorkGroup

- Agent A: participant
  `agt_1d03a770-0c29-3325-a76d-f4b4d1507f75`, implementation intent
  `dd84812f-ae13-3703-828c-5227fac0ad56`, epoch `1`, exact claim
  `todo.py`.
- Agent B: participant
  `agt_e034b416-9f0d-3a1e-8fc2-62757a638bfd`, test intent
  `c06a0af8-8fee-3a48-9fc7-8588626a97e5`, epoch `1`, exact claim
  `test_todo.py`.
- Shared WorkGroup:
  `2176bfbd-6199-303f-805c-a91c382b92ff`, version `1`, status `ACTIVE`.

B announced the WorkGroup first, so B's test intent was the producer/owner
and A's implementation intent became the reviewer for this run. Claims were
disjoint and no overlapping write ownership was created.

## Projection-to-action trace

The raw JSONL is retained outside the repository at the harness path above.
The relevant ordered trace is:

1. A `get_next_action` returned `REVIEW_ADMISSION_REQUIRED` with exact
   executable arguments:

   ```json
   {"kind":"work_group_join","payload":{"workGroupId":"2176bfbd-6199-303f-805c-a91c382b92ff","proposal":"Review the immutable snapshot for this work group","intentId":"c06a0af8-8fee-3a48-9fc7-8588626a97e5"}}
   ```

   A executed that exact `request_coordination` action successfully. Request
   `7168a060-4d20-4975-8553-6cd66ed8906d` was created as `PENDING`.

2. The same retry-safe request projection was repeated. A executed the exact
   same action again, creating request
   `cce97366-4c2e-47d7-8553-f36f79cc02bb`, also `PENDING`. A received the same
   projection once more and then continued its assigned visible-file work;
   it did not select another unprojected lifecycle action.

3. B's initial `get_next_action` returned ordinary `IMPLEMENT` with no
   `recommendedTool` or executable arguments. B created `test_todo.py` and
   ran `pytest -q test_todo.py`, which reported exit code `1` with `2 passed,
   1 failed` because its isolated worktree did not yet contain A's
   `TodoList.complete` implementation. B then called `get_next_action` and
   did not request integration or human intervention.

4. B's first concrete projection was exact owner acceptance:

   ```json
   {"kind":"coordination_response","payload":{"coordinationRequest":"7168a060-4d20-4975-8553-6cd66ed8906d","coordinationStatus":"ACCEPTED","proposal":"admitted"}}
   ```

   B executed the exact `respond_coordination` action successfully. A second
   exact projection for request
   `cce97366-4c2e-47d7-8553-f36f79cc02bb` was also executed successfully.
   The resulting requests were `ACCEPTED` and grants were issued:
   `215ba3af-5cf9-352a-ac5e-5685438a7d12` and
   `d831734a-d597-3457-b817-ae5b3f7e6e70`, both targeted to A at epoch `1`.

5. A completed `todo.py` in its assigned lane and passed focused checks. Its
   next concrete projection was exact single-use grant consumption through
   the existing coordination tool:

   ```json
   {"kind":"work_group_join","payload":{"workGroupId":"2176bfbd-6199-303f-805c-a91c382b92ff","grantId":"215ba3af-5cf9-352a-ac5e-5685438a7d12","intentId":"c06a0af8-8fee-3a48-9fc7-8588626a97e5","claimEpoch":1,"targetParticipant":"agt_1d03a770-0c29-3325-a76d-f4b4d1507f75"}}
   ```

   A executed the exact action and received `CONSUMED`.

6. After consumption, A's `get_next_action` returned
   `SNAPSHOT_PENDING` / `wait`, with the exact recommended retry action
   `get_next_action` and arguments `{}`. A executed that retry and received
   the same reviewer-side wait state again. This was correct: B's immutable
   producer snapshot did not yet exist.

7. B's final `get_next_action` occurred after both owner responses but before
   A consumed the grant. It returned ordinary `IMPLEMENT` with no concrete
   lifecycle action, `pendingCoordination=0`, `grants=2`, and
   `snapshots=0`. B ended after its assigned test work and did not poll again
   after A's later grant consumption. Therefore no producer-side
   `snapshot_publication_required` projection was requested, and no exact
   `finish_lane` action could be tested.

## Terminal state

The read-only collaboration status showed:

- both participants `ACTIVE`;
- exact claims `test_todo.py` and `todo.py`, each at epoch `1`;
- both REVIEW requests `ACCEPTED`;
- both grants present, with the first consumed by A and no second grant
  consumption recorded;
- WorkGroup `2176bfbd-6199-303f-805c-a91c382b92ff` still `ACTIVE`.

No snapshot, structured validation decision, integration result, or terminal
WorkGroup closure was recorded. The control checkout remained clean at
`f27bbec` and contained only the managed baseline application files.

The project CLI coordination view reported `COORDINATION_STATUS=PASS`,
`PROJECT_SEQUENCE=0`, `TASKS=0`, and `OWNERSHIPS=0`; collaboration status is
the authoritative view for the project WorkGroup and claims in this fixture.

Fixture Doctor reported `DEGRADED`, six warnings, zero critical findings and
zero errors, with reconciliation recommended:

- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

These warnings were not shown to cause the lifecycle stop and remain
separately classified.

## Verification

Passed:

- `gradlew.bat :workspace:test --tests org.synesis.workspace.agent.AgentWorkflowReducerTest --tests org.synesis.workspace.Syn037CompletionValidationTest :mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest --no-daemon --max-workers=1 --console=plain`
- `gradlew.bat :workspace:javadoc :mcp:javadoc :coordination:javadoc --no-daemon --max-workers=1 --console=plain`
- `scripts/agent-validate-deferred.ps1`
- `scripts/agent-validate-fixtures.ps1`
- `scripts/agent-doctor.ps1` (exit 0; one existing personal absolute-path
  documentation warning)
- `go vet ./...` from `bootstrap`
- `git diff --check`

`go test -count=1 ./...` from `bootstrap` reproduced the three known
migration failures: `TestBootstrapInstallUpdateRollbackDoctorAndUninstall`,
`TestLegacyLayoutMigration`, and
`TestPreparedVersionedUpdateRetainsPayloadAndRollsBack`, each reporting
`update migrations not prepared`.

The first full root-check attempt stopped at `:link:formatCheck` because
eleven already-committed SYN-039 Markdown artifacts contained trailing
spaces. Those documentation-only files were normalized. The next full check
passed format, compilation, Javadocs, static analysis, bundle, launcher, and
module checks, then stalled at `:mcp:test`. A bounded thread dump showed:

```text
Test worker -> ProcessCommandRunner.execute(ProcessCommandRunner.java:81)
  -> GitProcessRunner.runInternal(GitProcessRunner.java:129)
  -> McpServerTest.git(McpServerTest.java:32)
  -> McpServerTest.setUp(McpServerTest.java:38)
```

The worker was `TIMED_WAITING` in the existing command runner while its
`synesis-process-output` and `synesis-process-wait` helper threads remained
active. The check was interrupted after preserving this evidence; no timeout
was increased and no test or production behavior was weakened.

## Boundary and next action

No production code changed for CP-0489. SYN-039 remains `ACTIVE`; no SYN-040
was created and nothing was pushed. The exact next action is a fresh bounded
diagnostic that keeps both agents alive and returning to `get_next_action`
after a wait or peer-side state change, while still forbidding manual relay
and unprojected lifecycle actions. It must capture the producer's post-grant
publication projection and continue through reviewer validation before any
ordinary unattended acceptance is attempted.
