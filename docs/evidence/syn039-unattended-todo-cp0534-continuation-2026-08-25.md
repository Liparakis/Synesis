# SYN-039 CP-0534 continuation diagnostic and ordinary acceptance

## Result and classification

Two fresh two-agent Todo runs were performed after CP-0534:

1. A bounded diagnostic with the exact-action rule applied to both
   `get_next_action` projections and concrete continuations returned by
   mutating tools completed the full review, snapshot, validation, integration,
   and WorkGroup lifecycle.
2. The required second run used only ordinary complementary coding prompts and
   no protocol-conformance instruction. It reached one shared WorkGroup,
   admission, grant consumption, snapshot publication, integration, and
   structured ACCEPT for Agent A, but stopped when Agent A ignored the concrete
   reciprocal continuation returned by `finish_lane`.

The diagnostic proves the existing protocol is executable when agents obey
the durable projections. The ordinary run remains a product-acceptance
failure, but it does not prove a Synesis lifecycle defect: no unchanged
projected action failed, and the first boundary was provider/session
compliance with a continuation returned by a mutating tool. No production
code changed for these runs.

## Shared preflight and harness boundary

Both runs used fresh disposable Git + Synesis projects, two independent
GPT-5.6 Luna High Codex sessions, the current repository-built MCP, the same
initialized project root for both agents, exactly ten MCP tools, and
`ready / isolated` sessions with distinct participant identities and disjoint
claims. The harness did not relay messages, trigger lifecycle transitions,
publish snapshots, validate snapshots, repair ownership, or mutate the
control checkout.

- Bundled MCP:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `AF86D89708D1AD2AD7A58C3D08028AD1CB078FAC057AC41A80A8D886EFF9E788`
- Protocol: `2025-06-18`
- Server/version: `synesis 0.1.0-SNAPSHOT`
- Tool count: `10`
- Tool set: `ensure_session`, `read_file`, `apply_patch`, `run_command`,
  `get_next_action`, `request_coordination`, `respond_coordination`,
  `publish_capability_implementation`, `finish_lane`, `cancel_lane`

The MCP route used explicit per-agent wrappers pinned to that executable and
the initialized project. Raw Codex JSONL and MCP traces are retained in each
harness directory listed below.

## Diagnostic run: exact projected-action rule

### Fixture

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0534-continuation-2026-08-25-001`
- Project ID: `44f89cc1-ef73-4efa-af2c-365363a0a7d7`
- Initial Git fixture commit: `882ac86 baseline Todo fixture`
- Synesis-managed baseline: `62a2952`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0534-continuation-2026-08-25-001\logs`
- MCP connection IDs:
  `syn039-cp0534-continuation-001-agent-a` and
  `syn039-cp0534-continuation-001-agent-b`
- Codex session IDs:
  `01a036df-747a-7fc0-8dd5-c4bf7f0206ee` and
  `01a036df-74a7-7b72-8508-601159228da0`

### Participants, intents, claims, and epochs

| Agent | Participant                                | Intent                                 | Claim                     | Epoch |
|-------|--------------------------------------------|----------------------------------------|---------------------------|------:|
| A     | `agt_1b960144-d9d4-3a48-949f-9e4a9be820e6` | `39c57c74-831b-333c-b8dd-bbf1ebdf57b5` | `PATH_EXACT todo.py`      |     1 |
| B     | `agt_a5050c9b-4e52-3c79-be7b-4c9ae9b58345` | `85a0a63e-d897-3be7-880d-9743b1f6c94c` | `PATH_EXACT test_todo.py` |     1 |

Shared WorkGroup: `fd42d9b3-5333-3a72-8cf0-20603ddda286`.

### Projection/action trace

The following are the relevant exact projection/action pairs from the raw
JSONL trace. Repeated unchanged projections were also executed; they are
included here once with their stable identifiers.

1. Both agents first received ordinary `IMPLEMENT` with no
   `recommendedTool` or executable arguments and performed their assigned
   visible repository work. They did not inspect `.synesis/**`.
2. Agent A received and executed:

   ```text
   get_next_action({})
   -> recommendedTool=request_coordination
      arguments={
        kind: "work_group_join",
        payload: {
          workGroupId: "fd42d9b3-5333-3a72-8cf0-20603ddda286",
          intentId: "85a0a63e-d897-3be7-880d-9743b1f6c94c",
          proposal: "Review the immutable snapshot for this work group"
        }
      }
   -> request_coordination(...) -> request f136e848-ec5a-4d05-988d-693a05615eb4
   ```

   The request was created pending and later accepted by Agent B.
3. Agent B received and executed the exact projected owner response:

   ```text
   respond_coordination(
     kind="coordination_response",
     payload={
       coordinationRequest: "f136e848-ec5a-4d05-988d-693a05615eb4",
       coordinationStatus: "ACCEPTED",
       proposal: "admitted"
     })
   ```

