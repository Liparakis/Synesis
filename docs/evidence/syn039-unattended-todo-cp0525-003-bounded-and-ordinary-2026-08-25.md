# SYN-039 CP-0525-003 bounded and ordinary acceptance

Date: 2026-08-25

Status: PARTIAL. The bounded exact-projection diagnostic completed the
existing review, immutable snapshot, validation, integration, and WorkGroup
closure path. The required ordinary acceptance reached the same shared
WorkGroup and integrated the implementation snapshot, but the provider
session ended before the reciprocal review continuation could complete. No
unchanged projected Synesis action failed, and no production code changed.

## Harness and MCP

Both runs used fresh disposable Git projects, two independent GPT-5.6 Luna
High Codex processes, and the repository-built bundled MCP:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

The MCP startup traces reported protocol `2025-06-18`, version
`0.1.0-SNAPSHOT`, commit `bc334ac`, and exactly ten tools. Both connections
were explicitly pinned to the same project root with distinct connection
instance IDs. The JSONL logs and MCP traces are preserved outside the
repository under:

- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0525-003`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0525-003`

The prior CP-0525 ordinary sessions were launched with Codex `--ephemeral`.
An attempted `exec resume` returned `no rollout found for thread id`; the
same participant/intent could therefore not be resumed without inventing a
new session. That is harness evidence, not a Synesis protocol failure.

## Bounded exact-projection diagnostic

Project:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0525-003`

Project ID: `83318ef3-f435-4122-bd7c-a40a29c80e79`

The seed commit was `488de82`; the managed baseline was `ea90f91`.

| participant                                | session                                        | claim / epoch      | intent                                 |
|--------------------------------------------|------------------------------------------------|--------------------|----------------------------------------|
| `agt_b7e455b3-5bf8-3533-b6d3-0dcaf940e583` | `session-69350ac6-fa0f-4ab3-b1d3-c176b8a03835` | `todo.py` / 1      | `bbc61592-123e-34bf-b8cf-afebcdb7b04d` |
| `agt_18a76131-3fb6-3fe9-a2e3-4ac0a02b7804` | `session-311bac0b-20da-43bf-a2d5-6be462f2134e` | `test_todo.py` / 1 | `200cc50d-6ad4-38bb-ab04-6e41dd1d794a` |

WorkGroup: `18f226ad-d28b-3fd6-b8aa-3afb83429f4b`, final `COMPLETED`,
version 2.

The exact observed progression was:

1. Both agents reached `ready / isolated` and converged on one WorkGroup.
2. REVIEW admission projections supplied exact `request_coordination`
   arguments. Requests `7b1a5dc2-ce35-4c77-83eb-4e53933d2c37` and
   `43d0a12c-7ab9-464a-af79-70b55ad767f6` were accepted.
3. Single-use REVIEW grants were issued and consumed with participant,
   intent, WorkGroup, and epoch checks:
   `ec3b775c-f262-33d1-8d7e-a2aee8d728f5` targeted the test reviewer, and
   `907caba2-e8e0-3c42-9e7d-e168c1deafb6` targeted the implementation
   reviewer.
4. The implementation agent executed the projected
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   Snapshot `snap_f7cf8550b8bc318219ce725c44600f1f`, commit
   `9577158b9d284b6c36702e6179c5dd3576db8813`, and `todo.py` were published
   and integrated.
5. The test agent executed the same projected `finish_lane` action.
   Snapshot `snap_a395b25de4c2895ac7af0f948f4c16d1`, commit
   `fbe3f79e0fbc7d8b00d31b0ad46c96e5a7ea7e05`, and `test_todo.py` were
   published and integrated.
6. The reviewer ran the visible tests against the immutable snapshot and
   executed the exact structured ACCEPT response. Synesis returned
   `result=ACCEPTED` and `workGroupStatus=COMPLETED`.

The final control checkout was clean at `6d1341c`; `python -m pytest -q
test_todo.py` passed 3/3. `collaboration status` showed both participants
COMPLETED, both requests ACCEPTED, and no active claims. `coordination status`
was PASS with zero tasks and zero ownerships.

## Ordinary unattended acceptance

Project:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0525-003`

Project ID: `bebad616-240b-4b06-8b02-9b6f01e01f2d`; seed commit `54cfd5b`.

| participant                                | session                                        | claim / epoch      | intent                                 |
|--------------------------------------------|------------------------------------------------|--------------------|----------------------------------------|
| `agt_e297990a-bd36-3c82-b5e4-b212274de0d1` | `session-02cefbbb-4458-4d31-9fa6-3cd2bf41a341` | `todo.py` / 1      | `0aa91b39-1b98-3841-90f0-9f99e9a45844` |
| `agt_a25a9bc6-7ffd-377f-98b3-f8b5e3e5a4e1` | `session-a71ce1e7-3217-419f-b48c-026994f79f8c` | `test_todo.py` / 1 | `f67f4ca9-6836-3d57-8700-8b511b4f4dd2` |

WorkGroup: `0d6e6301-e6d1-3084-b0be-abbca3cdaa10`, final observed `ACTIVE`.

The run used only the two visible coding prompts. Both agents reached the
same project, distinct isolated sessions, disjoint claims, and one shared
WorkGroup. Requests `892eea6e-6ad6-48e4-9c17-f9db6ed6a3dd` and
`fa4ae58a-9716-41a2-b3b6-eaed4751c6ae` were accepted. Grant
`869abda9-57d7-30e0-a790-fccbd8c77877` targeted the test reviewer and grant
`18b1c463-dffa-3782-a084-12013f649f22` targeted the implementation reviewer.

Agent A executed the projected `finish_lane`, publishing and integrating
`snap_c550cc7ed9480371be171f9c8da7f583` at commit
`03c4c117b92fc3dc28f1cdd47e83f56d1c160e56`. Agent B accepted that snapshot
with structured validation, and both agents' visible work reached their
respective completed coding states.

The first ordinary engagement boundary was after A's successful review
admission request and a subsequent concrete `get_next_action` projection for
the reciprocal review request. A's Codex turn ended with an agent message;
there was no follow-up lifecycle call from that session. B correctly followed
the projected `WAIT -> get_next_action({})` continuation and accepted A's
snapshot, but the reciprocal grant targeted at A remained unresolved because
A's session was no longer running. B's final projected state was
`REVIEW_GRANT_PENDING` / `WAIT`, with grant
`18b1c463-dffa-3782-a084-12013f649f22` targeted to A. No unchanged projected
action returned an error.

The final control checkout was clean at `ce661ba`; control
`python -m pytest -q test_todo.py` passed 2/2 because B's test snapshot was
not published. The final read-only state showed both REVIEW requests
ACCEPTED, A COMPLETED, B ACTIVE, WorkGroup ACTIVE, and the two grants still
recorded. Doctor was DEGRADED with six warnings, zero errors, and zero
critical findings.

Classification: provider/session engagement and continuation compliance, not
a proven Synesis lifecycle defect. The bounded diagnostic proves that the
existing protocol still closes cleanly when projected actions are executed.

## Separate verification classification

- No production source changed for this run.
- The known root Gradle Git subprocess stall remains separate.
- Bootstrap migration failures remain separate.
- Doctor warnings remain separate; they did not cause either lifecycle result.
- No push was performed and no SYN-040 was created.
