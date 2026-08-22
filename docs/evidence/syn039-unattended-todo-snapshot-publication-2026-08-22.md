# SYN-039 snapshot-publication slice evidence — 2026-08-22

## Scope

This slice fixes the producer-side next-action gap after a reviewer consumes a
targeted single-use REVIEW grant. The owner now receives an explicit existing
`finish_lane` action with the workgroup, intent, and claim epoch. The action is
fail-closed to the owner intent and is suppressed once a matching immutable
snapshot already exists. The raw MCP surface remains the existing ten tools;
no new role, daemon, orchestrator, or side channel was added.

## Deterministic regression evidence

`mcp/src/test/java/org/synesis/mcp/application/McpSyn039SliceTest.java` now
reproduces the prior post-grant state and proves that, after the reviewer
consumes the grant, the owner projection contains:

- `snapshot_publication_required`;
- `finish_lane` as the next protocol action;
- the exact WorkGroup ID and claim epoch;
- reviewer visibility of the immutable snapshot after publication.

`workspace/src/test/java/org/synesis/workspace/agent/AgentWorkflowReducerTest.java`
proves that this existing completion tool is rendered as a `PUBLISH` action
with `finish_lane` as the executable recommendation. Existing coordination
coverage remains authoritative for wrong reviewer, stale intent/epoch,
single-use replay, duplicate grant issuance, ACCEPT, and REJECT behavior.

Passed focused verification:

```text
:workspace:test --tests org.synesis.workspace.agent.AgentWorkflowReducerTest \
  --tests org.synesis.workspace.AgentNextActionServiceTest
:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest
:coordination:test --tests org.synesis.coordination.collaboration.WorkGroupServiceTest \
  --tests org.synesis.coordination.collaboration.WorkIntentServiceTest \
  --tests org.synesis.coordination.collaboration.ReviewValidationPayloadTest
:cli:installDist
```

The combined focused run completed `BUILD SUCCESSFUL`. Strict Javadocs for
coordination, workspace, MCP, and CLI also completed successfully after adding
the missing public API documentation for the existing review validation
request/service.

## Unattended Todo reruns

Three fresh ordinary Codex-session reruns were launched with separate MCP
connections and no manual relay, file assignment, review resolution, snapshot
publication, integration, or cleanup:

1. `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-publication-20260822-112230-lf`
   reached the existing owner-admission request. Agent A acknowledged the
   inbox item and attempted `finish_lane` before accepting the REVIEW request;
   Synesis correctly returned `task_not_ready`.
2. `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-publication-20260822-112544-lf2`
   reached `owner_request_pending`; the reviewer request was durable, but no
   owner acceptance or grant followed.
3. `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-publication-20260822-112743-lf3`
   is the final rerun. Agent A ran the Todo tests successfully and
   `get_next_action` exposed `owner_request_pending`,
   `recommendedTool=respond_coordination`, and the exact pending REVIEW
   request ID `4998d76b-fe4b-4d08-b627-103ed21d4122`. Agent B submitted the
   projected review request and stopped while admission remained pending.

For the final fixture, project `6238e7b7-3954-4959-a8f9-c55458c20c9b`,
WorkGroup `d2cce76f-ecd1-324f-9a56-6d47e8854b88`, owner participant
`agt_33478e02-7e36-328e-a6a5-f6ae2cca9ce7`, and reviewer participant
`agt_cae39e39-a005-3e62-8929-c9f036368bda` remained ACTIVE with no REVIEW grant
and no snapshot. The raw JSONL and stderr files remain in that disposable
fixture. This rerun therefore did not reach the new post-grant publication
slice; it exposes the next provider-side blocker: the normal owner session is
not executing the already-projected REVIEW acceptance action autonomously.

## Full verification and stall evidence

The serialized root command was attempted:

```text
gradlew.bat check --dependency-verification=strict --no-daemon --no-parallel --max-workers=1 --console=plain
```

It did not complete. In one run it stalled in
`WorkspaceCliTest.setUp` while `ProjectApplicationService.init` performed a
Git command. A focused `:mcp:test --tests
org.synesis.mcp.application.McpServerTest` reproduced the recurring stall in
`McpServerTest.collaborationDiscoveryReturnsJsonSafeParticipantsIntentsAndClaims`
at `McpServerTest.java:181`.

At the captured time, worker PID `24912` had its Test worker in
`TIMED_WAITING` at:

```text
McpServerTest.collaborationDiscoveryReturnsJsonSafeParticipantsIntentsAndClaims
 -> McpProtocolHandler.handleToolsCall
 -> AgentNextActionService.getNextAction
 -> ProjectApplicationService.locate
 -> RepositoryPrivateStateService.ensure
 -> AdministrativeStateLocator.resolveGitCommonDirectory
 -> GitProcessRunner.runResult
 -> ProcessCommandRunner.execute
```

The associated Git child process was present while the Java worker waited;
the existing bounded subprocess hardening was preserved and no larger timeout
was introduced. The focused MCP test was stopped after the thread/process
evidence was captured. Root `check` is therefore incomplete, not green.

## Other verification

- deferred validator: PASS;
- fixture validator: PASS;
- repository doctor: DEGRADED with an existing personal-absolute-path
  documentation warning;
- `bootstrap/go test -count=1 ./...`: PASS;
- `bootstrap/go vet ./...`: PASS;
- `git diff --check`: PASS;
- strict Javadocs: PASS.

SYN-039 remains ACTIVE. No SYN-040 was created and nothing was pushed.
