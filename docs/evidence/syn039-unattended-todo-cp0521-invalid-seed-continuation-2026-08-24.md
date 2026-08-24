# SYN-039 continuation diagnostic — CP-0521 invalid-seed stop

Date: 2026-08-24

This bounded diagnostic was intended to test retaining a completed participant
only for already projected REVIEW actions, without announcing a new intent.
It did not reach that phase because the fresh Todo seed already implemented
the requested behavior. The agents correctly refused to manufacture a code
change, so this run is fixture/agent evidence and not a Synesis production
failure.

## Fixture

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-continuation-cp0521-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-continuation-cp0521-001`
- Project ID: `d406ffe7-cb3a-4cd1-8d45-234f94de7489`
- Seed commit: `7c2e076` (`seed Todo continuation diagnostic`)
- Managed baseline: `574979f`
- Current bundled MCP: `0.1.0-SNAPSHOT`, startup commit `bc334ac`.
- Both sessions used explicit current-bundle MCP overrides and the same
  project root. The provider status separately reported the global Codex
  config as `MIGRATION_REQUIRED`; this did not prevent MCP wire compatibility,
  catalog freshness, or session binding and remains separately classified.

Participants:

- A: `agt_163aa675-87de-3d2a-a9ef-c3a5a4d32227`, intent
  `ce31338f-bc5a-35e4-9485-24cc15456ca8`, claim `PATH_EXACT todo.py`, epoch 1.
- B: `agt_3bac29c9-4662-31b4-ad3b-d22841658bc5`, intent
  `91fe4241-ede2-3f49-a232-16ebe2a5ba00`, claim `PATH_EXACT test_todo.py`,
  epoch 1.
- WorkGroup: `bb378922-3385-3c36-b8ac-98760163e56a`, final state `ACTIVE`.
- Review request: `c027daf4-0565-4dd0-8761-b5a9bec24a40`, accepted.
- REVIEW grant: `d0c99d5b-ac4f-35e7-8571-3283adf03e38`, epoch 1, single-use,
  targeted B; not consumed because no snapshot was published.

## Observed behavior

Agent A inspected `todo.py` and found that `TodoList.complete` already marked
matching items as completed. The visible tests passed 3/3 and no source diff
was warranted. A therefore remained in `IMPLEMENT` and never received or
executed `finish_lane`.

Agent B discovered the shared WorkGroup, submitted the projected REVIEW
admission request, and correctly remained in `WAIT` / `SNAPSHOT_PENDING` while
the implementation lane stayed active. B later added its regression test and
the focused visible suite passed 4/4, but no immutable snapshot, validation,
integration, or terminal WorkGroup state was reached.

The bounded harness stopped both sessions after eight coding turns. It never
entered the completed-lane continuation phase, created no replacement intent,
and performed no manual lifecycle mutation.

Final control checkout remained clean at `574979f`; Doctor was `DEGRADED`
with 6 warnings, 0 errors, and 0 critical findings.

## Classification and next action

Classification: invalid acceptance seed / correct agent refusal to invent
work. No unchanged projected action failed and no Synesis production defect
is proven.

The next diagnostic must use the same ordinary two-agent workflow with a
genuinely missing `TodoList.complete` behavior (a no-op implementation), then
retain completed sessions only for already projected review actions. The
no-op seed is a fixture correction, not a production change or Todo-specific
production behavior.
