# SYN-039 unattended Todo acceptance — CP-0474 — 2026-08-24

## Result

The current bundled Synesis MCP distribution removed the prior pre-coordination
readiness blocker. Two independent GPT-5.6 Luna High agents reached an
isolated, project-pinned session and entered the real review lifecycle without
manual relay or lifecycle intervention. The run then stopped at the first
post-review blocker: the implementer did not publish the required immutable
snapshot. No production code changed in this reproduction.

## Distribution and readiness evidence

- Repository: `C:\Users\Liparakis\Desktop\Synesis`, branch `master`, HEAD
  `0dce369`; worktree clean before the run; 13 commits ahead of `origin/master`.
- Disposable project:
  `C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0474-20260823-235645`.
- Project ID: `bd177c7b-bfe0-4cf6-958d-c89c67aa67a0`.
- Control baseline after setup: `38f63a344e5592df126856dba682260a8dea2f62`.
- Current bundle:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`.
- MCP executable configured for Codex:
  `...\bin\synesis-mcp.exe`, SHA-256
  `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`.
- Codex configuration used the exact command and project pin:

  ```toml
  [mcp_servers.synesis]
  command = 'C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe'
  args = ["mcp", "--provider", "codex", "--project", "C:\\Users\\LIPARA~1\\AppData\\Local\\Temp\\syn039-unattended-todo-cp0474-20260823-235645"]
  ```

- Direct preflight startup reported `version=0.1.0-SNAPSHOT` and
  `commit=bc334ac`; initialize returned protocol `2025-06-18`, and `tools/list`
  returned exactly 10 tools.
- During the unattended run, two distinct MCP processes were observed from
  the current bundle, each launching its bundled Java runtime with the exact
  disposable project root. The prior user-level launcher was not used.
- Both sessions reached `ready / isolated` and created separate bindings.

## Autonomous lifecycle evidence

- Agent A: app agent `01a03069-e471-79f0-8ded-e6f74176f579`; Synesis
  participant `agt_1d9ff29b-8df8-3731-86a5-e2b3770eb93f`; lane/intent
  `997bfba1-9e50-3fe2-8723-c7342848293c`.
- Agent B: app agent `01a03069-e5b0-7790-9afc-f437d4bd5815`; Synesis
  participant `agt_756d9bcc-314d-36ff-bb9c-de9f72bef7f9`.
- WorkGroup: `33e8329c-fd66-3174-9e3f-f115f6dae550`.
- Initial claims remained limited to `todo.py` and `test_todo.py`.
- Agent A autonomously added `TodoList.complete(title)` and a regression
  test; its isolated pytest run passed `4` tests.
- REVIEW request: `2bb00015-523b-4c9c-b604-66ea1b31539c2`.
- REVIEW grant: `496f1893-ca32-3939-82a1-24f860dea86a`.
- Agent B discovered the WorkGroup, obtained the targeted review authority,
  and consumed the single-use grant autonomously. It did not claim or edit
  the implementer's files.
- Agent A's projected next action was `PUBLISH`, with
  `snapshot_publication_required` and action ID
  `7e111de83-259d-377c-8f7e-8f7978c8880c`.
- Agent A's `finish_lane` retries returned the fail-closed result
  `status=blocked`, `reason=task_not_ready`, `nextAction=retry`.
- Snapshots: none. Validation: not reached. ACCEPT/REJECT: not reached.
  Integration: not reached. WorkGroup closure: not reached.
- Agent B correctly stopped at `SNAPSHOT_PENDING` with only `wait` /
  `get_next_action` projected; it did not guess, claim, or bypass publication.

## Final state

- Control checkout remained clean at baseline `38f63a3`; no accepted Todo
  implementation reached the control checkout.
- Final `synesis coordination status` was `PASS`, sequence `0`, with zero
  projected tasks and zero control-checkout ownerships. The active WorkGroup
  and lane evidence remained in the participant/session projections and raw
  coordination event store.
- Final Doctor was `DEGRADED`, with five warnings: one stale session lease,
  durable command namespace reconciliation, command retention/capacity, and
  two provider-migration warnings. It reported
  `reconciliationRecommended=true`; no repair was performed.
- The existing Git subprocess stall and bootstrap migration-test failures
  remain separate verification issues; neither was reached by this
  acceptance run.

## Boundary conclusion

The stale/incompatible MCP distribution was confirmed as the CP-0473
readiness cause and was excluded from this run. SYN-039 now has a real,
distribution-correct lifecycle reproduction through review-grant consumption.
The next narrow implementation slice is to trace why the owner does not
execute the already projected snapshot-publication/`finish_lane` action after
the REVIEW grant is consumed. Do not broaden into cleanup, Doctor, ownership,
or integration redesign until that transition is fixed and rerun.
