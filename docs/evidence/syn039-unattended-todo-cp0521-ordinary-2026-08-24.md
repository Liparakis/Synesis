# SYN-039 CP-0521 ordinary unattended Todo acceptance

## Result and classification

This was a fresh ordinary two-agent acceptance using only complementary
visible coding prompts. No lifecycle rule, identifier, relay, manual request,
grant, snapshot, validation, integration, or ownership repair was supplied by
the harness.

The run reached one shared WorkGroup, exact REVIEW admission, owner response,
single-use REVIEW grant consumption, immutable snapshot publication, guarded
integration, and structured ACCEPT for Agent A's snapshot. It stopped before
the reciprocal snapshot because Agent A's Codex process ended after creating
the reciprocal REVIEW request, before polling the request's later grant
projection. Agent B then received only the durable WAIT continuation for a
grant targeted at Agent A, whose lane was already COMPLETED. No exact
projected lifecycle action failed; this is ordinary-agent engagement evidence,
not a proven production defect.

## Fresh project and harness

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0521-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0521-001`
- Project ID: `c0e40311-4003-4606-88a4-8eabaf3edffb`
- Seed commit: `0794246`
- Final control checkout: `280d15d` (`Synesis immutable lane snapshot`)
- Control checkout: clean
- MCP executable: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `D7B8C0E533674C2BDA891FC60F2B013923A731838C1F0601EBC4E5AD3F11360C`
- MCP startup version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Agent A connection: `conn-instance-fe3e744c-a60b-4332-b37f-16e3289bab43`
- Agent B connection: `conn-instance-63808ae7-732c-4d81-9933-ed142689e7da`

## Participants and claims

| Agent | Participant / intent | Claim and session worktree | Final state |
|---|---|---|---|
| A | `agt_f63934bf-e66c-335b-acf2-d9971f6cbda5` / `af6dc944-f448-3334-a90b-2378d41fcdf2` | epoch 1, `PATH_EXACT todo.py`, `session-83c55a2d-91c8-42e3-9946-c32d1b26e7bf` | COMPLETED |
| B | `agt_7a893393-1bc6-35a9-bea1-0c525b1065bd` / `c2cf7424-99d4-3956-b61e-dff2a0793086` | epoch 1, `PATH_EXACT test_todo.py`, recovered worktree `session-17a29508-bd25-4fcb-bbb5-c6f6873aa47e-recovery-335bf0fb-d7f3-48d3-844b-03c8086ab451` | ACTIVE |

Shared WorkGroup: `76687622-dc98-331d-946a-29b3ada29382`, `ACTIVE`.

## Projection-to-action trace

1. B received and executed the exact projected REVIEW admission for A:
   request `465bf8e4-29bc-4347-8f2e-4a0eb9f10c47`, later accepted by A.
2. A received and executed the exact projected owner response. B consumed
   grant `46285822-ebc0-3e19-9169-282437dfa79b`, targeted to B for A's intent,
   epoch 1.
3. A received `snapshot_publication_required` with
   `finish_lane({"summary":"Publish the completed immutable snapshot"})` and
   executed it exactly. Snapshot:
   `snap_e426d4bc75881c0ef58ad2a0d7bdad08`; integration was `integrated`.
4. B recovered from `workspace_stale`, received the structured
   `review_validation` projection, and submitted `accepted` for A's snapshot.
5. A then received the exact reciprocal REVIEW admission projection and
   created request `61b8543d-f91c-4e2e-a022-0a332007aaec`; B accepted it.
   A did not poll again after that peer-side transition.
6. B repeatedly received only `WAIT -> get_next_action({})` with
   `reviewGrantPending=true`. The grant was
   `d72045c7-3761-3d92-8a84-71b9ab1dfba5`, targeted to A for B's intent at
   epoch 1. A's lane was already `COMPLETED`, so no participant remained
   engaged to consume it. B had no executable action to publish its own
   `test_todo.py` work.

Both agents' visible test command passed (`1 passed`). B made no test-file
mutation because the durable inbox remained in the reciprocal grant wait.
The integrated control commit also retained generated Python cache artifacts;
cleanup remains a later SYN-039 boundary and was not changed here.

## Final diagnostics

`collaboration status` reports both REVIEW requests `ACCEPTED`, both grants
issued, A `COMPLETED`, B `ACTIVE`, and the WorkGroup `ACTIVE`. Doctor is
`DEGRADED` with six warnings, zero errors, and zero critical findings: two
stale session leases, command namespace reconciliation, command capacity or
retention, and two provider migration warnings. These warnings did not prevent
ready/isolated sessions or the lifecycle reached in this run and remain
separately classified.

Raw JSONL, prompts, wrappers, startup traces, and stderr are retained under
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0521-001`.

## Next classification action

Run the exact-action diagnostic before changing production behavior. Require
each agent to execute every concrete projection unchanged and remain engaged
through peer-side transitions. If an unchanged projected action fails, that is
the next protocol defect; if an agent changes or ignores it, retain agent
compliance evidence only.

