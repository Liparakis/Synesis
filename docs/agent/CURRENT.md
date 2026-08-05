# Current Task

## Identity

- Task ID: SYN-038
- Status: DONE (App Server lifecycle and durable project-command extension
  complete)
- Priority: P0
- Started checkpoint: CP-0408
- Last completed phase checkpoint: CP-0447
- Extension bookkeeping checkpoint: CP-0448
- Latest implementation checkpoint: CP-0457
- Closure checkpoint: CP-0458
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0043, ADR-0044

## Objective

Extend the completed reliable Codex App Server lifecycle owned by the existing
`synesis coordination serve` process with durable project-command admission
across Codex interruption. Begin with the bounded namespace, permanent-lock,
format-compatibility, process-anchor, and two-process fixture spike, then add
typed request replay/conflict and release/reacquire command admission without
changing the ten-tool MCP boundary or existing App Server ownership.

## Work completed

- `CoordinationServeCommand` now constructs and retains `ProjectRuntimeHost`
  for the life of the existing loopback coordination server and mounts the
  Codex-only `/codex-lifecycle/v1` route.
- `ProviderSessionCommand` resolves the existing binding/session workflow,
  announces and acquires the exact WorkIntent claim, freezes binding,
  participant, lane/epoch, worktree, control-root, and MCP identities, and
  submits an immutable signed START envelope.
- `ProjectRuntimeHost` owns per-binding attachment locks, process attachments,
  lifecycle checkpoints, idempotency, WAIT capacity, reconciliation, and
  diagnostics.
- The Codex-only protocol client uses the generated local schema projection,
  bounded raw frame/body handling, late-response tombstones, event-driven WAIT,
  bounded evidence queue/journal/retention, repeated-discovery hard stop, and
  separate interruption/cancellation evidence surfaces.

## Extension implementation state

- Bookkeeping is reconciled as an extension of SYN-038; the previous App
  Server commit, checkpoints, ADR-0043, and acceptance evidence remain intact.
- No SYN-038 durable command namespace, permanent command locks, command
  records, or typed request replay path existed before this extension.
- The implementation slice is complete. The bounded namespace/lock/format/
  process-anchor spike and deterministic two-process fixtures are preserved in
  the implementation commit.

## Extension verification

- Added host-wide command namespace skeleton with permanent `namespace.lock`
  and physical-worktree lock objects. Mutable namespace/scope/anchor metadata
  uses unique sibling temporaries and atomic replacement.
- Added compatibility/integrity metadata, newer-format fail-closed reads,
  verified real-path worktree locators, typed request-ID canonicalization,
  process-anchor persistence, durable command records, and bounded command
  protection.
- Added strict bounded MCP framing, exact process-identity capture, optimistic
  WorkIntent mutation preconditions, read-only existing-request lookup, phase
  revision gates, bounded namespace reconciliation, supported older-format
  migration evidence, cleanup/repair fail-closed guards, and terminal-history
  compaction through the existing cleanup entry point.
- Focused `ProjectCommandNamespaceSpikeTest`, `ProcessCommandRunnerTest`, and
  `McpFrameReaderTest` pass. The `McpServerTest` Git setup hang is fixed through
  the shared bounded runner; the class now passes 32 tests.

- Durable-command verification: the focused MCP class initially exposed a
  deterministic `LEASE_RENEWAL_FAILED` response at line 535. The exact cause
  was `PARTICIPANT_NOT_FOUND`: a no-claims session has an active lease but no
  collaboration participant. Durable admission now uses `heartbeatIfPresent`,
  ignoring only that absence while preserving all other lease/readiness failure
  paths. The lease was renewed once; scope/anchor persisted at revision 1; no
  STARTING record was written before the fix.
- The shared Git runner now closes stdin, merges and bounds output, disables Git
  prompts/editors/signing/hooks, disables optional locks and fsmonitor, enforces
  monotonic and wall-clock ten-second deadlines, and terminates descendants.
  Raw Git paths in integration orchestration now use the same runner. Its
  regression suite covers large stderr, stdin EOF, timeout diagnostics,
  descendant cleanup, bounded output, ordinary Git setup, and temporary-index
  setup.
- Full root `check` passed on 2026-08-05 in 8m53s after the doctor regression
  was updated to recognize valid host-wide durable-command retention warnings.

## Closure

- Implementation commit: `ad9fdd8addc9f71e806dfb2da5b5d78f050f87ac`
  (`Complete SYN-038 durable project-command extension`).
- Prior evidence CP-0457 remains preserved; CP-0458 records the final closure
  state, including the resolved Git subprocess hang, the `heartbeatIfPresent`
  no-participant admission fix, and all final verification results.
- The previous SYN-038 Codex App Server lifecycle history and acceptance
  evidence were not amended or replaced. No SYN-039 was created.

## Verification

- Focused lifecycle suite: PASS (8 classes, 26 tests).
- Workspace/CLI/MCP compilation: PASS.
- Strict Javadocs for workspace, coordination, CLI, and MCP: PASS.
- Deferred validator and `git diff --check`: PASS.
- Installed schema generation and initialize smoke: PASS; see
  `docs/evidence/syn038-codex-schema-2026-08-03.md`.
