# SYN-039 first implementation slice — 2026-08-22

## Result

The two requested defects are fixed and covered by deterministic regression
tests. The exact unattended Todo rerun advanced past both baseline failures:
the reviewer request was accepted and issued a targeted single-use grant, and
the implementer's passing snapshot integrated into the control checkout.
The run then stopped at the next intentionally out-of-scope lifecycle gap:
the reviewer did not validate the published snapshot, so the WorkGroup stayed
`ACTIVE` and closure evidence was not produced.

No manual file assignment, message relay, conflict resolution, snapshot
selection, or integration was performed during the rerun.

## Production changes

- `CoordinationRequest.Kind.REVIEW` uses the existing coordination request
  model for read-only admission.
- Accepting a `REVIEW` request issues a deterministic, single-use `LaneGrant`
  targeted at the requesting participant, for the owner's existing intent and
  claim epoch. Duplicate acceptance is idempotent. The owner's file claims are
  unchanged.
- A `work_group_join` request without `grantId` is normalized into a typed
  `REVIEW` coordination request. The existing grant-consuming join path remains
  fail-closed and unchanged for supplied grants.
- Collaboration status and `get_next_action` now expose groups, targeted
  grants, and immutable task snapshots, including snapshot commit, base commit,
  changed paths, lane, and claim epoch.
- The integration-check compatibility adapter now recognizes the bounded
  passing evidence already emitted by the Todo run (`testResult`, structured
  exit code, or an unambiguous `tests` entry) while retaining explicit
  fail-closed handling for failure wording. Project-owned validation commands
  remain direct argv and are not framework-specific.

## Deterministic evidence

- `coordination/.../WorkIntentServiceTest.java` proves that a reviewer request
  creates a grant only after owner acceptance, does not create reviewer file
  ownership, consumes once, and remains idempotent on replay.
- `mcp/.../McpSyn039SliceTest.java` reproduces the missing-`grantId` MCP
  request, proves typed reviewer admission and grant consumption, and covers
  both recorded passing Todo integration evidence forms without
  `TESTS_FAILED`.
- Targeted commands passed:

  `./gradlew.bat :coordination:test --tests org.synesis.coordination.collaboration.WorkIntentServiceTest :mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest --no-daemon --console=plain`

  `./gradlew.bat :mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest --no-daemon --console=plain`

  `./gradlew.bat :workspace:test --tests org.synesis.workspace.application.integration.IntegrationCompatibilityServiceTest --tests org.synesis.workspace.application.agent.Syn037CompletionValidationTest --tests org.synesis.workspace.MultiChatLogicalWorkspaceTest --no-daemon --no-parallel --max-workers=1 --console=plain`

  `./gradlew.bat :cli:installDist --no-daemon --no-parallel --max-workers=1 --console=plain`

- `git diff --check` passed. The full root `check --dependency-verification=strict`
  passed compilation, hygiene, packaging, Javadoc, static analysis, and
  launcher stages, then was bounded at the long-running `:mcp:test` stage;
  no test failure was emitted before stopping it. The separate combined
  coordination/MCP run likewise reached `:mcp:test` without output and was
  stopped after a bounded wait. The deterministic and focused gates above are
  the authoritative green verification for this slice.

## Exact unattended rerun

- Fixture: `C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-slice-20260822`
- Project: `2dbe297d-162c-4f2d-897c-306a57beb959`
- Baseline commit: `2a4553c`
- WorkGroup: `932d024e-06ff-3176-bef6-12c33279e486`
- Implementer participant/lane: `agt_e8ef586b-adfb-3d56-86e2-6b410df2835c` /
  `3db91f37-9ad0-364c-aaab-cb26494fdee1`
- Reviewer participant: `agt_93870f01-30a0-30f9-bf9b-29ac7de500dd`
- Review request: `0c85ee01-5841-4a6b-896e-789815a378d8`, accepted by Agent A
- Review grant: `79ef69cd-55bc-3925-a179-ff272cc94d12`, single-use and targeted
  to Agent B; owner claim epoch `1` and owner file claims were preserved.
- Snapshot: `snap_3e673171518792f078f394bf5dab7cd5`
- Snapshot base/commit: `2a4553cb352b297c39dda4af6f1e3c96d5c584a9` /
  `ae17adfa3a4fc04a5ac73dd04b42337df38692c7`
- Snapshot changed paths: `todo.py`, `test_todo.py`
- Agent validation: `python -m pytest -q` -> `3 passed in 0.02s`
- Control checkout result: clean `master`, commit `97664dc Synesis immutable lane snapshot`;
  the completed Todo application is present and the control checkout test run
  also passed `3/3`.
- WorkGroup result: still `ACTIVE`; grant unused, no validation decision, no
  reviewer snapshot validation, and no clean-close evidence.
- Doctor result: `DEGRADED`, five warnings, zero critical/errors,
  `reconciliationRecommended=true`. Warnings were ambiguous/stale session
  liveness, command namespace reconciliation/retention, and provider
  migration. This is not claimed healthy and is the next lifecycle scope.

## Scope conclusion

This slice closes the recorded missing reviewer authorization and false
`TESTS_FAILED` defects. It deliberately does not implement rejection routing,
review command execution, WorkGroup closure, stale lease cleanup, detached
agent cleanup, or broader integration redesign. The exact next SYN-039 action
is to make the admitted reviewer validate the published snapshot and drive the
existing accept/close path, preserving any subsequent failure as the next
bounded blocker.
