# Provider collaboration acceptance — 2026-07-28

## Evidence boundary

This report distinguishes MCP configuration, MCP connection, MCP mutation,
and native-hook evidence. A provider CLI being installed is not proof that its
MCP session completed.

| Provider | Configuration discovered | Real MCP connection | MCP mutation | Native hook |
|---|---|---|---|---|
| Codex 0.140.0 | `codex mcp list` found `synesis`; entry points to the current local install | Real `codex exec --ephemeral --json --dangerously-bypass-approvals-and-sandbox` initialized Synesis and completed `ensure_session` | Confirmed: exact `src/task_tracker.py` claim returned `status=ready`; a separate claimed probe file was created with `apply_patch` and verified by `read_file` with matching revision hash | Not claimed |
| Claude Code 2.1.59 | Project `.mcp.json` points at the current local install; `claude auth status` is authenticated | Real `claude -p` sessions initialized Synesis and returned structured claim outcomes | Confirmed: `claude_acceptance_probe.txt` was created with `apply_patch` and reread with matching hash `138ed040582d07c2a4aa4beaffd6d5e84252d561413f148535b0db2ac9fc6fd2` | Not claimed |
| Antigravity | `agy.exe` 0.x is installed; both configs point to the current local install; direct MCP process completed initialize, isolated claim, mutation, readback, and clean EOF | Direct MCP transport PASS; model prompt retry omitted/failed to retain structured claims and was blocked by `coordination_intent_required`, with a later provider `internal_failure` | Direct MCP evidence confirmed; model-driven acceptance remains limited by harness behavior | Native-hook maturity not changed |

The local Synesis MCP server itself passes the two-process, 11-tool, revision,
claim, handoff, release, and recovery test suites. Those are Synesis evidence,
not universal provider enforcement evidence. The task-tracker fixture remains
dirty by design and was not reset or modified by this validation.

## Process-level MCP acceptance

Two independent installed Synesis MCP processes were launched against the
task-tracker fixture with provider identities `codex` and `claude`. The Codex
process acquired `src/task_tracker.py`; the Claude process received a
structured `overlapping_claim` response before mutation. After the Codex
process closed, a new Claude MCP process reacquired the same path successfully,
demonstrating clean-EOF release and reacquisition. This proves process-level
MCP collaboration, not that the Claude/Codex model CLIs themselves completed
an authenticated run.

The fixture initially contained historical event records whose shifted wire
codes were decoded as collaboration events. Stable decoding now distinguishes
legacy dependency-invalidated payloads, and `synesis collaboration status`
replays the existing dirty fixture successfully without deleting events.

The provider mutation probe's process ended without a clean collaboration
release event in the fixture; a read-only reconciliation inspection classified
one session as `suspectedStale` and not yet abandonment-eligible. This is
retained as evidence that stale fencing is observable, not treated as proof of
automatic ownership transfer. The fixture's existing claims remain untouched.

## Exact commands

