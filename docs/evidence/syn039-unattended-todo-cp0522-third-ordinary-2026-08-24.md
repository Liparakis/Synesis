# SYN-039 third ordinary Todo acceptance — CP-0522

Date: 2026-08-24

This was a fresh ordinary run with only the two complementary coding prompts.
No lifecycle instructions, manual coordination, request acceptance, snapshot
publication, validation, or integration was performed by the harness.

## Preflight and project

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0522-006`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0522-006`
- Project ID: `a7163c0d-1946-45d1-91e2-aa0efa82875d`
- Seed commit: `a1b13f5`; managed baseline: `17b214c`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP startup evidence: version `0.1.0-SNAPSHOT`, commit `bc334ac`, provider
  `codex`, the same project root, and connection instances
  `syn039-ordinary-cp0522-agent-a` / `syn039-ordinary-cp0522-agent-b`.
  Both connections completed `tools/list` and exposed the current ten-tool
  catalog.
- Session A: `01a03554-e78f-7ad2-8c71-99c4fd1543c2`; participant
  `agt_121f9580-bafc-3b64-8d3a-2ccfc034bc76`.
- Session B: `01a03554-e854-7a20-a561-772869812bc9`; participant
  `agt_eeb08370-2f5a-3304-9f24-4f73f91d1856`.
- Both sessions independently executed the projected `ensure_session` and
  reached `ready` / `isolated` with distinct worktrees.

## Coordination state

- WorkGroup: `e769b143-f9b0-337f-b06a-9eb1603c8cc9`, final `ACTIVE`, version 1.
- A intent: `c0b6ae6c-d812-3213-95bc-0234391fa8b6`, claim
  `PATH_EXACT todo.py`, epoch 1.
- B intent: `0e9bd769-ca48-3e33-b0da-aee83178b57c`, claim
  `PATH_EXACT test_todo.py`, epoch 1.
- REVIEW requests accepted:
  `6a02dc14-8de7-4c04-96fe-1f87fc6e7f65` (A to B) and
  `ca5be11c-9b9f-43be-a69a-91ed4751dad1` (B to A).
- REVIEW grants:
  `80757785-384a-3b00-b17d-9d205c1b0159` targeted A and was consumed;
  `4ced56de-ae1e-3d6d-b665-fe74c8e7764b` targeted B and remained pending.
  Both were single-use epoch-1 grants for this WorkGroup.

## Projection/action trace

1. Both agents first received `get_next_action` with
   `RECOVER → ensure_session({})` and executed the exact projected tool.
   Each then received ordinary `IMPLEMENT` with no executable lifecycle
   action and performed its visible assigned work.
2. After A implemented `TodoList.complete` and its visible tests passed 2/2,
   A received:

   ```json
   {
     "recommendedTool": "request_coordination",
     "arguments": {
       "kind": "work_group_join",
       "payload": {
         "intentId": "0e9bd769-ca48-3e33-b0da-aee83178b57c",
         "workGroupId": "e769b143-f9b0-337f-b06a-9eb1603c8cc9",
         "proposal": "Review the immutable snapshot for this work group"
       }
     }
   }
   ```

   A executed those exact arguments successfully and created request
   `6a02dc14-8de7-4c04-96fe-1f87fc6e7f65`.
3. B received the exact projected owner response for that request and
   executed `respond_coordination` successfully. A then received the exact
   single-use grant-consumption projection for grant
   `80757785-384a-3b00-b17d-9d205c1b0159` and consumed it successfully.
4. A next received `WAIT` with
   `recommendedTool=get_next_action` and arguments `{}` while the protocol
   state was `REVIEW_GRANT_PENDING` / `review_grant_consumption`. Instead of
   polling, A selected ordinary `read_file` and `run_command` actions. Those
   unprojected reads failed closed with `workspace_stale` after the review
   workspace boundary. This is the first concrete action/projection
   compliance stop in this run.
5. A later received a `review_decision` / `review_validation` projection. It
   first submitted an unprojected response containing
   `failedAcceptanceTests`, which was correctly denied with
   `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:failedAcceptanceTests`. It then
   submitted the allowed structured rejection successfully:

   ```text
   result=REJECTED
   snapshot=snap_012bbfe1bc5f22b8e69d51e9638b4c05
   targetIntent=c0b6ae6c-d812-3213-95bc-0234391fa8b6
   route.nextAction=ensure_session
   route.targetParticipant=agt_eeb08370-2f5a-3304-9f24-4f73f91d1856
   ```

6. B later received the exact `PUBLISH → finish_lane` projection with
   `{"summary":"Publish the completed immutable snapshot"}` and executed it
   successfully. It published and integrated its test snapshot, then
   executed the exact reciprocal REVIEW request. A accepted that request,
   but A never published its own implementation snapshot.
7. A's final attempted `ensure_session({"refresh":true,...})` was not the
   projected action and returned `internal_failure`; the durable projection
   remained `WAIT → get_next_action({})`. The harness stopped A at its
   bounded turn limit and did not create a replacement intent.

## Snapshot and final state

- Published snapshot: `snap_012bbfe1bc5f22b8e69d51e9638b4c05`.
- Snapshot commit: `754f93fa341b61fad125946e0d7f7d97ed3929f5`.
- Base commit: `17b214c02cef8feea2a28b8569a931b917e4e4d9`.
- Changed path: `test_todo.py` only.
- Snapshot was integrated into the control checkout at `ffba5f6`; control
  pytest reported `1 failed, 2 passed` because the separately claimed
  `todo.py` implementation had not been integrated.
- A's implementation remained unpublished; no validation of an A snapshot
  occurred. B's snapshot was structurally rejected by A after its completion
  test failed against the incomplete base.
- Final WorkGroup remained `ACTIVE`; A remained `ACTIVE` with the
  `todo.py` claim, B was `COMPLETED`, grant
  `4ced56de-ae1e-3d6d-b665-fe74c8e7764b` remained unresolved, and no clean
  WorkGroup closure occurred.
- Final Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings,
  reconciliation recommended, no mutations performed.

## Classification

No unchanged projected Synesis lifecycle action failed. The exact
`request_coordination`, owner `respond_coordination`, grant consumption,
review rejection, and `finish_lane` actions that were executed succeeded or
failed closed only when the agent supplied invalid/unprojected data. The
first blocker is ordinary agent/session compliance with a durable `WAIT`
projection, followed by an invalid review-response field and an unprojected
recovery attempt. This run does not justify a production lifecycle change.

The bounded CP-0522 diagnostic in the companion evidence file completed the
same protocol through terminal WorkGroup completion when the existing-session
continuation was explicitly exercised. The contrast remains evidence of the
ordinary Codex turn/projection boundary, not proof of a backend defect.

Evidence artifacts: the `agent-*-turn-*.jsonl` files and MCP traces under
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0522-006`.
