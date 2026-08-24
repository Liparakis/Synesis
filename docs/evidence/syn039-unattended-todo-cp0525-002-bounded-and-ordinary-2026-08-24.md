# SYN-039 CP-0525 bounded exact-projection and ordinary acceptance

Date: 2026-08-24
Status: PARTIAL; the bounded diagnostic completed the existing review,
snapshot, validation, integration, and WorkGroup closure path. The required
ordinary acceptance reached shared coordination and integrated both lanes but
stopped with the WorkGroup ACTIVE after the agent session ended at a valid
continuation boundary. No unchanged projected Synesis action failed and no
new production defect is proven.

## Common harness and MCP evidence

- Current bundled MCP:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `81B64BB6C12006C19F335AD4F850B14196BF14A113BB28DBE81D7BA5164D9864`
- Startup identity in both runs: version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, provider `codex`, and the expected tool catalog handshake.
- Both runs used two independent `gpt-5.6-luna` Codex processes with high
  reasoning effort, explicit project roots, and distinct connection pins.
- Raw JSONL, prompts, launchers, and MCP traces are preserved under:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0525-002`
  and
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0525-002`.
- Neither harness relayed messages, accepted requests, consumed grants,
  published snapshots, or triggered lifecycle transitions.

## Bounded exact-projection diagnostic

The agents received the explicit rule to execute every concrete
`get_next_action` projection with unchanged tool arguments. Ordinary
`IMPLEMENT` projections permitted only visible repository work.

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0525-002`
- Project ID: `87a708f7-6462-48fd-a9f2-661c91911501`
- Seed commit: `259ef9f`
- Managed baseline: `06430c6`
- Sessions/worktrees:
  - A: `session-cd692781-d83d-4f87-98d0-279bdda929d1`, participant
    `agt_1abd04e8-65cb-3b10-83c2-d7110e664350`
  - B: `session-66e3ff85-3981-44a1-96ec-9807506c1772`, participant
    `agt_9e3efd10-b218-3418-b884-05a48c2dc942`
- Intents and claims, both epoch 1:
  - A intent `157a36a3-3788-3537-a094-64dd93b22dd5`, exact claim `todo.py`
  - B intent `5670ad10-d666-3151-bf46-63361ba7cc3d`, exact claim
    `test_todo.py`
- WorkGroup: `52ceb172-4e63-332b-ac6a-a5d932acd03d`, final `COMPLETED`,
  version 2.

The decisive projection/action sequence was:

1. Both sessions recovered through projected `ensure_session`, then received
   ordinary `IMPLEMENT` work and made only their assigned visible changes.
2. B received and executed the exact REVIEW admission request for A:
   `request_coordination(work_group_join, workGroupId=52ceb172-4e63-332b-ac6a-a5d932acd03d,
   intentId=157a36a3-3788-3537-a094-64dd93b22dd5)`.
   The request was `9207324b-ffac-49a4-9ca8-c5d476cf4b21` and was accepted.
3. A accepted the reciprocal request
   `55003e88-604a-470a-8815-9c8cb433f862`. The two single-use REVIEW grants
   were consumed with exact participant, intent, WorkGroup, and epoch data:
   `6458691e-3129-3951-b2fa-ad76e5eb2424` targeted A and
   `c87c2bbe-b13c-3697-9ce3-0a6dfb40a573` targeted B.
4. B published its first immutable test-only snapshot
   `snap_2784391af0f35b6bc74ada2743cb4726` (base `06430c6`, commit
   `f06750d1a192f517f8e603342d85abf022f1ee70`, `test_todo.py`). A inspected
   it through the authorized review projection and submitted structured
   `REJECTED`; the failed test was the expected missing implementation case.
5. A executed the projected `finish_lane` and published/integrated
   `snap_3d6669f5e5ce033c8abd075f849aed1c` (commit
   `55633779518cfc69c02b982e4d96c13fe8ff333d`, `todo.py`). B inspected the
   immutable snapshot and submitted structured `ACCEPTED`.
6. The control checkout ended clean at `8892f88`, with the two immutable lane
   commits `728c548` (`test_todo.py`) and `8892f88` (`todo.py`); visible
   `pytest` passed 2/2 for the seed’s current test set.

The agents made several invalid response attempts while reviewing, including
unsupported fields and incomplete review identifiers. Synesis returned
fail-closed `policy_denied` responses (`COORDINATION_RESPONSE_FIELD_NOT_ALLOWED`,
`REVIEW_GRANT_BINDING_MISMATCH`, and required-field errors). They then returned
to the unchanged projected arguments and the valid decisions succeeded. This
is evidence that the protocol remains fail-closed, not a failure of an
unchanged projection.

Final bounded diagnostics:

- `collaboration status`: WorkGroup `COMPLETED`; both participants
  `COMPLETED`; accepted requests and consumed grants recorded.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  reconciliation recommended, no mutation performed.

## Second ordinary unattended acceptance

This run used only the two visible coding prompts. No exact-action rule,
continuation prompt, lifecycle instruction, relay, or manual transition was
provided.

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0525-002`
- Project ID: `4b46f176-3697-4c24-83ef-1cf13e6ab95f`
- Seed commit: `bdab0cb`
- Managed baseline: `0931a46`
- Sessions/worktrees:
  - A: `session-02865bdb-b0fa-4d63-9687-e6b05c37f8ad`, participant
    `agt_a752cedf-c9f6-3f32-b2ab-414d2ab4bf44`, intent
    `821ba540-fd60-3ae1-b05d-ef5115b8c4c9`, exact claim `todo.py`, epoch 1
  - B: `session-476b41ef-33b8-4bd5-b2f1-f06a194b0baa`, participant
    `agt_b5d2eb1d-0d1b-3cdc-9fc0-c6e915f8c4ca`, intent
    `3e291f66-34e2-3478-965e-35f641a29966`, exact claim `test_todo.py`,
    epoch 1
