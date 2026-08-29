# SYN-039 CP-0533 engaged two-agent diagnostic

Date: 2026-08-24
Status: PARTIAL; both lanes integrated and the WorkGroup closed, but reviewer
validation was not trustworthy because the reviewers could not access the
immutable snapshot from their stale bound workspaces.

## Scope and harness

This was a fresh bounded diagnostic using two independent GPT-5.6 Luna High
Codex sessions. The harness supplied complementary visible coding prompts and
required each agent to execute every concrete `get_next_action` projection
exactly and remain engaged through projected WAIT/coordination continuations.
No information was relayed between agents, and no coordination transition,
grant consumption, snapshot publication, validation, conflict repair, or
integration was manually triggered.

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0533-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0533-001`
- Seed commit: `7c88a0b`; managed baseline: `fdfb96a`
- Project ID: `a376d789-c5c7-4412-86cd-41d3d693f029`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `3076EF03BCE4EE3088165296720B64241127549327E47589ACD12EDE57CCEA9C`
- Both MCP traces reached `initialize_parsed`, wrote the initialize response,
  and received `tools/list`; both wrappers used the same project pin and
  distinct connection IDs. The current catalog contains exactly ten tools.

## Participants and ownership

| Agent | Participant                                | Intent                                 | Claim          | Epoch | Session worktree                               |
|-------|--------------------------------------------|----------------------------------------|----------------|------:|------------------------------------------------|
| A     | `agt_724b59a9-2a62-3f63-8335-093ce2f65fc9` | `3152e5e6-783c-3064-979c-25d4627efce8` | `todo.py`      |     1 | `session-7e568cef-7014-4523-82fa-dfad10bb19bf` |
| B     | `agt_e3b1223c-a595-31b3-a6c6-7bf4b94ecfd5` | `8c103aab-8b2d-395a-871a-00e1bf7324e4` | `test_todo.py` |     1 | `session-0262c7e7-6391-4217-ae4a-bec3e4626118` |

Both claim-bearing `ensure_session` calls returned `ready / isolated`, and
the claims remained disjoint.

## Projection-to-action trace

The raw JSONL projection/action trace is in
`harness-diagnostic-cp0533-001\logs\agent-a.jsonl` and
`harness-diagnostic-cp0533-001\logs\agent-b.jsonl`.

1. B received `REVIEW_ADMISSION_REQUIRED` and executed the exact projected
   `request_coordination(work_group_join)` for A's intent. Request
   `27858b5c-e7c0-4692-821f-8054720c4088` was created and A executed the exact
   projected `respond_coordination`; the request became `ACCEPTED`.
2. A implemented `todo.py` and its visible pytest run passed 3/3. B added a
   meaningful completion regression to `test_todo.py`.
3. A received the exact projection
   `snapshot_publication_required -> finish_lane({"summary":"Publish the completed immutable snapshot"})`
   and executed it. Synesis returned `PUBLISHED` and `integrated` for
   snapshot `snap_6064cd14a4fcf0028614b1ce8fc9bd6d`, commit
   `576378113246ae35d34edd2d57966c7113ea68df`; control advanced through
   integration commit `02f642225f0ec89a5765b68925fc2003584c6500`.
4. A then projected and executed the reciprocal REVIEW request for B's intent.
   Request `530427fa-9612-43ae-9080-64f62a7d03b7` was accepted by B.
5. B consumed the exact single-use REVIEW grant
   `12f7812c-b1bf-365e-98c3-3d6aa4b5fff0` for A's snapshot, intent, WorkGroup,
   target participant, and epoch. No overlapping write ownership was created.
6. After consumption, B's `get_next_action` projected
   `review_decision` / `review_validation` with exact grant
   `12f7812c-b1bf-365e-98c3-3d6aa4b5fff0`, intent
   `8c103aab-8b2d-395a-871a-00e1bf7324e4`, epoch `1`, and snapshot
   `snap_6064cd14a4fcf0028614b1ce8fc9bd6d`. It exposed `accepted|rejected` and
   the required rejection reason, but did not project an executable tool and
   typed arguments for the decision.
7. B attempted to inspect the review snapshot with `read_file("todo.py")` and
   `run_command(["python","-m","pytest","-q","test_todo.py"])`. Both exact
   results were `retry_required / workspace_stale / ensure_session`.
   B then attempted `ensure_session({"refresh":true})`, which returned
   `failed / internal_failure / request_human_help`. It submitted the exact
   structured review response as `REJECTED` with the reason that validation
   could not be completed. The rejection was not evidence that A's code was
   bad; the reviewer could not access the snapshot.
8. Synesis issued a new single-use REVIEW grant
   `8949dfe5-67fd-3464-8253-30bb7b8ab47e` for A, and A consumed it with the
   projected identifiers. B then received and executed its exact
   `snapshot_publication_required -> finish_lane` projection. Its immutable
   snapshot `snap_b367ea88864a7653386dac1d6e3b3702`, commit
   `66c53b9a21e8d3ea9b07122226bc0b9194dc6845`, integrated at control commit
   `74e378820d4f77e91ad2c64410965b596ad6f56e`.
9. A's reciprocal review read also returned `workspace_stale`; A nevertheless
   submitted `ACCEPTED` for B's snapshot. The WorkGroup then reached its
   terminal `COMPLETED` state. This closes the coordination records, but it is
   not clean validation evidence because the decision followed an unsuccessful
   snapshot read.

## Final state

- WorkGroup: `cf3f65dd-c43b-3ad1-855b-0d72c68a419a`, `COMPLETED`, version 2.
- Both participants: `COMPLETED`.
- REVIEW requests: `27858b5c-e7c0-4692-821f-8054720c4088` and
  `530427fa-9612-43ae-9080-64f62a7d03b7`, both `ACCEPTED`.
- REVIEW grants: `12f7812c-b1bf-365e-98c3-3d6aa4b5fff0` and
  `8949dfe5-67fd-3464-8253-30bb7b8ab47e`, both consumed at epoch 1.
- Coordination status: `PASS`; sequence `0`; tasks `0`; ownerships `0`.
- Control checkout: clean `master`; final integrated tree contains both
  `todo.py` and `test_todo.py`; control pytest passed `4 passed`.
- No second ordinary unattended acceptance was run: the diagnostic reached
  WorkGroup completion, but not a trustworthy review/validation path.

## Doctor and verification

Fixture Doctor was `DEGRADED` with six warnings, zero errors/critical
findings, `reconciliationRecommended=true`, `repairAvailable=true`, and
`cleanupRecommended=false`: two `stale_session_lease`, one
`command_namespace_reconciliation_required`, one `command_capacity_or_retention`,
and two `provider_migration_required` findings.

Repository focused SYN-039 tests, fixture validators, Doctor, control pytest,
and `git diff --check` passed. The root `./gradlew.bat check --no-daemon
--max-workers=1` attempt remains separately blocked at `:link:formatCheck` by
trailing whitespace in older checkpoint/evidence documents; the known Git
subprocess stall and bootstrap migration failures remain separately recorded.

## Classification and next slice

The first concrete lifecycle blocker is reviewer snapshot access after control
advances: the review projection exposes the immutable snapshot identifiers, but
the already-authorized reviewer remains bound to a stale worktree. Its reads
fail closed, and `ensure_session` recovery can return `internal_failure`, so a
review decision can be recorded without actual snapshot validation.

This run changed no production code. The next narrow slice is to reproduce the
reviewer `review_decision -> snapshot read -> workspace_stale -> ensure_session`
transition in a deterministic MCP fixture and trace why the existing immutable
snapshot projection does not provide a usable read/recovery path. Preserve
fail-closed workspace, ownership, grant, participant, epoch, and validation
semantics; do not broaden cleanup, Doctor, orchestration, or unrelated
integration behavior.
