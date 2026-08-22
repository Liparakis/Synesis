# SYN-039 owner REVIEW-acceptance slice evidence — 2026-08-22

## Scope

This slice fixes the owner-side next-action projection after a reviewer has
submitted a typed `REVIEW` coordination request. The existing
`respond_coordination` tool is now projected with the exact strict
`coordination_response` payload instead of an empty argument map. The
projection also carries the request, WorkGroup, intent, participant, and claim
epoch context needed for an agent to select the action without guessing.

No new MCP tool, role, orchestrator, side channel, ownership transfer, or
authorization bypass was added. The existing collaboration service remains the
fail-closed authority for target-participant and request-state checks.

## Deterministic regression evidence

`mcp/src/test/java/org/synesis/mcp/application/McpSyn039SliceTest.java` now
proves that the owner projection contains:

- `owner_request_pending` and `respond_coordination`;
- the exact pending REVIEW request ID;
- the conflicting intent, WorkGroup ID, and claim epoch;
- `workflow.recommendedTool=respond_coordination`;
- a strict `coordination_response` payload with the exact request ID and
  `ACCEPTED` status;
- fail-closed rejection of a wrong request before the valid response;
- advancement of the valid response into the existing review-grant flow.

Existing coordination tests continue to cover wrong participants, stale
intent/epoch values, single-use replay, duplicate grant issuance, and
ACCEPT/REJECT validation decisions.

## Verification

Passed:

```text
:coordination:test --tests WorkGroupServiceTest --tests WorkIntentServiceTest
  --tests ReviewValidationPayloadTest
:workspace:test --tests AgentNextActionServiceTest
  --tests AgentWorkflowReducerTest
  --tests IntegrationCompatibilityServiceTest
:mcp:test --tests McpSyn039SliceTest
:cli:installDist
:coordination:javadoc :workspace:javadoc :mcp:javadoc :cli:javadoc
scripts/agent-validate-deferred.ps1
scripts/agent-validate-fixtures.ps1
go -C bootstrap test -count=1 ./...
go -C bootstrap vet ./...
git diff --check
```

The serialized root `check` remains incomplete. It reached `:cli:test` and
reproduced the bounded Git startup stall in `WorkspaceCliTest.setUp:74`:

```text
WorkspaceCliTest.setUp
 -> ProjectApplicationService.init
 -> ManagedBaselineTransactionService.prepare
 -> GitProcessRunner
 -> ProcessCommandRunner.execute:81
```

At capture, Gradle test worker PID `29372` was waiting in
`ProcessCommandRunner.execute` and child `git.exe` PID `22824` was present.
The existing subprocess hardening was preserved and no timeout was enlarged.
The previously recorded focused `McpServerTest.java:181` stall remains the
same isolated infrastructure blocker.

## Unattended Todo rerun

The app-managed two-agent rerun used the fresh disposable fixture
`C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0470-20260822-115600`
with no manual message relay or protocol intervention. It did not reach the
owner REVIEW-acceptance slice: Agent A received the existing typed
`overlapping_claim` blocker during initial ownership admission because another
active participant already held `todo.py` and `test_todo.py`. Agent A's final
state was `coordination_intent_required`; tests and files were untouched.
Agent B did not produce a terminal result before the bounded observation ended
and was then closed. This is a harness/setup failure, not evidence against the
new projection, so no end-to-end SYN-039 pass is claimed.

SYN-039 remains ACTIVE. WorkGroup closure, rejection routing, cleanup, and
Doctor health remain unverified. No SYN-040 was created and nothing was pushed.