- Shared WorkGroup:
  `5e0a82d7-635d-3e47-9e3e-5a4c37d83822`, final observed `ACTIVE`, version 1.
- REVIEW requests, both `ACCEPTED`:
  - `e4ce5111-8d75-4715-a1cb-625616f0c2d5` B → A
  - `93dcbd40-37bc-40d9-88cf-ee23161ad757` A → B
- REVIEW grants consumed with exact projected arguments:
  - `a7bbc462-9c63-3033-bef2-1d436019b3b1` targeted B
  - `a5cc0b20-241f-3c8a-9424-1e9c7ae1ee1f` targeted A
- A snapshot:
  `snap_de38379e858662f72b2a5de69db6d983`, immutable commit
  `64bbfba43959ef33c706626ee86a345f92add58f`, `todo.py`; integrated control
  commit `a625698`.
- B snapshot:
  `snap_b78e80fc552f8df1a890812d587b2e72`, `test_todo.py`; integrated control
  commit `9913709`.
- Control checkout tests: `pytest -q` passed 3/3.

The ordinary run reached the first reciprocal review, and B correctly
accepted A’s implementation snapshot. A then received/consumed the reciprocal
REVIEW grant but its Codex turn ended while `get_next_action` continued to
project the valid `WAIT → get_next_action({})` continuation. B published and
completed its test lane, but no reviewer decision for B’s snapshot was
recorded before the agents stopped. No unchanged projected lifecycle action
returned an error, and no valid action was absent; the stop is classified as
ordinary agent/session engagement rather than a production protocol defect.

Final ordinary state:

- `collaboration status`: both participants `COMPLETED`, both requests
  `ACCEPTED`, WorkGroup `ACTIVE`.
- Control checkout `git status`: one generated untracked `__pycache__/`
  directory remains; this is a disposable acceptance artifact observed after
  the first blocker, not a reason to alter production integration behavior in
  this slice.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings; no mutation
  performed.

## Classification and next step

The bounded exact-action run proves the current review/snapshot/validation/
integration protocol can complete end-to-end and remains fail-closed. The
ordinary run reproduces the same external agent-turn engagement boundary:
the agent stops while a valid continuation remains projected. No production
change is justified by these runs.

Next narrow action: preserve the ordinary projection/turn-ending evidence and
run the focused SYN-039 verification plus repository diagnostics, then create
the next checkpoint. Do not add lifecycle machinery, cleanup behavior, or
orchestration based on this run alone.
