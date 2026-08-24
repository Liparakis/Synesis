# SYN-039 unattended Todo acceptance — CP-0520 ordinary completed-lane run

Date: 2026-08-24

This was a fresh ordinary acceptance after CP-0519. The agents received only
their complementary visible coding prompts. The harness retained unfinished
Codex sessions, but stopped resuming a session once its original Synesis lane
was reported `COMPLETED`; it did not create a replacement intent, relay
messages, accept requests, consume grants, publish snapshots, validate work,
or trigger integration.

## Repository and harness

- Repository HEAD before the run: `04d6ad5`, branch `master`, clean, 87 local
  commits ahead of `origin/master`; nothing was pushed.
- Fresh project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0520-001`
- Harness and raw JSONL:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0520-001`
- Seed commit: `16b94ab` (`seed Todo ordinary acceptance project`)
- Project ID: `befa0cd2-374c-4e6a-83e8-efc3e895a9f9`
- Current bundled MCP:
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`; startup commit: `bc334ac`; SHA-256:
  `81B64BB6C12006C19F335AD4F850B14196BF14A113BB28DBE81D7BA5164D9864`
- Both startup traces report provider `codex`, the same project root, and
  distinct connection IDs:
  `syn039-ordinary-cp0520-agent-a` and
  `syn039-ordinary-cp0520-agent-b`.
- Codex sessions: A
  `01a0350d-476c-7ff1-ba2c-edcbb796e95a`; B
  `01a0350d-474b-70f0-b9fa-de671491cfce`.

## Coordination state

- WorkGroup:
  `5c1609bd-f88d-36e5-845b-0f07677e9ffe`, version 1, final state `ACTIVE`.
- Agent A:
  `agt_2d432669-7a62-32ee-9bed-4859f163fbe3`, intent
  `24585451-d44d-33a0-a38f-eabd1128adb5`, claim `PATH_EXACT todo.py`, epoch 1;
  final state `COMPLETED`.
- Agent B:
  `agt_cc92368e-f067-3c89-b19d-6449efe6cd74`, intent
  `835e140b-2ee9-3acd-97b2-9018af7f5a97`, claim `PATH_EXACT test_todo.py`,
  epoch 1; final state `ACTIVE`.
- Accepted REVIEW requests:
  `036915b8-30d9-4d57-9902-e272a06d3f97` and
  `76776863-3ae8-46b9-bc74-9fa892ddaace`.
- REVIEW grants: `58d46ccd-eb1a-3a7b-b780-48794fce4c1a` targeted B for A's
  intent, epoch 1, single-use, and consumed by B;
  `22bc7d10-0337-31c9-9155-6de7f0130b73` targeted A for B's intent, epoch 1,
  single-use, and remained pending for A.

## Projection-to-action trace

1. Both agents first received `RECOVER` with the exact
   `ensure_session({})` action, then established isolated sessions with
   disjoint claims. Their first normal projection was `IMPLEMENT` with no
   executable lifecycle tool, so they read and changed only their visible
   assigned files.
2. B's first REVIEW admission attempt omitted `targetParticipant` and was
   rejected fail-closed with `COORDINATION_FIELD_REQUIRED:targetParticipant`.
   B later executed the complete projected request and received `CONSUMED`.
   This is agent argument compliance evidence, not a production failure.
3. A's projected publication was:
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   A executed those exact arguments successfully. The result reported
   `snapshotState=PUBLISHED`, `integrationState=integrated`, and A's state
   `COMPLETED`.
4. The immutable snapshot was
   `snap_41f8664537c23fe67293f8e08f740fa6`, commit
   `528dcc79103a5e445dd9e6ba49806bb9c536c5d2`, base commit
   `ad7166be00523c4b075a2b2fb200cfde5c5b034e`, changed path `todo.py`.
5. `finish_lane` returned the next REVIEW admission projection for B's
   intent. A executed the projected `request_coordination` with kind
   `work_group_join`, the exact WorkGroup ID, intent ID, and proposal. A then
   ended its Codex turn without polling `get_next_action` again.
6. B consumed the REVIEW grant for A, ran pytest and read the immutable
   snapshot, and submitted the structured ACCEPT decision. B also accepted
   A's reciprocal REVIEW request. B's subsequent exact projections were
   `WAIT` with `recommendedTool=get_next_action` and arguments `{}`;
   `nextProtocolKind=review_grant_consumption` identified grant
   `22bc7d10-0337-31c9-9155-6de7f0130b73` targeted at A. B correctly polled
   and performed no unauthorized lifecycle mutation.

There was therefore no unchanged projected lifecycle action that failed and
no valid active participant for which Synesis projected no usable action. The
first stop was the ordinary agent/session ending after A completed its own
lane, before A consumed the reciprocal REVIEW grant. The corrected harness
did not resume that completed lane as a new coding intent, so B remained in
the protocol's correct WAIT state.

## Final project and diagnostics

- Control checkout: clean at `b0db566` (`Synesis immutable lane snapshot`).
- Control checkout validation: `python -B -m pytest -q` → `3 passed`.
- Control checkout `git diff --check`: pass.
- No B snapshot, second validation, final integration, or terminal WorkGroup
  closure occurred.
- Fixture Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  `RECONCILIATION_RECOMMENDED=true`, `REPAIR_AVAILABLE=true`, and
  `MUTATIONS_PERFORMED=0`.

## Classification and next action

Classification: ordinary Codex session engagement/projection compliance,
not a proven Synesis production defect. The protocol exposed the required
next review-grant consumption path and the reviewer correctly waited for the
target participant. Do not change review, grant, snapshot, validation,
integration, ownership, or cleanup production code from this run.

Raw projection/action evidence is retained in the harness JSONL and MCP
startup traces under the harness path above. The next bounded experiment
should retain a completed participant only long enough to follow any already
projected review action, without announcing a new coding intent; if that
diagnostic closes cleanly, rerun the ordinary acceptance unchanged.
