# ADR-0091 — A blocked part of a run asks for an operator and records where it failed

## Status

Accepted on 2026-10-12 by the repository owner. Built on the same day except
where "Measured on 2026-10-12" and "Built, and not built" below say otherwise.

Step 1 of three. ADR-0092 (one public contract per owner) and ADR-0093 (agent
conversations) follow it and do not depend on it.

Mode: evolution of the existing system. Claims are marked VERIFIED (read in
this repository or in a recorded run), DERIVED, ASSUMED or UNKNOWN.

## Context

In a real two-agent run (`u3-ll`, build `remaster22`) one agent wrote two files
into the control checkout with its provider's own file tool. Everything after
that went wrong quietly.

What the repository does today, all VERIFIED:

- A Codex agent is started as `codex exec --json --cd <control root>`
  (`CodexProviderIntegration`). Its working directory is the control checkout.
  What it may write is decided by the user's own Codex configuration. On the
  machine of the run that was full disk access with no approval.
- The only guard is one `PreToolUse` hook matching `^apply_patch$`
  (`.codex/hooks.json`). In the control checkout the hook routes the patch into
  the session's worktree and denies the native write
  (`HookApplicationService.toolEvent`). A write made by a shell command is not
  matched by the hook at all.
- In the run the native write completed, and the agent's log holds no trace of
  the hook. The hook writes no record of its own, so whether it ran cannot be
  told.
- Integration refuses a control checkout holding changes Synesis did not make
  (`ControlBranchAdvancementService.hasBlockingControlChanges`). That is
  intended: the files may be the user's unsaved work, and the checked tree must
  be the tree that was verified.
- The refusal is recorded as a failed integration attempt with the `git status`
  text as its reason. The snapshots of that attempt become
  `INTEGRATION_FAILED`, the state documented for a failed build or an
  unresolvable merge. That state is not in the set the integration pump retries
  (`TaskCompletionProjection.isIntegrationEligibleState`). So after a person
  cleans the checkout, the work would still never be integrated.
- The owner of the refused snapshot was shown `INTEGRATION_FAILED` with no
  reason, and its next action was an ordinary `IMPLEMENT`. The agent that wrote
  the stray files was refused `workspace_mismatch` when it then used the
  Synesis patch tool for the same paths. Both polled to the limit and stopped.
  The run ended `STALLED`.
- `request_human_help` exists as a next-action value. It is advice to an agent
  in a run with no human. Nothing durable is created and the run controller
  does not know.

Two other failures in the same build left nothing to diagnose:
`ensure_session` answered `SESSION_BINDING_FAILED` once with the cause
discarded (`ProviderSessionBindingService.ensure`), and a hook denial leaves no
record.

UNKNOWN: why the hook did not stop the write. One occurrence in 361 Codex
sessions.

## Decision

Decided by the repository owner in discussion on 2026-10-11.

### An operator request

- When Synesis meets a block that only someone with authority over the project
  can clear, it records an **operator request**: what is blocked, the exact
  condition, and what would clear it. The first two conditions are a control
  checkout with changes Synesis did not make, naming the files, and an
  integration conflict that needs repair.
- An operator request is durable project state, in the coordination log, so
  every projection and every agent's next action can see it.
- A human answers it, from the CLI, and reads it there and in the web UI. An
  operator agent is not part of this step. It can be added later on the same
  record: launched by the user with stated permission, and limited to
  reversible actions.
- A request closes when Synesis observes that its condition no longer holds,
  not when someone says so. For the dirty checkout, that is the next
  integration attempt finding the checkout clean. The CLI command that
  "resolves" a request asks Synesis to check again.
- Synesis does not move, adopt or delete the files itself. It cannot tell an
  agent's debris from the user's work.

### Only the blocked part stops

- A lane whose next step needs the blocked thing is answered `BLOCKED` with
  the operator request and told to stop: no polling, no wake-up.
