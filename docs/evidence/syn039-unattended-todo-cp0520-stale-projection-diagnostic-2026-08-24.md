# SYN-039 CP-0520 post-fix exact-rule diagnostic

## Result and classification

This fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
sessions, the rebuilt current Synesis MCP bundle, and a new Git + Synesis
project. The agents used complementary visible responsibilities and no manual
relay, lifecycle transition, claim repair, or snapshot publication.

The deterministic stale-dirty regression for CP-0519 is now green. The
production change keeps the existing fail-closed `workspace_stale` boundary,
but lets a bound session with confirmed legitimate dirty work project an
already-authorized durable review, publication, owner-response, or grant-wait
continuation. It does not replace or mutate the dirty worktree.

The fresh diagnostic reached both visible implementations, one shared
WorkGroup, exact REVIEW admission, both single-use grants, both immutable
snapshots, both guarded integrations, and one structured ACCEPT. It did not
reach clean WorkGroup closure. After B published and integrated its snapshot,
A had already finished its last exact `WAIT -> get_next_action({})` polling
cycle and its Codex process ended before observing the new snapshot and
validating it. No exact projected lifecycle action failed in this run.

The first blocker in this run is agent engagement/compliance after a valid
WAIT continuation, not a new production protocol defect. A also tried malformed
request or grant arguments after earlier projections; those fail-closed
responses are retained as agent-compliance evidence, and the later exact
arguments succeeded. No ordinary second acceptance was run because the
diagnostic did not complete end-to-end.

## Fresh project and harness

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0520-001`
- Harness and raw JSONL/stderr logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0520-001`
- Project ID: `ab4e1f51-9ea3-467b-b447-2644f226c534`
- Seed commit: `aa1bd65`
- Synesis-managed baseline: `432912e`
- Current control checkout: `2fb8168`
- Control checkout status: clean
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `D7B8C0E533674C2BDA891FC60F2B013923A731838C1F0601EBC4E5AD3F11360C`
- MCP startup version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Agent A MCP connection: `conn-instance-abcda1f9-665a-4628-b3e6-2a048dc2c6a5`
- Agent B MCP connection: `conn-instance-a6a7a5d9-595b-4522-9f5b-de9f3ddb1043`

## Participants, claims, and WorkGroup

| Agent | Session / participant | Intent, epoch, claim |
|---|---|---|
| A | `session-bfc0802b-67f3-4900-a2b5-b3d49a75802e` / `agt_c1c6a39f-51ef-3999-a69f-f793e3e622de` | `ddeadea8-c8d8-35de-a33b-0394a8da6a5c`, epoch 1, `PATH_EXACT todo.py` |
| B | `session-44719a3f-a95b-41f4-84c6-bf5c36357139` / `agt_c831091b-4087-3944-8fe7-6b42c7232698` | `30a3a95e-82aa-328f-a5a0-8a090928f775`, epoch 1, `PATH_EXACT test_todo.py` |

Shared WorkGroup: `1fea1dc1-607e-3168-99df-8e896bf68295`, final status
`ACTIVE`. The final collaboration status reports both participant lanes as
`COMPLETED` with no active claims, but the WorkGroup remains open because B's
snapshot has not received the reciprocal validation decision.

## Lifecycle trace

1. A and B reached ready/isolated sessions through the current bundled MCP.
   A implemented `todo.py`; B later added the regression test in
   `test_todo.py`. Their visible tests passed.
2. B received and executed the exact REVIEW admission request for A's intent.
   Request: `979c234f-d1a1-4a69-826b-c1e4d7b0cd6a`, eventually accepted by A.
3. B received the single-use grant
   `c749321b-60ad-3b79-af92-da75ef959c89` for A's intent and consumed it with
   the exact target participant on the successful attempt.
4. A received the exact `snapshot_publication_required` projection and
   executed `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   Snapshot: `snap_464a5ab9404ea90ec3619fe7fe049632`; integration advanced the
   control checkout through `807933f`.
5. B recovered a clean stale binding with the projected `ensure_session({})`,
   received the exact review decision for A's snapshot, and executed
   structured ACCEPT. The response was `ACCEPTED`.
6. A received B's reciprocal REVIEW admission projection and created request
   `871ed1dc-e152-412f-a4ea-ecf2bdd3f4e0`; B accepted it.
7. A consumed B's single-use grant
   `d2225f88-dbaa-398e-8cbc-3a9603c4a731`. B then received and executed the
   exact publication projection, producing snapshot
   `snap_c1ff4b1656cfda99df7124fcea8ad12f`; integration advanced the control
   checkout to `2fb8168`.
8. A's last durable projection before its process ended was the exact
   `WAIT -> get_next_action({})` continuation with B's grant pending before
   B's snapshot became visible. A did not poll again after B's publication, so
   no reciprocal validation or WorkGroup closure occurred.

The complete projection-to-action trace is retained in the two harness JSONL
logs. Deviations included malformed request IDs from A (`UUID string too
large`, then `REQUEST_NOT_FOUND`) and one incomplete grant-consumption payload
from B; each was fail-closed and the exact projected payload later succeeded.

## Production slice and deterministic regression

`AgentNextActionService` now uses a bounded stale-dirty coordination projection
only when the existing readiness path proves:

- the exact binding is still `BOUND`;
- the control base advanced;
- the assigned worktree has confirmed non-managed changes.

The projection reuses existing review, snapshot-publication, owner-response,
and review-grant wait decisions. It never reopens, replaces, or mutates the
stale worktree; the existing mutation services retain participant, claim,
epoch, grant, snapshot, and ownership checks.

`McpSyn039SliceTest.dirtyParticipantReceivesPendingReviewAcceptanceAfterControlCheckoutAdvances`
deterministically reproduces the CP-0519 state and proves that the dirty
participant receives the exact `respond_coordination` acceptance payload while
its dirty file and binding remain unchanged. The test is green.

## Final diagnostics and verification

Fixture Doctor result: `DEGRADED`, six warnings, zero errors and zero critical
findings:

- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

These warnings did not prevent ready/isolated sessions, WorkGroup formation,
review admission, grant consumption, snapshot publication, validation, or
integration. They remain separately classified. The known root Git subprocess
startup stall, bootstrap migration test failures, and pre-existing document
format findings also remain separate verification issues.

Passing verification after the run:

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`;
- `:workspace:test --tests org.synesis.workspace.ProviderSessionBindingServiceTest`;
- `:coordination:javadoc :workspace:javadoc :mcp:javadoc`;
- `scripts/agent-validate-deferred.ps1`;
- `scripts/agent-validate-fixtures.ps1`;
- `go vet ./...` in `bootstrap`;
- `git diff --check`.

`go test ./...` in `bootstrap` still has the three known
`update migrations not prepared` failures. The rebuilt bundle was produced by
`:cli:platformBundle --rerun-tasks --no-daemon`.

## Next narrow action

Do not change production code for CP-0520's agent stop. The next acceptance
should use a fresh ordinary two-agent run and preserve whether an ordinary
agent continues after the exact WAIT projection and peer snapshot publication.
If a concrete projected action executes and fails, fix only that defect. If
agents continue and the WorkGroup still remains ACTIVE, capture the first
closure/cleanup/reciprocal-validation blocker. Do not broaden SYN-039, push, or
create SYN-040.
