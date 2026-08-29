# SYN-041 bounded terminal-session seal — 2026-08-28

Status: implementation slice complete; SYN-041 remains ACTIVE. SYN-039 remains
CLOSED / DONE / ACCEPTED. No real Codex experiment, provider migration, push,
tag, release, or new MCP tool was performed.

## Result

The first bounded implementation adds explicit exact-session terminal intent
to the existing `finish_lane` surface. The ordinary call remains lane-only.
With `terminalSession=true`, lawful lane completion is followed by an
append-locked, server-validated exact-session seal. A successful seal appends
`PROVIDER_SESSION_TERMINALIZED` with the session ID, provider, derived
participant, bounded reason, and the immediately preceding event-log
revision. The event is the authoritative monotonic fence; binding and lease
markers are denormalized repairable markers.

## Exact authority model and predicate

The implementation uses the existing provider binding as the narrowest
session/connection authority object: provider fingerprint selects the exact
durable binding, its session ID maps to the existing participant handle, and
the project event log is the serialized authority history. No generalized
identity model was introduced.

Under `ProjectAppendLock`, the seal validates the durable project/provider/
fingerprint/session binding and refuses when any exact-session authority is
present: active intent or claim, pending coordination dependency, available or
unvalidated consumed review grant, review-pending/rejected/unresolved
snapshot, prepared mutation authority, pending capability dependency, blocking
worktree command, or a participant state that remains recoverable. The
predicate is exact-session scoped and fail-closed on unreadable command state.
There is no separate durable wake record in the current model; wake/next
action admission is fenced by the same binding/event marker and terminal
response path.

## Fence and lifecycle semantics

`finish_lane` carries the optional boolean `terminalSession`, defaulting to
false. Lane completion and session sealing remain separate. A blocked seal
does not undo lawful lane completion and returns `SESSION_TERMINATION_BLOCKED`
with bounded categories. A successful seal returns `SESSION_TERMINATED`, the
durable event sequence, and stable result fields. Replaying the same completed
request returns the same event sequence without appending a second fence.

After the fence, the exact session is rejected by ensure/rebind, the normal
authority resolver, review resolver, next-action polling, heartbeat,
announcement, coordination request/response, continuation, and lane-grant
consumption. A cleanly detached ordinary session still gets a fresh binding
generation; terminal sessions do not. A different provider session remains
eligible under existing rules.

Review-pending and rejected-snapshot continuation remain blockers, so SYN-039
review routing and S1-rejected-to-S2 continuation are not bypassed. Existing
no-change completion remains valid and can opt into the seal after its lane
authority is released.

## Lease, transport, and Doctor semantics

The minimum lease addition is `TERMINAL_AUTHORITY_CONFIRMED` plus derived
`TERMINAL_DISCONNECTED`. A clean EOF still writes `CLOSED_CLEANLY`. An
unexpected loss before a terminal fence retains the existing ACTIVE,
SUSPECTED_STALE, AMBIGUOUS, or RECOVERY_ELIGIBLE behavior. If the fence was
already durable, missing-process evidence derives `TERMINAL_DISCONNECTED`:
historical, non-recovery-eligible, and still distinguishable from clean EOF.
Doctor continues to warn for stale/recovery/ambiguous authority but does not
emit actionable `stale_session_lease` for terminal-disconnect history.

## Verification evidence

All commands used `TEMP=C:\\t` and `TMP=C:\\t`.

Focused bounded implementation and regression set:

```text
./gradlew.bat :workspace:test --tests ProviderSessionTerminalizationServiceTest
  --tests TerminalLeaseStateTest --tests AgentNextActionServiceTest
  :coordination:test --tests WorkIntentServiceTest --tests WorkGroupServiceTest
  :mcp-contract:test --tests McpToolCatalogTest
  :mcp:test --tests McpSyn039RejectedContinuationTest
  --tests McpSyn039NoChangeCompletionTest --max-workers=1
```

