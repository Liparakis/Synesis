# SYN-039 CP-0527 REVIEW replay fix and post-fix diagnostic

Date: 2026-08-25

## Scope and decision

This slice implements only the first concrete post-CP-0526 protocol failure:
an unchanged projected REVIEW admission could fail with `INTENT_NOT_FOUND`
after the target lane had completed and released its intent. Review,
snapshot, validation, integration, cleanup, Doctor, and provider-session
behavior were not redesigned.

## Pre-fix reproduction

The prior ordinary run was retained at:

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0526-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0526-001`
- Agent trace: `logs\agent-b.jsonl`
- WorkGroup: `b6078b02-df81-379a-bb51-ac5497984ccd`
- Target intent: `e5221c2d-f0f9-3e4a-8de5-ac7f70a7fccf`

Agent B received this exact durable projection:

```json
{
  "kind": "work_group_join",
  "payload": {
    "intentId": "e5221c2d-f0f9-3e4a-8de5-ac7f70a7fccf",
    "workGroupId": "b6078b02-df81-379a-bb51-ac5497984ccd",
    "proposal": "Review the immutable snapshot for this work group"
  }
}
```

The agent submitted the unchanged `request_coordination` action. The first
submission created request `0e79ddde-ef70-4731-b32c-b3fde5f6d42f` and returned
`PENDING`. The same projected request was later delivered again after the
target lane had completed. The retry returned:

```text
status=blocked
reason=policy_denied
result.error=INTENT_NOT_FOUND
```

This was a real unchanged projected-action failure. It occurred because
`WorkIntentService.request` looked up the current collaboration intent before
checking the existing durable REVIEW request. Normal lane completion removes
the released intent from the live collaboration projection, while the durable
request remains the authority record needed for at-least-once replay.

## Narrow fix

Commit `81aa2f6` (`Make released REVIEW requests replayable`) changes only the
REVIEW request path:

1. find an existing durable request by exact requester, conflicting intent,
   and REVIEW kind;
2. return that request unchanged for replay;
3. only then resolve the live target intent when creating a new request.

The replay lookup intentionally does not require the released target
participant to remain in the live intent projection. New requests still
require the current intent and therefore remain fail-closed. The existing
ownership, participant, epoch, and request identity checks remain in force.

## Regression coverage

- `WorkIntentServiceTest.reviewRequestReplayRemainsIdempotentAfterTargetLaneReleases`
  releases the target lane, repeats the exact REVIEW request, and proves the
  same request ID is returned exactly once. A new requester against the
  released intent still fails with `INTENT_NOT_FOUND`.
- `McpSyn039SliceTest.missingGrantRequestBecomesOwnerAuthorizedReviewAdmission`
  replays the captured projected admission after release and proves it does
  not return `INTENT_NOT_FOUND` or create a second request.

The coordination regression was red before the production change and green
after it. The focused MCP regression is green after rebuilding the bundled
MCP distribution.

## Fresh post-fix diagnostic

The bounded diagnostic used a fresh Git + Synesis project and two independent
GPT-5.6 Luna High Codex sessions. Both sessions used the rebuilt bundled MCP:

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0527-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0527-001`
- Project ID: `0d3ffa5c-0279-4966-b926-45aedcc4b2ac`
- Git seed: `f05c7db`; managed baseline: `8908632b5ee90a718306aa901a25e0d806be60b3`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`; protocol: `2025-06-18`; commit: `bc334ac`
- MCP catalog: exactly 10 tools
- MCP SHA-256: `776B1AA22D4EEBE566941FCCDB0F15F544555BAC8C62DB4F1128BAC03A0D9359`

Participants and disjoint epoch-1 claims:

- Agent A: session `session-8ed76257-a7c1-4be8-b2d9-94d68f08a492`, participant
  `agt_7952ee7e-ff88-3b16-84fe-bb08518c4722`, intent/lane
  `432242bd-9fd4-357e-9e49-82eb4ea2bcda`, claim `todo.py`.
- Agent B: session `session-eeb53c0e-0aef-4b63-9474-b406d55aa3e5`, participant
  `agt_5b025ab0-37be-357f-8f66-7ce1b2f69a5f`, intent/lane
  `69a42f1a-597b-33f8-9004-0f544b0cfedd`, claim `test_todo.py`.
- WorkGroup: `ff42da2a-719f-34cb-8851-de17edb9aba8`, `ACTIVE`, version 1.

The exact post-fix action progression was:

1. The agents created one shared WorkGroup with disjoint claims.
2. REVIEW admission was projected and the exact `request_coordination`
   arguments were executed. Requests `0b28443f-8ae7-4c00-999f-de3d8c419e86`
   and `35b347c7-9a38-4429-a5d9-6edf4d1d142a` were accepted.
3. Grant `775737fa-a930-32ab-b682-2e05cd39f600` was projected with the exact
   target participant, intent, WorkGroup, and epoch and was consumed by Agent
   A. The reciprocal grant `6eb5cd9c-f949-3080-9bc1-5391a6db17cd` remained
   pending when the provider turn ended.
4. Agent B executed the projected `finish_lane`, publishing immutable snapshot
   `snap_dfdbaefcb233814fed3eead8972c7fe2` at commit
   `e77783487694cee90ecdb76176cc34b89f3ccfe1`, based on
   `8908632b5ee90a718306aa901a25e0d806be60b3`. Its response reported
   `snapshotState=PUBLISHED` and `integrationState=integrated`.
5. Agent A consumed its exact REVIEW grant and inspected B's immutable
   snapshot. The bounded pytest command correctly returned exit code 1 with
   `1 failed, 2 passed` because B's test-only snapshot was evaluated before A's
   implementation snapshot was integrated.
6. A first agent-selected review payload was rejected fail-closed because it
   included the unsupported field `failedAcceptanceTests`. A then submitted
   the structured REJECT payload with the allowed fields; Synesis returned
   `REJECTED` and routed the work to the correct implementer.
7. The reciprocal owner acceptance was later executed with the exact projected
   `respond_coordination` arguments. The provider turn ended before B consumed
   grant `6eb5cd9c-f949-3080-9bc1-5391a6db17cd` and published its final review
   snapshot.

No unchanged projected action failed in this post-fix diagnostic. The first
remaining boundary is provider/session engagement after a valid projected
continuation, not a new Synesis protocol defect. Because the bounded
diagnostic did not complete end-to-end, the conditional second ordinary
acceptance was not run.

## Terminal state and independent verification

- WorkGroup remained `ACTIVE`; no terminal closure was claimed.
- The control checkout integrated B's snapshot. Agent A's implementation
  snapshot was not integrated in this run, so final control pytest was not
  treated as an end-to-end acceptance result.
- `synesis doctor` reported `DEGRADED` with 6 warnings, 0 errors, and 0
  critical findings; cleanup and reconciliation were not recommended and the
  next action was `prepare_repair_plan`.
- The recurring root Git subprocess stall remains separate infrastructure
  evidence: the Java test worker blocks in `ProcessCommandRunner.execute` /
  `GitProcessRunner.runInternal` while `McpServerTest` establishes a session.
- Bootstrap `go test ./...` still has the three known migration failures
  reporting `update migrations not prepared`; `go vet ./...` passes.

Focused SYN-039 coordination/MCP tests, the bundled MCP rebuild, validators,
Javadocs, Go vet, and `git diff --check` passed. The full MCP test suite was
not allowed to hide the known subprocess stall and was interrupted after
capturing the existing thread evidence.

## Classification and exact next action

The released-lane REVIEW replay defect is fixed and committed. The remaining
SYN-039 acceptance gap is an ordinary provider-session continuation boundary:
run one fresh completely ordinary two-agent Todo acceptance with no
protocol-conformance instruction or manual intervention. Stop at the first
unchanged projected-action failure or state requiring progress with no usable
projection. If it reaches clean WorkGroup completion, inspect the remaining
Doctor warnings; otherwise preserve the first concrete blocker without
broadening SYN-039.