4. Agent A received a REVIEW grant projection and executed the exact
   single-use admission/consumption request:

   ```text
   request_coordination(
     kind="work_group_join",
     payload={
       grantId: "ac63417d-5bb7-3d0a-950c-0ab774c29b26",
       intentId: "85a0a63e-d897-3be7-880d-9743b1f6c94c",
       claimEpoch: 1,
       workGroupId: "fd42d9b3-5333-3a72-8cf0-20603ddda286",
       targetParticipant: "agt_1b960144-d9d4-3a48-949f-9e4a9be820e6"
     })
   ```

   Agent A then received the exact review context and submitted structured
   ACCEPT for B's snapshot `snap_62c871c6e74a3e7da6fe6ff51e9a932e`.
5. Agent B received the reciprocal owner response projection for request
   `5b9c9b56-f207-47fa-9db9-ab724095ebaf`, executed the exact
   `coordination_response`, then received and executed the exact grant
   consumption request for grant
   `283d8f43-92cc-333a-8ac6-5544a90340ae`:

   ```text
   request_coordination(
     kind="work_group_join",
     payload={
       grantId: "283d8f43-92cc-333a-8ac6-5544a90340ae",
       intentId: "39c57c74-831b-333c-b8dd-bbf1ebdf57b5",
       claimEpoch: 1,
       workGroupId: "fd42d9b3-5333-3a72-8cf0-20603ddda286",
       targetParticipant: "agt_a5050c9b-4e52-3c79-be7b-4c9ae9b58345"
     })
   ```

6. Both producers then received and executed the exact publication projection:

   ```text
   get_next_action({})
   -> reason=snapshot_publication_required
      recommendedTool=finish_lane
      arguments={summary: "Publish the completed immutable snapshot"}
   -> finish_lane({summary: "Publish the completed immutable snapshot"})
   ```

7. Each reviewer received the exact `review_decision` context, inspected the
   immutable snapshot, ran the bounded visible tests, and submitted structured
   ACCEPT:

   ```text
   respond_coordination(
     kind="review_validation",
     payload={
       grantId: "...",
       intentId: "...",
       claimEpoch: 1,
       snapshotId: "...",
       result: "accepted"
     })
   ```

   The first decision returned `workGroupStatus=ACTIVE`; the second returned
   `workGroupStatus=COMPLETED`.

### Snapshots and integration

| Producer | Snapshot                                | Base                                       | Snapshot commit                            | Changed path   |
|----------|-----------------------------------------|--------------------------------------------|--------------------------------------------|----------------|
| B        | `snap_62c871c6e74a3e7da6fe6ff51e9a932e` | `62a2952ff7633302caeb478360e647930f2f3540` | `80b2a4a95d7bd953e8159e3c210eb0e07e8ca134` | `test_todo.py` |
| A        | `snap_0f181e97e99a35b1d952bd76eb9e0b67` | `62a2952ff7633302caeb478360e647930f2f3540` | `a4616d30cb13098b07d365b4400a318fc4a7b991` | `todo.py`      |

Both snapshots were visible to the authorized reviewer and integrated. The
final control checkout was clean at `7038143 Synesis immutable lane snapshot`;
direct control pytest reported `4 passed` after disposable `__pycache__`
artifacts were removed.

### Diagnostic terminal state

```text
AGENT A STATE=COMPLETED
AGENT B STATE=COMPLETED
REQUEST f136e848-ec5a-4d05-988d-693a05615eb4 ACCEPTED KIND=REVIEW
REQUEST 5b9c9b56-f207-47fa-9db9-ab724095ebaf ACCEPTED KIND=REVIEW
WORK_GROUP=fd42d9b3-5333-3a72-8cf0-20603ddda286 STATUS=COMPLETED VERSION=2
LANE_GRANT=ac63417d-5bb7-3d0a-950c-0ab774c29b26 TARGET=agt_1b960144-d9d4-3a48-949f-9e4a9be820e6 EPOCH=1
LANE_GRANT=283d8f43-92cc-333a-8ac6-5544a90340ae TARGET=agt_a5050c9b-4e52-3c79-be7b-4c9ae9b58345 EPOCH=1
```

Doctor was `DEGRADED`: six warnings, zero errors, zero critical findings,
`CLEANUP_RECOMMENDED=false`, `RECONCILIATION_RECOMMENDED=true`,
`REPAIR_AVAILABLE=true`, and `NEXT_ACTION=prepare_repair_plan`. These are the
known fixture warnings and did not block this run.

## Ordinary follow-up: no protocol-conformance instruction

### Fixture and durable state

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0534-followup-2026-08-25-001`
- Project ID: `ea64f44d-1d1b-4337-bf58-4aab9b0e8963`
- Initial Git fixture commit: `683a2df baseline Todo fixture`
- Synesis-managed baseline: `cc1e067`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0534-followup-2026-08-25-001\logs`
- MCP connection IDs:
  `syn039-cp0534-followup-001-agent-a` and
  `syn039-cp0534-followup-001-agent-b`

