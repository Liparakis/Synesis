# SYN-039 CP-0532 — exact diagnostic closure and ordinary acceptance boundary

## Scope and evidence locations

This checkpoint records the required pair of fresh acceptance runs after
CP-0531. No production code changed, no remote state was modified, no
coordination transition was manually triggered, and no SYN-040 was created.

Both runs used the current bundled MCP:

```text
executable: C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe
version: 0.1.0-SNAPSHOT
commit: bc334ac
sha256: E91A08ADD236925A42D7A11F5F89AA615E807BB23C822925BD77E17EA0D6BEFB
```

The retained provider traces are outside this repository at:

- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0541-001\logs\agent-a.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0541-001\logs\agent-b.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0542-001\logs\agent-a.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0542-001\logs\agent-b.jsonl`

The MCP startup traces for both agents reported the same current version and
commit and received `tools/list`. The exact ten-tool catalog was already
verified in the current bundled-MCP preflight evidence.

## Fresh exact-projection diagnostic

Fixture:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0541-003`

Project ID: `e9cff886-feee-496c-933d-fbe939402ae9`.

Participants and disjoint epoch-1 claims:

- implementation: participant
  `agt_97497e0b-e803-31d3-b191-cb22d3bea975`, intent
  `0b2335be-8784-3c92-9d36-66601c046651`, claim `todo.py`;
- test: participant `agt_f27ed876-604a-375a-aba9-454c7ed807dc`, intent
  `07628381-0af6-38f4-9f0e-bed18bfba10b`, claim `test_todo.py`;
- shared WorkGroup: `35931e39-9eb1-3693-b03e-b89fc7088b72`.

The exact projection/action trace reached terminal completion:

1. Both agents established isolated sessions and announced disjoint claims.
   `IMPLEMENT` projections without a concrete lifecycle action were followed
   by ordinary visible repository work.
2. B executed the exact projected
   `request_coordination(work_group_join)` for A's intent. Request
   `203c5f08-dcaf-4bc6-87b5-2a2da54176cf` was admitted by A.
3. A executed the exact projected owner
   `respond_coordination` acceptance for that request.
4. A executed the exact projected `finish_lane` publication for its
   `todo.py` change. Snapshot `snap_1d7312a7776983949d6be2e0d2c17d48`,
   commit `61f265da02e47b50ee03639bf5c6ae4ab336b3d0`, was published and
   integrated.
5. A executed the exact projected reciprocal
   `request_coordination(work_group_join)` for B's intent. Request
   `368d7847-4589-4b79-843e-7c5023fbbbd7` was admitted by B.
6. B executed the exact projected grant-consumption action for single-use
   grant `9ec041fb-bf01-375b-a6dc-8c8c855469b8`, targeted to B with epoch 1
   and A's intent. B inspected A's immutable snapshot and submitted the
   structured `ACCEPTED` decision.
7. B executed the exact projected `finish_lane` publication for its
   `test_todo.py` change. Snapshot
   `snap_e7eb33ecffdcbe279df5677d81ae4a9a`, commit
   `ff36d3b3f2e39e755384c487eb84f3fa96caa713`, was published and integrated.
8. A recovered the exact projected `workspace_stale -> ensure_session({})`
   action, returned `ready / isolated`, consumed the exact single-use grant
   `14d3b3d1-3eb3-3f7c-881d-3734e5c629fc`, and inspected B's immutable
   snapshot. A then submitted the structured `ACCEPTED` decision with the
   exact grant, intent, epoch, and snapshot identifiers.
9. Synesis returned `workGroupStatus=COMPLETED`. The control checkout was
   clean at `04c2720` and direct control verification reported `5 passed`.

Every concrete executable projection retained in both JSONL traces was
executed with its projected arguments. Repeated request projections replayed
idempotently. No unchanged projected lifecycle action failed, and no manual
relay, ownership repair, snapshot publication, validation, or integration was
performed.

Final diagnostic coordination status reported zero tasks and zero ownerships.
Doctor was `DEGRADED` with six warnings, zero errors, and zero critical
findings: two stale session leases, command namespace reconciliation,
command retention/capacity review, and two provider-migration warnings.

## Fresh ordinary unattended acceptance

Fixture:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0542-003`

Project ID: `9af5f848-6bc9-45be-b48b-2e26d3d128bb`.

Participants and disjoint epoch-1 claims:

- implementation: participant `agt_896d3e0f-4673-3a2c-9a65-680f0d709c96`,
  intent `a9499eb3-d998-385b-b853-4e7a836000af`, claim `todo.py`;
- test: participant `agt_6459eecf-c8c1-335b-86c0-69be26f0905b`, intent
  `cb1a4a9b-3f94-3d20-9635-d62f977eacfe`, claim `test_todo.py`;
- shared WorkGroup: `4646b6ba-66bc-3760-8fda-fc04b9db1b66`.

The ordinary prompts contained only the two complementary coding tasks. The
run reached shared review and proved the existing fail-closed behavior, but it
did not reach clean closure:

- B published and integrated test snapshot
  `snap_40f972d9d2cf16cd8d76b747d8715267`, commit
  `444d9817d86b5e33a4f3452f22c0aae0172fc4c1`.
- A consumed REVIEW grant `884a2ad0-c740-3941-b98c-25461b94288e` and
  inspected that immutable snapshot.
- A first submitted a review rejection with unsupported field
  `failedAcceptanceTests`; Synesis correctly returned
  `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:failedAcceptanceTests`.
- A then submitted a rejection with a wrong `intentId`; Synesis correctly
  returned `REVIEW_GRANT_BINDING_MISMATCH`.
- A later submitted the correctly bound structured `REJECT`, which succeeded
  and returned `workGroupStatus=ACTIVE`, routing the actionable work back to
  the implementation lane.
- A implemented `todo.py` and reached a projected `WAIT` continuation. B's
  reciprocal request `29d6f8fa-9b77-488a-9f2b-9e2362e89791` remained at the
  provider-session boundary before the reciprocal grant was consumed. No A
  implementation snapshot was observed.

The final control checkout contained only the test snapshot and reported
`1 failed, 4 passed`; the four failures were the unimplemented `todo.py`
behavior because A's later snapshot was not published. The bounded ordinary
provider wrappers were stopped after the evidence window; this was harness
termination after observation, not a Synesis lifecycle transition.

This run is agent-compliance/session-engagement evidence, not a production
protocol defect. The invalid review payloads were not the exact projected
arguments, and the same review, rejection routing, snapshot, integration, and
closure path succeeded in the exact-projection diagnostic.

Ordinary-run Doctor was `DEGRADED` with six warnings, zero errors, and zero
critical findings: ambiguous session liveness, one stale session lease,
command namespace reconciliation, command retention/capacity review, and two
provider-migration warnings.

## Classification and next action

SYN-039 protocol correctness is now demonstrated end to end by the exact-rule
diagnostic. The ordinary product acceptance remains incomplete because normal
Codex turns supplied invalid review arguments and then stopped while a valid
reciprocal continuation remained. No production change is justified by this
run. Keep the Git subprocess stall, bootstrap migration failures, and Doctor
warnings separately classified. The next action is another fresh ordinary
two-agent acceptance with only complementary coding prompts, preserving the
first unchanged projected-action failure or missing usable projection if one
appears.
