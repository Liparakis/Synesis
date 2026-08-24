# SYN-039 CP-0479 — Agent contract clarification and two acceptance runs

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Production changes: agent-facing guidance only; no lifecycle semantics changed

## Contract audit and bounded change

The CP-0478 audit found that the agent-facing contract did not distinguish
ordinary implementation work from a concrete Synesis lifecycle action:

- Root `AGENTS.md` described the ten-tool boundary and isolated worktrees but
  did not explain an `IMPLEMENT` response with no executable action.
- `docs/providers/codex.md` described installation and trust state, not the
  `IMPLEMENT`/visible-worktree distinction.
- The managed `synesis-manual` instructed agents to follow projected actions,
  but did not say what to do when `recommendedTool` and `arguments` were
  absent.
- The `get_next_action` catalog description only said that it retrieves the
  highest-priority coordination item.
- Existing workspace tests correctly reject `.synesis/project.json` and other
  private metadata with `invalid_path`; that protection was preserved.

The smallest clarification was added to:

- `workspace/src/main/java/org/synesis/workspace/application/provider/ProviderManualService.java`
  — the managed manual now says that `IMPLEMENT` without a concrete executable
  action means continue normal coding in the visible assigned worktree, never
  inspect `.synesis/**` with workspace file tools, and return to Synesis after
  progress or when collaboration is required.
- `mcp-contract/src/main/java/org/synesis/mcp/contract/McpToolCatalog.java`
  — the `get_next_action` tool description carries the same distinction and
  requires exact execution when a concrete tool and arguments are present.
- Deterministic coverage was added to
  `ProviderManualServiceTest` and `McpToolCatalogTest`.

No path protection, ownership, grant, snapshot, validation, integration, or
cleanup behavior was changed.

## Shared acceptance setup

Both runs used fresh Git + Synesis projects, two independent `gpt-5.6-luna`
agents, the rebuilt bundled MCP, explicit project pins, distinct connection
instance IDs, and no manual message relay or lifecycle intervention.

Bundled MCP identity after rebuild:

- Executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- SHA-256: `2C42A6ABFA421E2AE25974305D8C90CBFA627EA8B8679E1B202EEB63E810A262`
- Protocol: `2025-06-18`
- Version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Catalog: exactly ten MCP tools

## Run 1 — bounded diagnostic with contract guidance

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0479-001`  
Project: `c3a799c0-3eee-480c-acc1-60d52ee9f883`  
Baseline: `aa1a164`; managed baseline: `0ca19d6`

Agent B became the owner with participant
`agt_2d548c0d-90e0-379a-aa95-bb9afb741a37`, intent
`e80cb022-eb26-3fce-a6d9-292340fbb04c`, WorkGroup
`6e69d6f1-75b7-352b-a582-13a2a0a011cb`, and the `tests` claim at epoch 1.
Agent A discovered the WorkGroup as participant
`agt_ff6e4854-5c6d-3255-abe7-6c49c50fdf3e` and followed the projected review
admission request. The review requests were
`c69fec4f-ee7e-4209-a1a7-c81bedcc37ae` and
`ee9b71e2-4dea-4508-8814-82d83668b669`; the issued single-use grants were
`96bb621c-6352-392e-b730-f2feb980d007` and
`5eec89da-e83e-391d-b48c-5a786c5e9147`.

The owner continued visible coding after an `IMPLEMENT` response with no
concrete lifecycle action. It added the requested tests and `pytest` passed
4/4. It then chose `finish_lane` while `get_next_action` still projected
ordinary `IMPLEMENT` with only permitted operations; Synesis correctly
returned `task_not_ready / retry`. After a status check and cleanup of the
generated `__pycache__`, the retry succeeded:

- snapshot: `snap_701ed39a6a23b972bc5d723a4cbd630a`
- control checkout: commit `24ed805` (`Synesis immutable lane snapshot`)
- integration: `integrated`

The run therefore reached snapshot publication and integration. The captured
agent stream does not contain a structured reviewer ACCEPT/REJECT decision,
so full review-validation completion is not claimed. Final direct coordination
status reported `PROJECT_SEQUENCE=0`, `TASKS=0`, and `OWNERSHIPS=0`; three
managed worktrees remained. Doctor was `DEGRADED` with six warnings.

Classification: the clarification improved the ordinary IMPLEMENT path, but
an agent still selected an unprojected `finish_lane` before it was ready. This
is agent compliance evidence, not a lifecycle projection defect.

## Run 2 — ordinary acceptance without diagnostic guidance

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0479-002`  
Project: `981c2b2c-e0b5-47ae-a2ff-d1e69fc01b28`  
Baseline: `f9a8568`; managed baseline: `ad9e666`

The two prompts contained only complementary Todo coding responsibilities and
did not describe Synesis lifecycle steps. Both agents reached ready/isolated
sessions and ordinary `IMPLEMENT` projections.

Agent A implemented `TodoList.complete(index)` and added completion tests;
`pytest` passed 3/3. Its snapshot was published as
`snap_694e9a6456a6a9469c65fa7ab817b7d4`, commit
`073c2540b6e5a124519dbc0407c413bd51bfbfef`, but `finish_lane` returned
`policy_denied` with `integrationState=integration_blocked`.

Agent B initially attempted an overly broad `.` claim. `ensure_session`
correctly rejected it with `protected or traversal selector`; the retry
without that claim succeeded. It then added usage tests; `python -B -m pytest
-q` passed 4/4. It did not form a shared WorkGroup with Agent A. Its snapshot
was published as `snap_8e9d2df7bbc472abefe455b9e2131cec`, commit
`e0835c91bdd135e28b5bc1502f9eead7c85ebb29`, but integration was also blocked.

Before its final `finish_lane`, Agent B supplied this non-projected
integration-check fact set:

```text
tests=python -m pytest -q
result=4 passed
changedFiles=[test_todo.py]
implementationUnchanged=[todo.py]
```

Synesis returned:

```text
integration_conflict / TESTS_FAILED
accepted=false
```

The later `finish_lane` returned `policy_denied / integration_blocked` with
the published snapshot. This is not an exact projected-action failure: the
agent chose the integration-check call and then `finish_lane` while its
`IMPLEMENT` projection had no concrete lifecycle tool. The run also never
formed the required shared WorkGroup, so review, grant, validation, and
accepted integration were not reached.

Final ordinary-run coordination status reported sequence zero, zero tasks,
and zero ownerships, but the control checkout remained at managed baseline
`ad9e666` and three managed worktrees remained. Doctor was `DEGRADED` with
the same six warning classes as the diagnostic run.

## Result and next blocker

The contract clarification is verified and hidden-path protection remains
fail-closed. CP-0479 does not prove clean SYN-039 completion.

The next concrete acceptance blocker is ordinary-agent coordination
discoverability/compliance: without diagnostic guidance, the two agents did
not converge on one shared WorkGroup, and both independently reached
integration-blocked snapshots. The `TESTS_FAILED` response is preserved as a
secondary integration-gate observation, but it was triggered by an
agent-selected, non-projected integration-check payload and is not yet
authorized as a production fix.

The Git subprocess stall, bootstrap migration failures, and Doctor warnings
remain separately classified.