- `claude auth status` → authenticated via `claude.ai`
- `claude mcp add --scope project ...` → task-tracker `.mcp.json` updated to the current local install
- authenticated Claude conflict run → exact `src/task_tracker.py` claim returned `status=blocked`, `reason=overlapping_claim`, with the Codex intent and participant exposed; no files or shell commands used
- authenticated Claude mutation run → isolated `apply_patch`/`read_file` probe returned matching revision/content hash `138ed040582d07c2a4aa4beaffd6d5e84252d561413f148535b0db2ac9fc6fd2`
- authenticated Claude lifecycle run → `ensure_session(refresh=true, task.claims=[])` returned `status=ready`; a subsequent exact claim reacquired `claude_release_probe.txt` successfully
- authenticated Claude contract run → `describe_required_capability(collaborationOperation=publish)` returned JSON-safe contract revision 1 with content hash `975e11eb6fa9b7987fd1bfe3845d902f0a70a80524d4e707166e5dd373bfea0a` after the MCP serialization fix
- authenticated Claude contract status run → `describe_required_capability(collaborationOperation=status)` returned JSON-safe contract revisions and an empty dependency list
- `./gradlew.bat :mcp:test --no-daemon` → PASS, including publish and status JSON-projection regressions; `./gradlew.bat :cli:installDist --no-daemon` → PASS after the fix
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → `BUILD SUCCESSFUL` (50 actionable tasks) after the MCP projection fix
- `codex mcp remove synesis` followed by `codex mcp add synesis -- ...\synesis.bat mcp --provider codex` → current local install
- `codex exec --ephemeral --json ...` → first attempt was cancelled by the harness before a result
- `codex exec --ephemeral --json --dangerously-bypass-approvals-and-sandbox ...` → real `ensure_session` completed with `status=ready` and an isolated worktree; no source file was edited
- real Codex MCP mutation probe → `apply_patch` created `provider_acceptance_probe.txt` in the isolated worktree with revision `1fb78c34cf37b61394f119294c12ccc71333f571bcc8d2a4e9ed58916433be72`; `read_file` returned the same content hash
- `claude -p ... --output-format json --permission-mode bypassPermissions` → `Not logged in · Please run /login`
- `synesis reconcile --dry-run --verbose --json --project "C:\\Users\\Liparakis\\Desktop\\Test case"` → one `suspectedStale`, zero executable abandonment actions
- `C:\\Users\\Liparakis\\AppData\\Local\\agy\\bin\\agy.exe --print ...` after quota reset → `ensure_session` returned `ready`; the model-driven mutation omitted/failed to retain the structured claim and returned `coordination_intent_required`; a stricter retry returned provider `internal_failure`
- direct installed Antigravity MCP process (`synesis.bat mcp --provider antigravity`) → `initialize` returned server `synesis`; `ensure_session` acquired an isolated worktree and exact claim `antigravity_direct_probe.txt`; `apply_patch` completed with revision/content hash `bc004ae201cf91b9d3ba0b7aa5b71dcbe7f7b5b52b7d2f2d6efa3acf0a62bf9b`; `read_file` returned the same hash; process exited cleanly on stdin EOF
- The direct process result is MCP transport/enforcement evidence. The model prompt failure is recorded separately and does not change native-hook maturity.
- separate direct Codex and Claude MCP processes → collaboration discovery initially exposed a JSON projection defect (`unsupported JSON value`); after the shared MCP projection fix, status returned JSON-safe intents, participants, claims, and requests
- direct process handoff → Codex offered `HANDOFF` request `d6bffc19-1260-4127-8faf-1f21b1df2e10` to the active Claude participant; Claude accepted through the shared CLI adapter; the transferred intent advanced to version 2 with Claude ownership, and Claude created `handoff_direct_owner2.txt` through MCP with matching mutation verification revision `2a2cd3d940c7bad4fe9cdef03c4f34ee4b58ab0be9c07f9a54471328576c6956`
- `./gradlew.bat :mcp:test --no-daemon` → PASS, including the new JSON-safe collaboration discovery regression; `./gradlew.bat :cli:installDist --no-daemon` → PASS with the fixed MCP jar installed
- real deleted-chat recovery v3 → direct Claude MCP process acquired `deleted_chat_direct_probe_v3.txt`; its lease file existed before forced JVM termination; dry-run classified the session as suspected-stale during grace and abandonment-eligible after 300 seconds; prepared/executed reconciliation completed 15/15 actions with `controlCheckoutModified=false`; status then reported participant `agt_5953b5cf-60be-383f-8f64-7c93c6a90016` as `ABANDONED` with no active claim
- old-epoch fencing → a new MCP process reusing the abandoned connection ID received `status=blocked`, `reason=workspace_generation_changed` when attempting to reacquire the path; no mutation was attempted
- the recovery run also exposed and fixed first-ensure lease creation and reconciliation event-head refresh defects; deterministic lease, reconciliation, and MCP fencing regressions now pass
- `python -m pytest -q` in the task-tracker fixture → `45 passed in 0.11s`
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → `BUILD SUCCESSFUL` (50 actionable tasks)
- direct installed launcher probe → valid MCP `initialize` response with server name `synesis`
- `./gradlew.bat check --no-daemon --dependency-verification=strict` → PASS
