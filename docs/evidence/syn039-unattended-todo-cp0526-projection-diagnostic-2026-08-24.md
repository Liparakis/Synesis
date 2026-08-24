# SYN-039 CP-0526 projection diagnostic

Date: 2026-08-24

## Purpose

This bounded diagnostic followed the exact projected-action rule for both
independent GPT-5.6 Luna High Codex agents. A concrete Synesis action had to be
executed with the projected arguments before another lifecycle action was
chosen. The run stopped at the first unchanged projected action that failed.

## Fresh acceptance state

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0526-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0526-001`
- Project ID: `6280519f-85e0-46ed-9322-8f1417e732cc`
- Seed commit: `425a8f1 seed Todo diagnostic acceptance`
- Managed Synesis baseline: `bb4b69b0bbde0d71e534f4a71d88fa28334deaa9`
- MCP executable: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP identity: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit `bc334ac`, ten tools
- No manual relay, request acceptance, snapshot publication, validation, or integration was performed.

Participants and claims:

- Agent A: `agt_a3cfa113-5f30-3e04-a256-861004734821`, intent
  `3fb70468-2e67-3272-bd28-e2ca87e25447`, `PATH_EXACT todo.py`, epoch 1.
- Agent B: `agt_3f249cfe-eea7-373b-9b96-6c080dbe45a1`, intent
  `43d469b5-3fa8-3be3-afc0-bb631881dd90`, `PATH_EXACT test_todo.py`, epoch 1.
- WorkGroup: `50379b12-04b2-3f3d-94d6-82bd552e323b`, `ACTIVE`.

## First concrete failure

The run reached the reciprocal review flow. Agent A executed the projected
review validation for Agent B's immutable snapshot
`snap_a936379fbe82bc12eb4b37ed1d71bb27` and returned structured `accepted`.
Agent A also accepted the reciprocal REVIEW request, and Agent B consumed the
reciprocal single-use grant. Agent A then received this executable projection:

```text
get_next_action -> status=ready, reason=snapshot_publication_required,
nextAction=finish_lane,
arguments={"summary":"Publish the completed immutable snapshot"}
```

Agent A executed those exact arguments. The result was:

```text
status=blocked, reason=task_not_ready, nextAction=retry
```

At the projection and failure point, Agent A's visible `todo.py` still
contained the stub implementation. The recovered Agent A worktree inherited
Agent B's already-integrated `test_todo.py` source change, so generic
changed-path inspection treated the sibling change as Agent A's publishable
work even though Agent A's claim was only `todo.py`. The projection was
therefore not executable for the current lane.

The later residual log activity, in which Agent A edited `todo.py` and tests
passed, occurred after the first failure and is not acceptance evidence.

## Narrow fix and verification

The production fix applies the current lane's `ResourceSelector` claims to the
existing `TaskSnapshotService.hasPublishableChanges` gate used by
`AgentNextActionService`. It preserves the existing snapshot creation
ownership check and returns no publication projection for source changes that
fall outside the current lane claims.

Regression coverage:

- `TaskIntegrationServiceTest.inheritedSiblingSourceChangeCannotAuthorizeAnotherLanePublication`
- `McpSyn039SliceTest.completedLaneProjectsReciprocalReviewAdmissionWhileSiblingRemainsActive`

The focused workspace and SYN-039 MCP tests passed, and the rebuilt bundled MCP
was produced before CP-0527.

## Terminal state after the diagnostic was stopped

The diagnostic harness was stopped immediately after the first failure. The
residual state was `WorkGroup=ACTIVE`; both participants had completed their
visible work, both REVIEW requests were accepted, both grants existed, and the
control checkout contained the trailing-run Todo changes. Because that state
was produced after the recorded first failure, it does not prove clean
completion. Doctor was `DEGRADED` with stale leases, command namespace/retention
warnings, and provider migration warnings.

Connection IDs were not emitted in the Codex JSON logs; the raw MCP stderr
startup logs contain the executable, project, process, and connection details.