- Disposable real-owner acceptance: PASS for authority-before-START, durable
  START, exact thread/turn identity, WAIT control capacity, STEER, INTERRUPT,
  passive exact-thread resume, explicit continuation, duplicate STATUS/WAIT
  replay, and bounded hard-stop results. The restarted-harness run used local
  Codex `0.146.0-alpha.9.2` with the existing global command override and
  reached Synesis MCP `ensure_session`, `apply_patch`, and validation. It
  directly observed an interrupted turn while the foreground MCP barrier
  remained alive, so command cleanup is classified independently and not
  claimed. See
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.
- A second post-restart owner run on fixture `syn038-final2-20260803-1815`
  repeated production START, exact-thread resume, explicit continuation,
  STEER, INTERRUPT, WAIT, duplicate replay, owner exclusion, and hard-stop
  evidence. The existing ten-tool MCP surface separately completed
  `ensure_session`, validation, snapshot, and `finish_lane`; those results are
  explicitly not attributed to Codex because Codex 0.145.0 stopped at MCP
  elicitation. See the same acceptance record.
- A fresh exact binding on fixture `syn038-final3-20260803-1900` ran through
  the installed Codex `0.146.0-alpha.9.2` executable. After passive exact-
  thread resume, the same Codex thread used the ten-tool MCP surface to apply
  the marker, pass validation, publish snapshot
  `snap_f3bd879455900258bb77ca6cea8fac22`, and complete `finish_lane` with
  `task=integrated`. The control checkout ended clean at
  `9e30f4183434a5f282a6e42fb55e4339c0879578`.
- Post-restart direct Codex CLI MCP smoke: PASS. `ensure_session` returned
  `ready`, and a supported `run_command`/`git_log` intent completed with
  `SYN038_MCP_RETRY_OK` after the managed Codex Synesis Manual was refreshed.
  This is separate from the App Server-owner acceptance and does not prove
  command cancellation or lane completion.
- Coordination and CLI checks, strict Javadocs, static/format gates, deferred
  validation, bootstrap Go tests/vet, fixture validation, and diff checks pass.
  The latest sequential root `./gradlew.bat check --no-daemon
  --max-workers=1 --console=plain` completed with `BUILD SUCCESSFUL`; all
  module reports contain zero failures and zero errors. The focused lifecycle
  suite, bootstrap Go tests/vet, deferred and fixture validators, and
  `git diff --check` also pass.

## Historical limitations and resolved failures

- The restarted-harness owner run proves Codex-driven `ensure_session`,
  `apply_patch`, and validation with local Codex `0.146.0-alpha.9.2`. Its
  independent INTERRUPT fixture returned the exact terminal interrupted event
  while the controlled foreground barrier process remained alive; the service
  reported `turn_interrupted_command_state_unconfirmed`, and direct process
  evidence classifies the observed outcome as
  `turn_interrupted_command_remained_active`. Codex/MCP cancellation and
  `ProjectProcessExecutor` tree termination remain unproven.

- The earlier final3 binding remained blocked at `task_not_ready`; that
  historical result is superseded for completion evidence by the fresh exact
  binding run above. The old lane was not replayed or repaired implicitly.

- Historical 0.145.0 runs still record the explicit MCP elicitation boundary;
  SYN-038 remains fail-closed and adds no approval operation. The latest
  0.146.0 run used only the existing command configuration override and
  preserved the exact ten-tool MCP surface.

- After the user harness restart, a fresh `synesis-mcp.exe` process returned
  `initialize`, exactly ten tools, and `ensure_session=ready` on connection
  `syn038-harness-retry-2005`. The app-held MCP child was an older long-lived
  process and returned an internal session-construction error; it was left
  untouched. A fresh `synesis.cmd coordination serve` owner passively
  reconciled the stored App Server to `STOPPED` at revision 46. Its old
  participant could heartbeat, but the prior claim was released, so a STATUS
  request correctly failed closed with `lifecycle_claim_not_acquired`.

- Restarting only the stale desktop MCP Java child closed the app-side MCP
  transport without respawning it; the Codex app process remained alive. A
  standalone post-refresh current-bundle MCP invocation still returned
  protocol `2025-06-18`, ten tools, and `ensure_session=ready`. Do not treat
  the closed app transport as a Synesis implementation failure.

- A second direct post-restart smoke used the current `synesis.cmd` bundle and
  again completed `initialize`, advertised exactly ten tools, and returned
  `ensure_session=ready` with an isolated worktree. The callable desktop MCP
  route still returned `Transport closed`; this confirms the remaining issue is
  connector process state, not the Synesis MCP bundle.

- The 2026-08-05 full-check retry reproduced the test-environment blocker
  where `McpServerTest.setUp` hung while reading a Git subprocess pipe. The
  shared bounded Git runner fixed that hang; the focused MCP class now passes
  32/32 tests and the full `check` is green.
- The later durable-command assertion at `McpServerTest.java:535` returned
  `command_admission_stale` / `LEASE_RENEWAL_FAILED` because a valid no-claims
  session had no collaboration participant. The exact cause is recorded in
  CP-0457/CP-0458; `heartbeatIfPresent` now ignores only that absence and the
  regression passes without weakening lease validation.

## Completion state

The prior SYN-038 App Server lifecycle phase is complete. Its fresh real-owner
run completed Codex validation, snapshot publication, and normal `finish_lane`
integration on the exact resumed thread; its deterministic and repository gates
pass; and its independent command-cleanup limitation remains
`turn_interrupted_command_remained_active`. The durable project-command
extension is complete in `ad9fdd8`; it does not overwrite or reinterpret that
evidence.

## Immediate next action

Verify the pushed SYN-038 closure commit and annotated durable-command tag
before any new task is promoted. Preserve the prior independent
command-cleanup classification and do not add an approval operation or
universal cleanup claim.
