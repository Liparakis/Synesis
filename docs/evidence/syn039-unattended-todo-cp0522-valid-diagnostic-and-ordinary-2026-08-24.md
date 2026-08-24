# SYN-039 valid diagnostic and ordinary acceptance — CP-0522

Date: 2026-08-24

CP-0522 contains two fresh runs with the same genuinely incomplete Todo seed:
a no-op `TodoList.complete`. The bounded diagnostic used an explicit
existing-session continuation instruction only after a lane completed. The
ordinary run used only the two actual coding prompts.

## Valid bounded diagnostic

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0522-004`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0522-004`
- Project ID: `83c114a1-05db-44dc-813f-b5ce36265b7c`
- Seed commit: `ee48bbf`; managed baseline `d9b50cc`.
- MCP: current bundled `0.1.0-SNAPSHOT`, startup commit `bc334ac`; both
  explicit wrapper connections used the same project root and provider
  `codex`.
- Sessions: A `01a0353d-74ff-7460-a22e-34b8a6c80853`; B
  `01a0353d-7527-7052-bd38-b70f99537cca`.
- Participants: A
  `agt_ceb1e3c4-2cef-39eb-8fa0-3fca08f38602`, intent
  `7852a1f4-25cf-344c-86ce-b36d891a85c0`, `PATH_EXACT todo.py`, epoch 1; B
  `agt_17cd55dc-30ee-30ac-924f-4ecfe6f93e55`, intent
  `a2d754d8-234f-313d-b533-3d139110bc84`, `PATH_EXACT test_todo.py`, epoch 1.
- WorkGroup: `eaa7631f-ce23-310f-b94c-d44db63b8eda`, final `COMPLETED`,
  version 2.
- Accepted REVIEW requests: `e9162cfc-c9aa-4e35-9625-629c9d95f1a9` and
  `a1562cd6-7fb2-453e-b62a-a4be6fdc7e76`.
- Grants: `6cb26e55-de30-3dd9-abb0-09e823008383` targeted B and
  `98cd5343-32b0-345c-a2a2-74bd979215d8` targeted A; both epoch 1,
  single-use, and consumed.
- Snapshots: A
  `snap_68dcebb4c449128430a24accb65d2471`, commit
  `be8a44c21ef7ca1a5a2c581082dce8d9e5948836`, changed `todo.py`; B
  `snap_ba7aa4d87ce49564755ae9467e86657c`, commit
  `a899cc613c4a241db6a90c0b217b5cbcb97a4bf2`, changed `test_todo.py`.
- Both immutable snapshots were inspected and received structured `ACCEPT`;
  both integrated. Control checkout `02bac01` was clean and `pytest` passed
  4/4.
- Final Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings. The
  warnings remain separately classified.

The diagnostic reached the full protocol path without manual relay or manual
transition: implementation, admission, grants, snapshots, immutable review,
structured ACCEPT, integration, tests, and terminal WorkGroup closure.

## Ordinary acceptance

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0522-005`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0522-005`
- Project ID: `854765bb-a91e-4d8a-931b-d4432ddb1416`
- Seed commit: `78af369`; managed baseline `1e687fa`.
- Sessions: A `01a03543-1e83-7ab2-a5a5-3b2607adca8c`; B
  `01a03543-1efd-7331-9887-ceb8cc3a4a45`.
- Participants: A
  `agt_6883f9b5-b311-3c81-80d6-7d12ca50a1e9`, intent
  `0ece89cd-632d-33fc-8586-eea67d4faf8b`, `todo.py`, epoch 1, completed; B
  `agt_4b87061a-2c3a-31d5-9665-d40c51b274a1`, intent
  `53518a83-64ea-31bb-b2da-5d53e3e17ae7`, `test_todo.py`, epoch 1, active.
- WorkGroup: `0f999cd8-e9b2-38cc-a382-ab6722b76139`, final `ACTIVE`.
- Accepted REVIEW requests: `fdc44507-3d65-48ac-9767-25f47d7f49ab` and
  `7e0bfa32-f249-4aa3-8c0f-2ea9960f212b`.
- Grant `17eec59b-9dd0-34cf-9555-4a12415c11d7` targeted B and was consumed;
  grant `051f07ff-e0c0-3f10-8422-705d066afc57` targeted A and remained
  pending.
- A's snapshot `snap_d0a18b8641e2054682eb15f95d3a772c`, commit
  `2c85a3669f77d45cfa45164effd5bebd40aaf2d`, changed `todo.py` and integrated.
  B did not publish its `test_todo.py` snapshot.
- Control checkout `b7cf7c0` was clean and `pytest` passed 3/3. Doctor was
  `DEGRADED` with 6 warnings, 0 errors, and 0 critical findings.

## Ordinary projection/action boundary

A executed the exact projected `finish_lane` and the exact projected REVIEW
admission request. The following `get_next_action` projection still exposed
the pending `request_coordination` action; A did not execute that repeated
projection and its Codex turn ended. B accepted A's snapshot and then received
repeated exact `WAIT` projections with `recommendedTool=get_next_action` and
arguments `{}` while grant `051f07ff-e0c0-3f10-8422-705d066afc57` targeted A.
B followed the wait protocol and could not advance its own lane.

No unchanged projected action failed, no valid active lane lacked a usable
projection, and no production protocol defect is proven. The difference from
the successful diagnostic is the explicit post-completion session engagement
instruction, not a backend or safety change.

## Classification and next action

Classification: deterministic ordinary Codex session/projection compliance
limitation. The repository's generated `AGENTS.md` already requires exact
projection execution and says not to stop merely because a lane is complete;
the ordinary agent still ended after a valid projected continuation. Do not
change ownership, grant, snapshot, validation, integration, or cleanup code
for this evidence.

Run one third fresh ordinary acceptance with the same actual prompts to test
repeatability. If it reproduces the same stop, preserve it as the external
agent-session blocker and do not claim SYN-039 complete.