Result: BUILD SUCCESSFUL. The serialized wake/continuation regression set
also passed: `IntegrationQueueTest`, `CodexLifecycleWaitControlTest`,
`AgentSessionServiceTest`, and `McpSyn039SliceTest`.

Synthetic terminal-disconnect acceptance:

```text
./gradlew.bat :mcp:test --tests
  McpSyn039NoChangeCompletionTest.packagedBoundaryTerminalSealClassifiesLaterAbnormalTransportAsHistory
  --max-workers=1
```

Result: BUILD SUCCESSFUL. The MCP boundary completed a no-change lane with
`terminalSession=true`, observed the durable terminal marker, simulated a
dead process, derived `TERMINAL_DISCONNECTED`, and confirmed Doctor emitted
no `STALE_SESSION_LEASE`. Existing `StaleAndGraceTest` remains the negative
control for an unsealed ACTIVE lease and preserves recovery eligibility.

Strict Javadocs passed:

```text
./gradlew.bat :coordination:javadoc :workspace:javadoc :mcp-contract:javadoc :mcp:javadoc --max-workers=1
```

Distribution passed:

```text
./gradlew.bat :cli:platformBundle :cli:bundleSmokeTest --max-workers=1
```

The bundle assembled and the extracted platform smoke test passed. The
official-bundle synthetic smoke is paired with the MCP-boundary lifecycle
test above; no real provider run was used.

The first parallel full-module attempt was not accepted as evidence because
process-heavy tests timed out under concurrent execution. The affected cases
passed individually, and the authoritative bounded suites passed serialized
with `--max-workers=1`. `git diff --check` passed. The deferred-register
validator and final checkpoint are recorded after this file.

## File and scope accounting

Terminal-slice production files changed:

```text
coordination/src/main/java/org/synesis/coordination/application/CoordinationService.java
coordination/src/main/java/org/synesis/coordination/application/WorkGroupService.java
coordination/src/main/java/org/synesis/coordination/application/WorkIntentService.java
coordination/src/main/java/org/synesis/coordination/domain/collaboration/CollaborationProjection.java
coordination/src/main/java/org/synesis/coordination/domain/collaboration/ProviderSessionTerminalPayload.java
coordination/src/main/java/org/synesis/coordination/domain/prediction/PredictionEventType.java
coordination/src/main/java/org/synesis/coordination/domain/prediction/PredictionProjection.java
mcp-contract/src/main/java/org/synesis/mcp/contract/McpToolCatalog.java
mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java
workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java
workspace/src/main/java/org/synesis/workspace/application/agent/AgentSessionService.java
workspace/src/main/java/org/synesis/workspace/application/agent/AgentTaskCompletionService.java
workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java
workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionTerminalizationService.java
workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java
workspace/src/main/java/org/synesis/workspace/lifecycle/command/ProjectCommandStore.java
workspace/src/main/java/org/synesis/workspace/lifecycle/lease/SessionLeaseService.java
workspace/src/main/java/org/synesis/workspace/lifecycle/lease/SessionLeaseState.java
```

Terminal-slice test files changed:

```text
mcp-contract/src/test/java/org/synesis/mcp/contract/McpToolCatalogTest.java
mcp/src/test/java/org/synesis/mcp/application/McpSyn039NoChangeCompletionTest.java
workspace/src/test/java/org/synesis/workspace/application/provider/ProviderSessionTerminalizationServiceTest.java
workspace/src/test/java/org/synesis/workspace/lifecycle/lease/TerminalLeaseStateTest.java
```

The five preserved lifecycle files were not changed by this slice:
`GitProcessRunner.java`, `ProcessCommandRunner.java`,
`RepositoryPortabilityService.java`, `ProcessCommandRunnerTest.java`, and
`RepositoryPortabilityServiceTest.java`. Existing unrelated dirty docs and
checkpoint artifacts were preserved. No generalized identity architecture,
new task/milestone, provider migration, or protocol expansion was added.