- A lane that can still do useful work keeps working. With a dirty control
  checkout, lanes still implement and still publish their snapshots; nothing is
  integrated until the request closes.
- A refusal by an outside, temporary condition does not fail the work. The
  snapshots of an attempt refused for a dirty checkout return to the state they
  had, and are integrated when the condition clears.
- A run ends when the only work left is blocked. Its result is a new outcome,
  `NEEDS_OPERATOR`, with the open requests. Starting the run again after the
  operator has acted continues it.

### A failure point is recorded

- Every operator request, and every answer that reports an internal failure,
  carries a **failure point**: the component and operation, the identifiers
  involved (attempt, snapshot, lane, session), the log sequence and time, and
  the exact reason, including the raw output of the tool that refused.
- For an internal failure the exception, its cause chain and its stack are
  kept. They go to a diagnostics file under the project's local, unshared
  state, and the answer and the operator request carry its identifier. The
  coordination log holds the bounded reason, not the stack.
- No path may turn a caught exception into an answer without recording its
  failure point. `SESSION_BINDING_FAILED` is the first to be corrected.
- Every hook invocation records that it ran, what it decided and why.

### The guard

- The hook miss is reproduced first, now that an invocation leaves a record.
- A second layer is added so one missed hook cannot dirty the control checkout.
  Which layer is REQUIRES-MEASUREMENT; the candidates are below.
- Synesis looks for foreign changes in the control checkout when it serves a
  next action, not only when it advances the branch, so the operator request
  appears when the stray file does and not at the first integration.

## Alternatives considered

- **Tell the agents only.** Rejected: the agent that wrote the files already
  knew, and correctly refused to clean up. Nobody able to act was told.
- **Integrate over untracked files that the merge would not overwrite.**
  Rejected: the control tree would differ from the tree that was verified. In
  the run the stray test file made the project's own test command fail.
- **Synesis adopts stray files that fall inside a lane's claims.** Rejected:
  provenance is a guess, and the action changes the user's checkout.
- **An operator agent now.** Deferred by the owner. It needs an authority model
  that does not exist yet.

Candidates for the second guard layer, none chosen:

| Candidate | Stops a shell write | Cost and risk |
| --- | --- | --- |
| Start the agent with its worktree as working directory | Mostly | The worktree is assigned after the session binds; hooks and project instructions are found from the project root |
| Start Codex with a sandbox that makes the control checkout read-only | Yes | Depends on the provider's sandbox on each platform; UNKNOWN on Windows |
| Match shell commands in the hook as well | No, only known forms | A command can write in ways a pattern does not see |
| Detect early and raise an operator request | No, it reports | Cheap, and needed anyway |

## Consequences

- The MCP tool catalog does not change in this step. The CLI gains
  `operator list`, which shows each request in full, and `operator recheck`,
  and `agents run` gains one outcome value. Both are additive.
- One new durable event family in the coordination log, and one diagnostics
  file format in local state. ADR-0088 applies to both.
- A run that needs a person now says so and costs nothing while it waits. It
  still does not finish without that person.
- More is written on every failure. The diagnostics file needs a size bound and
  a retention rule; ASSUMED small, to be measured.
- An agent shown the names of the blocking files may still delete them with its
  provider's own tool. The wording says not to; only the second guard layer
  prevents it.

## How it will be checked

- A test places a stray file in the control checkout: an operator request names
  it, the affected lane is `BLOCKED` with that request and is not told
  `IMPLEMENT`, the snapshot is not `INTEGRATION_FAILED`, and after the file is
  removed the snapshot is integrated with no agent action.
- A test of the run controller: unaffected agents keep running, and the outcome
  is `NEEDS_OPERATOR` when only blocked work is left.
- A test that every answer with status `FAILED` carries a failure-point
  identifier, and that the diagnostics entry holds the cause.
