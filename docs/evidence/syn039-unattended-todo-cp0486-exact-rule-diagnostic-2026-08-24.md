# SYN-039 unattended Todo CP-0486 exact-rule diagnostic

## Classification

This bounded diagnostic did not prove a new Synesis lifecycle defect. The
agent-facing contract clarification was present in the generated `AGENTS.md`
and provider manual. Agent A obeyed the rule and did not call `finish_lane`
while `get_next_action` reported ordinary implementation work. Agent B made
an unprojected integration-check request against its own incomplete isolated
worktree; Synesis correctly returned `TESTS_FAILED` and stopped with
`request_human_help`. No exact projected lifecycle action failed.

The diagnostic therefore remains agent-compliance evidence. No production
lifecycle behavior was changed because of this run.

## Harness and preflight

- Fresh Git + Synesis project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0486-001`.
- External harness and JSONL logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0486-001`.
- Project ID: `733e5cfe-c75c-464d-9d38-ad3f81748d81`.
- Seed commit: `51b4edd Seed unattended Todo acceptance fixture`.
- Managed Synesis baseline: `3438b040e03eb65d421d8ffd99bd26c2a34864fa`.
- Provider setup commit: `5b658b1 Record provider setup for acceptance fixture`.
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`.
- MCP SHA-256:
  `8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`.
- Bundle metadata: `SYNESIS_VERSION=0.1.0-dev.local`,
  `RECORD_FORMAT=SDR2`, `RECONCILIATION_PROTOCOL=PRP1`,
  `BUILD_COMMIT=UNKNOWN`, protocol preflight `2025-06-18`, and the existing
  ten-tool MCP catalog.
- Both Codex commands used `-m gpt-5.6-luna`, the same `-C` project root, and
  an explicit per-agent MCP override to the current bundle through the
  external harness. No messages or lifecycle transitions were relayed by the
  harness.

Both sessions independently reached `ready / isolated`:

| agent | session / worktree                             | participant                                | intent, epoch, claim                                                  |
|-------|------------------------------------------------|--------------------------------------------|-----------------------------------------------------------------------|
| A     | `session-3792dadf-9698-45d8-a176-eebf37f1b254` | `agt_c7e999c6-d8d6-3110-b241-dae3597dcd0a` | `b9c6c975-8da3-34bc-a9e9-40ca3400b021`, epoch 1, exact `todo.py`      |
| B     | `session-db80157c-0a31-42ff-8efd-06c2f55c3182` | `agt_ad76b611-f888-376e-95ec-f5d11aa4eff5` | `de9ffffc-31ac-3e73-91a8-3c8fa81f0e07`, epoch 1, exact `test_todo.py` |

The sessions converged on one WorkGroup:
`9527b8ec-0971-3f33-995c-ac0833d506c7`.

## Exact trace

Agent A's first completed `get_next_action` projection was:

```json
{
  "status": "ready",
  "reason": "validation_required",
  "nextAction": "request_coordination",
  "recommendedTool": "request_coordination",
  "arguments": {
    "kind": "work_group_join",
    "payload": {
      "intentId": "de9ffffc-31ac-3e73-91a8-3c8fa81f0e07",
      "proposal": "Review the immutable snapshot for this work group",
      "workGroupId": "9527b8ec-0971-3f33-995c-ac0833d506c7"
    }
  },
  "actionId": "fece361f-cf15-3768-aa89-edb5ab4ec337"
}
```

A executed that exact `request_coordination` action successfully. The
resulting pending REVIEW request was
`4a38f4c4-3f7b-40a7-9348-4a9bfb553f73`, with requester A, target B, and
conflicting intent B. After visible implementation work, repeated
`get_next_action` calls returned the same exact projection and A repeated the
same exact action, creating pending requests
`1fcbfca5-5024-4688-9016-d11602a5adae` and
`f3c48ff0-f3d1-4d75-b976-4676e126dd39`. None of those exact calls failed, but
no owner response was reached because B had already stopped.

Agent A then:

- modified only `todo.py` to add `TodoList.complete(title)`;
- passed focused behavior checks, `py_compile`, and `git diff --check`;
- explicitly reported that Synesis did not project `finish_lane`, so it did
  not invoke it.

Agent B made a different, unprojected choice. Its first
`get_next_action` call supplied `{"integrationCheck":{}}` even though no
concrete lifecycle action had been projected. Synesis returned:

```json
{
  "status": "blocked",
  "reason": "integration_conflict",
  "nextAction": "request_human_help",
  "result": {
    "failures": ["TESTS_FAILED"],
    "accepted": false,
    "actions": ["Run the configured project test command and resolve failures"]
  }
}
```

B's isolated worktree correctly did not contain A's unintegrated `todo.py`
change. B created only `test_todo.py`; its local pytest result was 2 passed and
1 failed because `TodoList.complete` was absent in that isolated worktree.
This was not validation or integration of an accepted snapshot, so it does
not reproduce the earlier false `TESTS_FAILED` integration defect.

## Terminal state

The run reached no grant, snapshot, validation decision, integration result,
or WorkGroup closure:

- WorkGroup `9527b8ec-0971-3f33-995c-ac0833d506c7` remained `ACTIVE`.
- Claims remained disjoint at epoch 1 (`todo.py` versus `test_todo.py`).
- REVIEW requests were pending; grants and snapshots were absent.
- The control checkout remained at provider setup commit `5b658b1`; no Todo
  implementation or tests were integrated into control.
- No second ordinary unattended acceptance was run because the diagnostic did
  not complete.

Final fixture Doctor output was `DEGRADED` with six warnings and no errors:

1. two `stale_session_lease` findings;
2. `command_namespace_reconciliation_required`;
3. `command_capacity_or_retention`;
4. two `provider_migration_required` findings.

The Doctor result recommended reconciliation/repair and performed no
mutation. These warnings were not shown to cause the CP-0486 stop.

## CP-0485 stale-recovery trace resolved by the contract clarification

The preceding reviewer recovery trace was also completed before this run.
The reviewer's binding remained based on `5286a30`, while the control checkout
had advanced to `166228f5` after the owner's unprojected finish. The reviewer
worktree was still dirty with its untracked `test_todo.py` and `__pycache__`.
Binding generation was stale and the worktree was not clean, so the existing
fail-closed path raised `WORKSPACE_STALE_DIRTY`; generic session recovery
reported `internal_failure` rather than silently rebinding or discarding work.
The lease/process evidence did not show a Synesis process-loss defect. The
smallest corrective action was the agent-facing contract clarification now
covered by `ProviderManualServiceTest` and `ProjectApplicationServiceTest`:
ordinary `IMPLEMENT` without a concrete projected tool is not permission to
call `finish_lane` or another lifecycle action.

## Verification

- Focused workspace/MCP tests, including projection and contract tests: PASS.
- `:workspace:javadoc`: PASS.
- `scripts/agent-validate-fixtures.ps1`: PASS.
- `scripts/agent-doctor.ps1`: PASS with its existing personal-absolute-path
  documentation warning.
- `git diff --check`: PASS with existing CRLF normalization warnings only.
- The recurring root Git subprocess stall, bootstrap migration failures, and
  unrelated Doctor warnings remain separately classified.
