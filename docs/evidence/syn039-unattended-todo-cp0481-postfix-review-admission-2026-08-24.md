# SYN-039 CP-0481 — Post-fix diagnostic acceptance

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion

## Scope

This run verified the CP-0480 fix with a fresh disposable Git + Synesis
project and two independent GPT-5.6 Luna Codex sessions. No production code
was changed during the run. The raw agent traces are retained at:

`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0480-005\logs\`
and
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0480-006\logs\`.

The `-005` launch was invalid as an acceptance fixture: Agent A's prompt
caused it to claim both `todo.py` and `test_todo.py`, while Agent B claimed
`test_todo.py`. Synesis correctly failed closed with `overlapping_claim`.
Those logs are preserved, but are not product acceptance evidence.

## Post-fix diagnostic fixture

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0480-006`  
Project ID: `df45ded4-0b90-4f6e-8cc5-f810d714942f`  
Baseline commit: `96f4ec7b19451eedf449e483baee10a3d4c8a500`  
WorkGroup: `f0666aa0-31db-3025-a7e7-2e46f3fad1de`

Both agents used the same explicit current bundled MCP wrapper:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

The bundle reports `synesis 0.1.0-SNAPSHOT`; SHA-256 is
`9064A9A96B0DD09595409E886214C5C2F35D4778B221ABB77DCE60D6161B576E`.
The repository's MCP contract remains the ten-tool catalog. Both sessions
reached `ensure_session=ready` with `workspace=isolated`, the same project,
distinct session identities, and disjoint exact claims:

| Agent | Session | Participant | Claim | Intent | Epoch |
|---|---|---|---|---|---:|
| A | `session-765b0fa6-1c40-4e61-8f2f-ed81dc42e2bf` | `agt_314ed7cf-077b-3486-9332-e641dedd4d0a` | `todo.py` | `3096a26b-92d7-3056-8468-747b7c77e1e5` | 1 |
| B | `session-4c9dbd8a-4ee7-4845-9bac-84670aab0e84` | `agt_54e796c5-3877-396f-a90f-5a13b2fb8b67` | `test_todo.py` | `2b0bc998-4bd9-30ba-b416-407b7bbd9d9a` | 1 |

The first shared projection showed both participants, both intents, one
active WorkGroup, and no claim conflict. Backend WorkGroup convergence and
the CP-0480 admission projection path therefore remained healthy.

## Action trace and first blocker

Both agents performed normal visible repository work. Agent A added
`TodoList.complete(title)` to `todo.py`; Agent B added four focused tests to
`test_todo.py`. Agent A's isolated smoke test passed, and Agent B's tests
compiled; its test run initially failed only because Agent B's isolated
worktree correctly did not contain Agent A's unintegrated implementation.

After implementation, Agent A's `get_next_action` remained workflow
`IMPLEMENT` with no concrete `recommendedTool` or typed lifecycle arguments.
Agent A nevertheless selected `finish_lane`. The call published:

- snapshot: `snap_0c58f76fb959553d7d64d64ce7b0d21c`
- snapshot commit: `cf4e313abe5175b53b5240415c376af4c3e38994`
- changed path: `todo.py`
- claim epoch: `1`
- integration result: `integration_failed`

Agent A retried only after Synesis projected `nextAction=retry`; the retry
again returned `integration_failed`. No REVIEW admission projection,
`request_coordination`, REVIEW grant, validation decision, or ACCEPT/REJECT
transition was reached. Agent B's final projection remained ordinary
`IMPLEMENT` with no pending coordination action.

This is agent action/compliance evidence, not a proven CP-0480 production
defect: the failing `finish_lane` was not the projected next action. The
secondary integration result must not be used to change integration behavior
until an agent executes the exact projected action or a deterministic fixture
reproduces the same failure through the intended protocol.

## Final state

The control coordination status reported `COORDINATION_STATUS=PASS` with
sequence `0`, zero durable tasks, and zero ownerships in the control view.
The raw agent projections retain the active WorkGroup and published snapshot
state described above. No reviewer grant, validation, or WorkGroup closure
was recorded. No ordinary second acceptance was run because the diagnostic
did not complete end to end.

Doctor was `DEGRADED` with six warnings and no errors or critical findings:

- `ambiguous_session_liveness`
- `stale_session_lease`
- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required` findings

These warnings were not shown to cause the post-fix projection or admission
behavior and remain separately classified. The known root Git subprocess
stall and bootstrap migration failures likewise remain separate verification
issues.

## Classification and next action

The CP-0480 exact REVIEW admission argument fix is not contradicted by this
run, but it was not exercised because no REVIEW admission action was
projected. The next implementation/acceptance slice should address the
agent-facing transition from completed implementation and published snapshot
to a projected, actionable review request, or reproduce that transition with
an exact projected action before changing production code. Do not broaden
cleanup, Doctor, integration, or ownership behavior.