- A test that a hook invocation leaves a record.
- A real run in which the bootstrapper injects the stray file, as it may inject
  a process failure, and independently checks all of the above.

## Measured on 2026-10-12

With each hook invocation recorded, the miss was reproduced in a disposable
project on Codex CLI 0.162.0 (build `remaster23`). All VERIFIED.

- **The Codex hook never runs in a headless session.** An agent started by
  `synesis agents run` and told to use its own file tool wrote the file into
  the project checkout. No hook invocation was recorded, for the write or for
  the session start. The project's own status had said so all along:
  `TRUST_STATUS=REVIEW_REQUIRED`, `NATIVE_MUTATION_INTERCEPTION=UNPROVEN`. Codex
  runs a project's hooks only after a person has reviewed them, and a headless
  session has nobody to do that. So no Codex session in any recorded run was
  guarded; the agents kept to the Synesis tools because the manual says to.
- **Codex's read-only sandbox blocks the write.** With
  `sandbox_mode=read-only` the native patch was refused ("writing is blocked by
  read-only sandbox") and a shell command that writes did not run. A shell
  command that reads still ran. The checkout stayed clean.
- **Read-only alone also blocks the Synesis tools.** Every tool call then
  "requires approval, but approval policy is never".
- **Pre-approving the Synesis server's tools restores them.** With
  `mcp_servers.synesis.default_tools_approval_mode=approve` added,
  `ensure_session` answered `ready` and the native write was still refused.

So the second layer for Codex is two launch settings, given by Synesis for the
sessions it starts: the read-only sandbox, and approval of the Synesis server's
own tools. It matches how Claude Code sessions are already started, limited to
the Synesis tools and read-only tools.

The repository owner decided on 2026-10-12 to build it, and it is built:
`CodexProviderIntegration` gives both settings to every session it starts and
resumes. It changes a rule the repository stated on purpose, that Synesis
passes no sandbox argument; `HeadlessSessionContractTest` and the command
reference now state the new rule. The sandbox is tightened and, for the Synesis
tools only, approval is given. A probe through `synesis agents run` on build
`remaster25` was refused its native write and kept its Synesis tools.

Not chosen: `--dangerously-bypass-hook-trust` would make the hook run, covers
the patch tool only, and skips review of every hook in a file the user shares.

## Open questions

- None on which answers raise an operator request: settled below.
- The Codex launch settings are not tried on macOS or Linux.

Settled on 2026-10-12: Synesis raises an operator request for two conditions
in this step, a control checkout with changes it did not make and an
integration conflict that needs repair. An answer that says
`request_human_help` after a malformed call says to correct the call and try
again instead; no person is needed there.

The guard is taken in this order: the hook records each invocation, the miss
is reproduced, foreign changes are detected when a next action is served, and
then the provider's own sandbox is tried on Windows as the second layer.

Settled on 2026-10-12: an agent may raise an operator request itself. It does
so by addressing the operator with the message tool of ADR-0093, so it arrives
with step 3. The operator request of this step is built to carry a request
raised by a lane as well as one raised by Synesis, and to be answered by an
operator agent later as well as by a human now.

## Built, and not built

Built: the operator request for a control checkout with foreign changes, its
closing by observation, the pending outcome for a refusal by the checkout or by
a moved control head, the `OPERATOR_REQUIRED` answer for lanes that wait on
integration, `NEEDS_OPERATOR`, the two CLI commands, failure points for the
paths listed in `docs/providers/mcp.md`, and the hook record.

Not built:

- An operator request for an integration conflict. Agents already repair
  conflicts through the repair lane, so a request for each would end runs that
  can continue. It would belong to a conflict nobody is left to repair, which
  has not been observed.
- (Built since, ADR-0094: an operator request for a project validation command
  that fails with no lane left to answer for it.)
- A failure point on every `failed` answer. The paths that swallowed a cause in
  the recorded runs are covered; others are not.
- Operator requests in the web UI.
