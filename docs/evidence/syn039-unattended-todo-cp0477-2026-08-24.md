# SYN-039 CP-0477 — Explicit harness preflight and unattended lifecycle rerun

Date: 2026-08-24
Task: SYN-039 — Autonomous Workgroup Completion
Checkpoint: CP-0477
Repository commit before evidence: `c7b822f`

## Harness divergence and configuration correction

The CP-0476 comparison found two different agent routes:

- One route used the stale installed `C:\Users\Liparakis\AppData\Local\Synesis\bin\synesis.cmd` with only `mcp --provider codex`; it omitted `--project` and failed readiness.
- Another route used the current repository bundle with a pinned project argument, but its disposable CP-0476 project directory had disappeared, so fail-closed readiness correctly returned `workspace_not_ready`.

The acceptance harness was corrected without changing Synesis production code:
each Codex CLI process received explicit MCP command, project, provider, and
connection-instance overrides. The normal multi-agent route was not used for
the acceptance because it did not preserve those effective settings
consistently.

## Fixture and bundled MCP

- Project root: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0477`
- Project ID: `065d8765-ab19-46c6-bcb2-f919854e95dd`
- Initial application commit: `2afa8f6` (`baseline Todo fixture`)
- Synesis managed baseline: `582852572a48ed7bc9d0e8dea1add306d8229fbf`
- Bundled executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- SHA-256: `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`
- Startup version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Protocol: `2025-06-18`
- Server version: `0.1.0-SNAPSHOT`
- Provider: `codex`
- Catalog: exactly 10 tools — `ensure_session`, `read_file`, `apply_patch`,
  `run_command`, `get_next_action`, `request_coordination`,
  `respond_coordination`, `publish_capability_implementation`, `finish_lane`,
  `cancel_lane`

## Independent preflight

Two independent explicit Codex CLI preflight processes passed before the
acceptance began:

- Agent A connection `syn039-cp0477-cli-a`: session
  `session-c1cb16c9-c085-4b3e-ae46-c4531bfed9e0`, participant
  `agt_42a6492f-7ee0-3d18-89bd-c97595bbc7e0`, isolated worktree under the
  project ID, `ensure_session(refresh=true)=ready`.
- Agent B connection `syn039-cp0477-cli-b`: session
  `session-11fa9fa2-ab71-4bf2-9221-54bb1dca1530`, participant
  `agt_eaf3a476-2ec1-346d-99a7-0440badc5a43`, isolated worktree under the
  project ID, `ensure_session(refresh=true)=ready`.

Both observed the same project root and project ID. The direct MCP control
startup independently reported the same protocol, version, commit, and tool
catalog above.

## Unattended acceptance progression

The full acceptance used separate explicit connection IDs:

- Implementer connection: `syn039-cp0477-agent-a`
- Reviewer connection: `syn039-cp0477-agent-b`

Both passed preflight and reached distinct isolated worktrees. The implementer
then established:

- Participant: `agt_6f76238a-2f94-3f73-8c35-b17a4da35a42`
- WorkGroup: `62f4a6d0-0061-3e3d-8cc5-7536b556782c`, `ACTIVE`
- Intent: `8fcbab57-9293-3321-8945-e5a5fd4af6b9`
- Claim epoch: `1`
- Claims: exact `todo.py` and `test_todo.py`
- Implementer worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\065d8765-ab19-46c6-bcb2-f919854e95dd\worktrees\session-7c4365a0-33b0-4268-a405-30437663f0d6`
- Implementation: `TodoList.complete(title)` and success/missing-title tests
- Validation command: `python -m pytest -q`
- Validation result: exit code `0`, `3 passed`

The implementer then called `finish_lane` immediately. The exact result was:

`{"status":"blocked","reason":"task_not_ready","nextAction":"retry"}`

At that point no snapshot or grant existed. This call was premature: the
existing protocol requires the review admission/grant and publication path
before the owner can complete the lane. The agent stopped after the typed
blocker as instructed.

The reviewer independently observed the same WorkGroup and received this
exact next-action projection:

- state: `REVIEW_ADMISSION_REQUIRED`
- next action: `request_coordination`
- kind: `work_group_join`
- payload WorkGroup: `62f4a6d0-0061-3e3d-8cc5-7536b556782c`
- payload intent: `8fcbab57-9293-3321-8945-e5a5fd4af6b9`
- proposal: `Review the immutable snapshot for this work group`

Instead of submitting that projected request, the reviewer attempted an inbox
acknowledgement using a non-existent item ID. Synesis correctly returned the
fail-closed result `policy_denied / INBOX_ITEM_NOT_FOUND`. The reviewer then
stopped. No review request, REVIEW grant, snapshot, validation decision,
integration, or closure was recorded.

## Final state

The final MCP coordination projection showed:

- WorkGroup `62f4a6d0-0061-3e3d-8cc5-7536b556782c`: `ACTIVE`
- Implementer participant and claims present
- Reviewer participant present only as the observing connection
- Grants: none
- Snapshots: none
- Validation: none
- Integration: none
- WorkGroup closure: not reached

The control checkout remained clean at the managed baseline. The implementer
worktree retained the uncommitted Todo changes; they were not manually copied
or integrated.

Doctor after the bounded run was `DEGRADED` with eight warnings: four stale
session leases, command namespace reconciliation, command capacity/retention,
and two provider-migration warnings. These warnings are separately classified
and were not shown to cause the lifecycle result.

## Conclusion and next action

The explicit harness/configuration divergence is confirmed and corrected for
this run. The lifecycle did not reach review because both agents made
protocol-ordering mistakes: the owner invoked `finish_lane` before review
readiness, and the reviewer ignored the projected `request_coordination`
action. This is not evidence for changing production lifecycle code.

Next action: rerun the same explicit two-agent acceptance with prompts that
require strict execution of the exact `get_next_action` tool and arguments,
without manually relaying or triggering any transition. Preserve the first
typed result after the reviewer submits the projected review request. Do not
broaden SYN-039, push, or create SYN-040.
