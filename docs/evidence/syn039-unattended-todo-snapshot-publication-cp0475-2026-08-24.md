# SYN-039 snapshot-publication slice — CP-0475 — 2026-08-24

## Production defect reproduced

The owner-side `get_next_action` projection returned:

- `reason=snapshot_publication_required`
- `nextAction=finish_lane`
- `nextProtocolAction=finish_lane`
- `nextProtocolPayload={summary=Publish the completed immutable snapshot}`

but `AgentWorkflowReducer` emitted `workflow.arguments={}`. The exact
executable action therefore discarded the only protocol payload needed by the
existing `finish_lane` operation. This was the confirmed mismatch behind the
owner-side publication stop; the publication readiness checks themselves were
not weakened.

At the deterministic reproduction point, the owner had the active WorkGroup
and intent at claim epoch `1`, the REVIEW grant had been consumed by the
targeted reviewer, the owner still held the claimed `todo.py` path, the owner
binding was trusted and isolated, no validation context or capability blocker
was pending, the control checkout was clean, and the owner worktree contained
the claimed Todo change. With the projected empty argument map, the protocol
could not execute the intended publication request. With the exact projected
summary supplied, the existing completion path succeeded.

## Minimal implementation

`workspace/src/main/java/org/synesis/workspace/application/agent/AgentWorkflowReducer.java`
now copies the existing `nextProtocolPayload` into the executable
`finish_lane` arguments. No new tool, role, ownership path, orchestrator, or
readiness bypass was added.

## Deterministic evidence

`mcp/src/test/java/org/synesis/mcp/application/McpSyn039SliceTest.java` now
proves:

1. the publication projection contains the WorkGroup, intent, claim epoch,
   `finish_lane`, and exact `{summary: ...}` arguments;
2. invoking that exact projected action succeeds through the real MCP handler;
3. the resulting immutable snapshot is `PUBLISHED` and the reviewer’s
   coordination projection contains the snapshot ID;
4. the existing review fixture still rejects a wrong snapshot and grant replay;
5. existing coordination fixtures continue to enforce wrong-reviewer,
   single-use, epoch, invalid-state, and unresolved-validation fail-closed
   behavior.

`workspace/src/test/java/org/synesis/workspace/agent/AgentWorkflowReducerTest.java`
locks the transport-neutral projection contract.

The deterministic fixture also exposed a separate later observation: after
the owner’s successful integration, a reviewer `get_next_action` may return
`coordination_intent_required` because the completed owner intent has been
removed. That is not changed in this slice and remains the next lifecycle
candidate only if a valid unattended run reaches it.

## Unattended acceptance attempts

The first fresh disposable project was:

`C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0475-20260824-001`

Project ID: `83f3e9a7-a8f9-4c07-8338-72bbfe488e4d`; managed baseline commit:
`a5857e3`. The direct preflight against the repository-bundled
`cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
reported protocol `2025-06-18`, server `0.1.0-SNAPSHOT`, and `ensure_session`
`ready / isolated`.

The two-agent harness run itself did not become a valid lifecycle acceptance:

- Agent A received `workspace_not_ready` and remained at the exact
  `ensure_session` recovery action; no claim or WorkGroup was created.
- Agent B later reached `ready`, saw `IMPLEMENT` with no participants,
  WorkGroup, grants, snapshots, or pending coordination, and canceled its own
  lane. It reported baseline `a5857e3` and `pytest` `1 passed`.
- No REVIEW request, grant, snapshot, validation, integration, or closure was
  reached. Coordination status remained PASS, sequence `0`, with `TASKS=0`
  and `OWNERSHIPS=0`. Doctor was DEGRADED with five warnings and repair
  recommended.

A second fresh project was prepared at:

`C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0475-20260824-002`

Project ID: `c4e94e35-0bd3-455e-b5e6-133745bafada`; managed baseline commit:
`85af8a9`. Both new Luna High agent harness tasks remained running without a
terminal report for a bounded five-minute observation and were then stopped;
no lifecycle state was observed. This is harness/configuration evidence, not
evidence against the production publication fix. No production repository
files were modified by either acceptance attempt.

## Verification

PASS:

- `:workspace:test --tests org.synesis.workspace.agent.AgentWorkflowReducerTest --tests org.synesis.workspace.Syn037CompletionValidationTest`
- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`
- `:workspace:javadoc :mcp:javadoc :coordination:javadoc`
- `scripts/agent-validate-deferred.ps1`
- `scripts/agent-validate-fixtures.ps1`
- `go vet ./...` from `bootstrap`
- `git diff --check`

Separate verification failures:

- `go test -count=1 ./...` from `bootstrap` retains the three known migration
  failures: `TestBootstrapInstallUpdateRollbackDoctorAndUninstall`,
  `TestLegacyLayoutMigration`, and
  `TestPreparedVersionedUpdateRetainsPayloadAndRollsBack`, each reporting
  `update migrations not prepared`.
- Root `check` reaches the test phase but reproduces the known
  `McpServerTest` Git subprocess startup stall. It was stopped without a
  timeout increase or behavior change.
- Doctor remains `DEGRADED` with reconciliation recommended; its warnings
  were not causal to the deterministic publication mismatch and were not
  changed.

## Boundary and next action

SYN-039 remains ACTIVE. No SYN-040 was created and nothing was pushed. The
exact next action is to obtain a valid two-agent unattended run using the
current bundled MCP and project pin, then preserve the first lifecycle result
after the owner executes the now-correct projected `finish_lane` action. If
that run reaches reviewer admission and returns `coordination_intent_required`
after integration, implement only that next evidence-backed transition.