| Agent | Participant                                | Intent                                 | Claim                     | Epoch |
|-------|--------------------------------------------|----------------------------------------|---------------------------|------:|
| A     | `agt_01ef0c68-5f33-346c-b7fe-cb80cda016c2` | `d67886af-a6a2-382b-ac17-5f59d7e81b7d` | `PATH_EXACT todo.py`      |     1 |
| B     | `agt_eaf4274a-30f9-3381-a719-e5c9ed37c4cf` | `db2d65ca-4fae-3f21-be75-d647aab1849a` | `PATH_EXACT test_todo.py` |     1 |

Shared WorkGroup: `d1815a35-a4d5-3f9c-aa89-9531ea5652f9`.

Request `970c35e9-f2e8-459b-9c96-dc49d21ea218` was accepted. Grant
`0a274d8b-161f-3e8d-bde2-d3191c5ec2ed` was single-use, targeted Agent B's
participant, reviewed Agent A's intent at epoch 1, and was consumed.

### First ordinary boundary

Agent B executed the exact projected admission request, Agent A executed the
exact owner response, and Agent B consumed the exact REVIEW grant. Agent A
then received and executed:

```text
get_next_action({})
-> reason=snapshot_publication_required
   recommendedTool=finish_lane
   arguments={summary: "Publish the completed immutable snapshot"}
-> finish_lane({summary: "Publish the completed immutable snapshot"})
-> status=ready, reason=validation_required
   recommendedTool=request_coordination
   arguments={
     kind: "work_group_join",
     payload: {
       intentId: "db2d65ca-4fae-3f21-be75-d647aab1849a",
       proposal: "Review the immutable snapshot for this work group",
       workGroupId: "d1815a35-a4d5-3f9c-aa89-9531ea5652f9"
     }
   }
```

The `finish_lane` mutation did not fail. Agent A's provider turn ended
without executing that exact returned continuation. This is the first
ordinary-run boundary. Agent B correctly consumed the existing grant,
inspected snapshot `snap_9b589858cc39f99d4a70d057c4bf1aab`, ran its bounded
tests, and submitted the exact structured ACCEPT:

```text
respond_coordination(
  kind="review_validation",
  payload={
    result: "accepted",
    grantId: "0a274d8b-161f-3e8d-bde2-d3191c5ec2ed",
    intentId: "d67886af-a6a2-382b-ac17-5f59d7e81b7d",
    claimEpoch: 1,
    snapshotId: "snap_9b589858cc39f99d4a70d057c4bf1aab"
  })
```

Synesis returned `ACCEPTED` with `workGroupStatus=ACTIVE`. Since A did not
request reciprocal admission, B's later empty-argument polls returned bare
`IMPLEMENT` with no executable lifecycle action. The missing continuation,
not a failed projection, prevented B's snapshot and WorkGroup closure.

### Ordinary visible work and terminal state

- A changed only `todo.py`; its isolated pytest run passed `3/3`.
- B changed only `test_todo.py`; its isolated pytest run passed `4/4`.
- A's snapshot:
  `snap_9b589858cc39f99d4a70d057c4bf1aab`, base
  `cc1e0675923411ed6b0f666436bafa389d9f71fd`, commit
  `551ba591d9a5e9c1aa3c24bec0ff3e3428a4b7c2`, changed `todo.py`,
  integration `integrated`.
- Control checkout was clean at `db3f3f0 Synesis immutable lane snapshot`;
  direct control pytest reported `3 passed` after disposable `__pycache__`
  artifacts were removed.

```text
AGENT A STATE=COMPLETED
AGENT B STATE=ACTIVE
REQUEST=970c35e9-f2e8-459b-9c96-dc49d21ea218 STATUS=ACCEPTED KIND=REVIEW
WORK_GROUP=d1815a35-a4d5-3f9c-aa89-9531ea5652f9 STATUS=ACTIVE VERSION=1
LANE_GRANT=0a274d8b-161f-3e8d-bde2-d3191c5ec2ed TARGET=agt_eaf4274a-30f9-3381-a719-e5c9ed37c4cf EPOCH=1
```

Doctor was again `DEGRADED` with six warnings, zero errors, and zero critical
findings. This remained separate from the ordinary lifecycle boundary.

## Verification and disposition

- No production code changed during either acceptance run.
- The diagnostic completed the target lifecycle:
  `IMPLEMENT -> shared WorkGroup -> REVIEW admission -> owner response ->
  grant consumption -> snapshot publication -> snapshot review -> structured
  ACCEPT -> integration -> WorkGroup COMPLETED`.
- The ordinary product acceptance did not complete because a provider ignored
  an exact continuation returned by `finish_lane`.
- No production change is authorized by this evidence. Do not broaden SYN-039
  into provider retry/orchestration behavior from an ignored action.
- The root `McpServerTest` Git subprocess stall, bootstrap migration failures,
  and Doctor warnings remain separately classified unless future evidence
  proves direct causality.

Focused tests, validators, Javadocs, and `git diff --check` are recorded in
the accompanying checkpoint. No remote state was modified and no SYN-040 was
created.
