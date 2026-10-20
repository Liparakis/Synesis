# ADR-0099 — Agents are woken, they do not wait

## Status

**Accepted on 2026-10-18 by the repository owner** ("if 0099 is done according to the spec then it is done"), with
the amendment of 2026-10-17 and the fixes of 2026-10-18, including their published-contract changes. Evidence: a real
two-computer run with Codex completed on 2026-10-17 (run 6, `mp16-big`), and on 2026-10-18 the real PC + VM runs of
ADR-0098 step 3 found two more wake gaps (fixes 9 and 10), after which run A3 (`mp21-link`) completed on both
computers (`docs/agent/CURRENT.md`, 2026-10-18).

Proposed on 2026-10-17. The two decisions marked USER-STATED were made by the owner on 2026-10-17 and are final
for this change. Builds on ADR-0093 (conversations, waking a stopped agent), ADR-0091 (Codex headless sessions)
and ADR-0098 (one project across many computers). It changes the agent-facing behaviour of `get_next_action`'s
WAIT answer and of `run_command`, so it is a published-contract change and needs the owner's approval.

Claims are marked VERIFIED (observed on the real artifact, with where), USER-STATED, DERIVED or MEASURED.

Evidence so far (`docs/agent/CURRENT.md`, "Agents are woken, they do not wait"): the cross-computer wake is proven at
source level with a real Link session on loopback (MEASURED 209 ms on Linux, 211 ms on Windows, from the leader's
append to the member's wake); one real single-computer run with two Claude agents completed, the author woken by its
Stop hook on the review result and on its lane ending, with no sleep. Not yet: a real two-computer run, Codex.

## Context

In the two-homes run of 2026-10-17 (`docs/agent/CURRENT.md`, "Two homes, bigger goal") every writer published,
was told WAIT, and waited for a review. The answer said `retryAfterMs=5000` and `maxUnchangedPolls=12`. The agents
did not wait five seconds. They slept: `run_command` with `python -c "import time; time.sleep(590)"` and similar,
from 20 s up to 900 s (VERIFIED, `agent-runs/177b07d3-.../run-fb2eab33-...`, owner `claude#1` and `claude#2`: 16
sleep calls, all through `mcp__synesis__run_command`). While an author slept, the review request addressed to it
sat unanswered, and its reviewer gave up after twelve unchanged polls.

Waiting was the agent's job, and an agent is bad at it: it cannot know when something arrives, so it guesses a
duration, and every guess is either too short (tokens spent on unchanged polls) or too long (work left
unanswered).

The run controller (`AgentRunController`) already resumes an agent whose process has exited, when the log
progresses, a message is addressed to its lane, or an answer deadline passes. It cannot reach an agent that is
alive and asleep. Commit `d5b61156` (branch `claude/charming-liskov-6b9bc8`, not yet on `master`) keeps a run
waiting while another computer of the project is connected, so a stopped agent can still be resumed by that
computer's work. That commit also told agents to "wait within your turn". This ADR replaces that sentence.

## Decisions made by the owner (USER-STATED, 2026-10-17)

1. Agents must not wait by themselves. Waiting is replaced by provider hooks that wake an agent when something
   is addressed to it.
2. Agents can wake other agents anywhere on the project's network (other computers in the ADR-0098 project). For
   now this is always allowed for every member. Per-member permission is a later, separate change (see "Future").

## What the providers can do (VERIFIED on this PC, 2026-10-17)

Claude Code 2.1.291, in the print mode `synesis agents run` uses (`claude -p --output-format stream-json`), with
a throwaway project whose `.claude/settings.json` held the hooks:

- A `Stop` hook that prints `{"decision":"block","reason":"..."}` keeps the turn going; the model read the reason
  and acted on it. The second stop arrives with `stop_hook_active: true`.
- A `Stop` hook that runs past its `timeout` (5 s in the test, the hook slept 20 s) is cut off and the turn ends
  normally. The default timeout for command hooks is 600 s and the per-hook `timeout` is in seconds (Claude Code
  hooks reference).
- Hooks inherit the provider process's environment, so a hook sees the connection identity the controller gives
  each agent process.
- A `PreToolUse` hook that denies with `permissionDecisionReason` reaches the model: it said the sleep was blocked
  and ended its turn.
- Claude Code itself ends a turn after eight consecutive `Stop` continuations with no tool call between them
  (hooks reference), so a looping hook cannot hold a turn for ever even if Synesis got the bound wrong.

Codex CLI 0.162.0:

- Has a `Stop` hook with the same `decision: block` + `reason` output and `stop_hook_active` input (the JSON
  schemas embedded in `codex.exe`).
- In `codex exec`, none of a project's hooks ran: not `.codex/hooks.json` alone, not hooks given with `--config`
  and `--enable hooks`, not with `--dangerously-bypass-hook-trust`; not even a `SessionStart` hook. This matches
  what ADR-0091 found for `PreToolUse`. Synesis does not mark hooks trusted on the user's behalf, and does not pass
  the bypass flag, which would also run any other hook the checkout holds.
- So a headless Codex agent has no Stop hook. It is woken by the run controller after it stops (below).

### Codex CLI 0.163.0, re-tested on 2026-10-18

Throwaway repositories only, 14 model runs, on this PC and on the Linux VM; codex-rs read at tag `rust-v0.163.0`.
Full notes outside the repository (session notes, `codex-0.163-hooks.md`). Each claim says where it comes from.

- **Hooks run headless** in `codex exec`, `codex exec resume` and `codex app-server` (VERIFIED, Windows and Linux).
  The 0.162 tests saw none for three reasons, each VERIFIED: an untrusted project's `.codex/hooks.json` is never
  loaded, even with `--dangerously-bypass-hook-trust`, which only skips per-hook review; `node` is not on this PC's
  `PATH`, so `node -e` hooks failed at once; and on Windows a hook runs as `pwsh -NoProfile -Command "<command>"`,
  which breaks inner double quotes. `exec --json` prints no hook events and skips an untrusted hook without a word.
- **Where hooks come from** (DOCS, SOURCE): every active config layer, all of them run. User (`~/.codex/hooks.json`,
  `[hooks]` in `~/.codex/config.toml`), project (`.codex/`, loaded only when the project is trusted), the command
  line (`-c hooks.<Event>=...`, the "session flags" layer, loaded even in an untrusted project), plugins, managed
  policy. The schema is Claude Code's: event, matcher groups, `hooks` handlers with `type`, `command`, `timeout`
  (seconds, default 600).
- **Two gates** (SOURCE, VERIFIED with app-server `hooks/list`): project trust decides whether project hooks load;
  per-hook trust decides whether a loaded non-managed hook runs. A hook is trusted when
  `hooks.state."<key>".trusted_hash` equals its hash. The key is `<source path>:<event>:<group>:<handler>`; for the
  command-line layer it is `C:\<session-flags>\config.toml:stop:0:0` on Windows (a fixed `C:\`, whatever the
  project's drive) and `/<session-flags>/config.toml:stop:0:0` elsewhere. The hash is `sha256:` and the hex SHA-256
  of the handler's normalized identity as compact JSON with sorted keys, for example
  `{"event_name":"stop","hooks":[{"async":false,"command":"...","timeout":660,"type":"command"}]}`. Trust state is
  read only from the user layer and the command line; a project cannot trust its own hooks. The TUI `/hooks` review
  writes the user-layer entry. There is no `codex hooks trust` command.
- **So Synesis can trust its own hook, and only it, for one run** (VERIFIED): `-c hooks.Stop=[...]` defines the hook
  and `-c hooks.state={'<key>'={trusted_hash='<hash>'}}` trusts exactly that event, command and timeout. Nothing is
  written to `~/.codex` or the project, no project trust is needed, user and project hooks keep their own review
  state, and the bypass flag is not used. The key and hash recipe are internal to Codex, not in its docs; a Codex
  that computes them differently shows the hook `untrusted` and skips it. A managed `allow_managed_hooks_only` policy
  drops command-line hooks.
- **Stop semantics** (VERIFIED): `{"decision":"block","reason":"..."}` on stdout continues the same turn and the
  model acts on the reason; `stop_hook_active` is `true` on every later Stop of that turn; hooks get the codex
  process's environment; they run unsandboxed in the user's shell. Differences from Claude Code: **no cap** on
  continuations (six in a row were honoured); on Windows a hook that times out is **not killed**, and `codex exec`
  waits for it; exit code 2 with a reason on stderr does not work on Windows (pwsh turns it into 1), only the JSON
  form works on both.

Verdict: a headless Codex agent can have a Stop hook with the same wake semantics as Claude Code's, provided
Synesis bounds the wakes itself (it does: three without a WAIT) and ends its own hold before the hook's timeout (it
does: a ten-minute hold in a 660 s timeout). Built for agents the run controller starts (amendment, 2026-10-18 fix 7).

## Decision

### In one example

Ana's computer is the leader and runs `writer-1`; Ben's computer is a member and runs `reviewer-1`. `writer-1`
publishes `slug.py` and asks for review. `get_next_action` answers WAIT. Under this ADR:

1. `writer-1` ends its turn instead of sleeping. Its Stop hook starts and holds the process. No model is called.
2. On Ben's computer, `reviewer-1` asks to review `slug.py`. The request is recorded on the leader's log, which is
   Ana's local log, so on Ana's disk a new event file appears.
3. The hook sees that something about `writer-1`'s own lane changed (a review request now targets it) and lets the
   stop fail with the reason "Synesis recorded something for your lane: a coordination request for your lane.
   Call get_next_action." `writer-1` calls `get_next_action`, which shows the admission to give, and gives it.
4. Had nothing come for ten minutes, the hook would have let `writer-1` stop. Its process ends, and the controller
   resumes it as soon as the log progresses (it already does this), with the same neutral resume prompt.

When `reviewer-1` waits for that admission, the same thing happens on Ben's computer: the grant is recorded on the
leader and replicated to Ben's log, and the grant wakes `reviewer-1`.

This example is step 2 of ADR-0098, as built. From ADR-0098 step 4 there is no leader: Ben's request is written to
Ben's own log and arrives in Ana's copy of it, and the grant is written to Ana's log and arrives in Ben's copy. The
wake is unchanged: the hook and the controller still react to what lands in their own computer's copy, now of every
computer's log.

### No agent sleeps

- `run_command` refuses an argv that does nothing but sleep, before anything runs, with `POLICY_DENIED`,
  `reason: SLEEP_COMMAND_REFUSED` and an instruction: when Synesis says WAIT, end your turn; Synesis wakes you. This
  is in the server, so it holds for every provider, including Codex, whose hooks do not run headless.
- The managed Claude Code `PreToolUse` hook now also matches `Bash` and `PowerShell` and denies the same pure
  sleeps there, but only to an agent the run controller started (its process carries
  `SYNESIS_MCP_CONNECTION_INSTANCE_ID`). Headless Claude agents are not allowed those tools by default, so this
  serves agents whose user settings allow a shell. A person in an interactive session may still `sleep 3` for a
  dev server: nothing would wake them, so they are not told anything would.
- One rule decides what "only sleeps" means, for both: `sleep`, `Start-Sleep`, `timeout /t`, `ping -n N
  127.0.0.1`, `python -c` with only `time.sleep`, `node -e` with only `setTimeout`, alone or behind `sh -c`,
  `bash -c`, `cmd /c`, `powershell -Command`. Saying something afterwards (`echo`, `Write-Output`, `true`, a
  Python `print` of a literal) is still the same wait. A command that sleeps and then does real work is not
  refused. The rule steers agents; an agent determined to wait can still find a command it does not recognise.

### Ending the turn is how an agent waits

The managed AGENTS.md section, the provider manual and the `get_next_action` description say: when Synesis says
WAIT, end your turn. Do not sleep, do not poll on a timer, do not wait on a background task. Synesis wakes you when
something arrives for your lane. `retryAfterMs` and `maxUnchangedPolls` stay in the answer (readers depend on
them) and still bound an agent that polls, but the guidance no longer asks for polling.

### What "addressed to your lane" means

The log decides, through the projections that `get_next_action` itself reads. For one participant, the lane view
is a small map, one entry per thing that can be waiting for that lane:

| Entry | Read from | What it catches |
| --- | --- | --- |
| `lane` | its open intent: status, version, role | a new version of its lane |
| `requests` | coordination requests it made, or that target it or its lineage, with status | a review request to admit; admission granted or refused; any coordination request or reply |
| `grants` | lane grants issued to it | admission to review |
| `reviews` | review results on its task, or given by it | the review verdict |
| `task` | the completion state of its task | published, integrated, rejected, waiting for dependencies, a dependency integrated |
| `contracts` | contract change requests against its files or filed by it, with state | a change request, an answer |
| `capabilities` | capability requests its lineage owns, or that this caller asked for, with state and revision | an implementation published for it to validate, a validation of its own |
| `messages` | unread messages for its lane | a message (ADR-0093) |
| `operator` | requests its lane put to the operator, with state (added 2026-10-18) | the operator closing one |
| `group` | its WorkGroup's status | WorkGroup complete or cancelled |

Something is addressed to the lane when an entry differs from what it was when the agent was last told WAIT,
or when its lane has ended: integrated, revoked, cancelled, detached or continued by another session, which is
how "a dependency integrated" and "WorkGroup complete" usually reach a lane whose own work was waiting on them.
The reason names the entries that changed. A participant whose lane was suspended is not woken for that alone;
its lane is recovered by the ordinary path. A wait that ends at a time rather than on an event is also a wake:
the recovery deadline (`recoveryDeadlineEpochMillis`) and the requester's answer period
(`requesterAnswerDeadlineEpochMillis`) the WAIT answer names, and the moment a silent consumer of the lane's
contract counts as absent (ADR-0092). Nothing here decides what the agent may do: the reason only says
"call get_next_action", and that answer is the authority.

A lane view says nothing for a participant that holds no open lane. "Lane continuation offered" is addressed to
agents without a lane, and those are not held (below), so it is not an entry.

### Who is held, for how long, and where the baseline comes from

- When `get_next_action` answers WAIT to a connection that the run controller started (the connection identity
  came from `SYNESIS_MCP_CONNECTION_INSTANCE_ID`), the MCP server writes the caller's lane view to
  `.synesis/local/waits/<sha256 of the connection identity>.json`, after any messages were handed over. Any other
  answer removes that file. This is local, per-computer state, never replicated and never authority.
- The `Stop` hook (`synesis hook claude`, the same command as the `PreToolUse` hook, which now reads
  `hook_event_name`) finds that file from the same environment variable. No variable, no file, or no lane: it lets
  the agent stop at once. So an interactive session, or an agent that was not told WAIT, is never held. Whenever the
  hook lets an agent stop it removes the note, so whatever runs the agent again is told WAIT afresh.
- Otherwise it watches the local log's head every 400 ms (as `ProjectChangeWait` does), recomputes the lane view
  when the head moves, and blocks the stop when an entry differs from the file. On a block it writes the new view
  to the file, so the next stop waits for something newer.
- It holds at most ten minutes (the hook's `timeout` in `.claude/settings.json` is eleven), or until the time the
  wait ends when that is sooner, then lets the agent stop or, at that time, wakes it. Ten minutes is Claude Code's own default hook timeout. Longer holds would delay the moment a run whose
  agents all wait on each other is seen to be stuck.
- It blocks at most three times in a row without the agent being told WAIT again in between. The agent's next
  `get_next_action` resets the count. Claude Code's own cap of eight is the backstop.

The baseline is the view at the agent's last WAIT answer, not at the moment the hook starts. A review request that
arrives in the seconds between the answer and the stop is therefore not missed.

### Waking another agent, on any computer

An agent wakes another lane with `send_message` (ADR-0093), naming the lane by a file it claims or by its
participant handle. Coordination and contract requests wake their target the same way. No new action is added:
each of these is an event in the log, every computer's log receives it, and the receiving computer's hooks and
controller act on their own local copy. Nothing new goes over the network. Per USER-STATED decision 2, every
member may do this.

Latency across computers is the replication latency plus at most 400 ms. A member's proposal is appended to the
leader's log when the leader accepts it, so a leader-side agent sees it at once. An entry written on the leader
reaches a member by the leader's notice and the member's pull of what follows its head; the member also pulls
every 15 s without a notice. See "Evidence" for the measured numbers. From ADR-0098 step 4 the same holds with
"the writer" in place of "the leader": the computer that wrote an entry sends the notice, and its peers pull.

### The run controller

The controller already resumes a stopped agent that holds an open lane whenever the log progresses, and for an
unread message (ADR-0093). Every entry of the lane view changes only through a progress event or a message, so an
event addressed to a stopped agent already resumes it. A second, narrower trigger would never fire alone, and is
not added. What was missing is that the run ended once every agent here had stopped; `d5b61156` keeps it waiting
while another computer of the project is connected, and this change builds on it. The controller is the only
waking path for headless Codex agents, and the fallback when a Claude agent's hook has let it stop.

### Missed and duplicate wakes

A wake only says "call get_next_action". A duplicate costs one short turn. A wake the hook misses (the lane view
does not cover what changed, the hook process died, the file was removed) costs at most the ten-minute hold: the
agent then stops, and the controller resumes it on the next progress. A wake for something the agent cannot act
on yet ends with WAIT again, and the agent ends its turn again.

## Alternatives rejected

- **Wake on any state change** (the rule `ProjectChangeWait` uses for its 20 s hold). Simple, but every event in
  the project would cost every waiting agent a model turn. Within a held tool call that is free; across a turn it
  is not.
- **Run `get_next_action` inside the hook.** Exact, but it verifies the caller's worktree and may record a new
  trust state on its binding (`WorkspaceReadinessService.assess`). Doing that on every log change, from a second
  process beside the agent's own MCP server, is heavy and repeats writes that belong to the agent's own call.
- **Hold the WAIT answer itself for minutes.** Claude Code would allow a long MCP call, Codex gives a tool call
  about a minute, and the owner chose hooks (USER-STATED 1).
- **Refuse sleeps only in the PreToolUse hook.** The observed sleeps were `run_command` calls, and Codex runs no
  hooks headless. The server is the one place that sees every provider.
- **Pass `--dangerously-bypass-hook-trust` to Codex.** It would run every hook in the checkout, including ones
  Synesis did not write and a cloned repository may carry. Not without the owner's decision.

## Consequences

- An agent told WAIT costs no tokens until something arrives for its lane, on this computer or another.
- Interactive Claude sessions in a Synesis project start the Synesis hook for each Bash or PowerShell call (about
  0.7 to 1 s each, measured with the installed build). The protocol already routes commands through
  `run_command`. Each time such a session ends a turn, the Stop hook also starts once and lets it stop at once,
  since its connection was not started by the run controller.
- Old installs keep the old `PreToolUse` matcher and have no `Stop` hook until `synesis provider install claude`
  is run again; `provider status` reports the configuration as not current. `synesis agents run` does not check
  this, so on an old install agents are simply not held.
- A run whose agents all wait on each other is seen as stuck up to ten minutes later than before.

## Published contracts

- `run_command`: a new refusal for pure sleeps (`POLICY_DENIED`, result `reason: SLEEP_COMMAND_REFUSED`,
  `instruction`). Additive; no existing field changes meaning.
- `get_next_action`: no field changes. Its description and the provider guidance now say "end your turn" for
  WAIT.
- `.claude/settings.json` (managed entries): the `PreToolUse` matcher becomes `Edit|Write|Bash|PowerShell`, and a
  managed `Stop` entry is added, identified by its `id` (`synesis-claude-code-stop`) as the `PreToolUse` entry is.
  User entries are left in place, an empty `Stop` list of the user's included.
- `synesis hook claude` now also answers Stop events, and records the hook outcomes `STOP_ALLOWED` and `WOKEN` in
  local diagnostics, and `BLOCKED` for a refused shell sleep.
- `ProviderIntegration` (public Java API) gains two default methods, `managedStopHook` and `isManagedStopHook`;
  existing implementations are unchanged.
- New local file `.synesis/local/waits/*.json`. Local, unversioned, read only by the hook on the same computer; a
  missing or unreadable file means "do not hold".

## Fitness functions

- A run where an author is told WAIT and a reviewer on another computer later asks for review: the author is woken
  by the request, with no sleep call in its log.
- No `run_command` in an agent log whose argv only sleeps succeeds.

## Amendment of 2026-10-17: a waiting agent keeps its session

Written after the overnight run across this PC and the VM (`docs/agent/CURRENT.md`, "Overnight real run, this PC and
the VM"). Decisions 1, 2 and 4 change rules; the owner approved each as written here on 2026-10-17 (USER-STATED).
Decisions 3 and 5 change no rule.

### What went wrong (VERIFIED in the agent logs of run `run-0e6c908a`)

The run controller gave every process of an agent a new connection identity and recorded the old one as ended when the
process exited; the MCP server also closed its own lease at end of input. A closed lease is taken as proof that the agent
is gone, so its participant was suspended and held for recovery within seconds. Under this ADR ending the turn is how an
agent waits, and a Codex agent's process exits at every WAIT, so every wait became a departure. Each resume was then a new
session with no lane: `claude#2` was resumed ten times; in resumes 6, 7 and 8 it was offered its own held lane
(`continuation_available`) and claimed a new module instead. "One agent claimed four modules" was four sessions of one
provider conversation, each leaving a held lane behind, and dozens of participants churned through `RECOVERY_HELD` and
`DETACHED` while review requests waited on held authors.

### Decision 1: the run controller keeps its agents' sessions (rule change)

**Example.** Codex agent `codex#1` on the VM publishes `slug.py` and is told WAIT. Its process exits. Before, the VM's
Synesis recorded `codex#1` as gone: its lane went to recovery, and the review request Claude sent from this PC waited on
nobody. Now the controller that started `codex#1` still intends to resume it, so it keeps the session alive: the lease
of that connection names the controller process and the controller renews it. `codex#1` stays `ACTIVE` in the log on both
computers; Claude's request is addressed to a live author; the controller resumes `codex#1` into the same session and
the same lane, and it admits the reviewer.

- **One connection identity per controller agent.** The controller opens one identity for each agent and passes it to
  every process of that agent, first start and resumes. Same identity means the same binding, the same session and the
  same lane (exact resolution, `SessionAuthorityResolver`). Nothing is revived: a binding that is completed or terminal
  stays so, and `RECOVERY_HELD` or `DETACHED` are never reattached (ADR-0055).
- **The lease names its keeper.** `SessionLeaseService.keep` points the lease of a connection at the controller process
  and refreshes its heartbeat, under the project append lock. It takes over only a lease that is not closed or terminal
  and that names either the controller itself or a live descendant of it (its agent's MCP server), so it can only keep
  sessions of processes it started. The liveness rule is unchanged and already right: keeper alive and heartbeat fresh
  is `ACTIVE`; keeper gone is an absence that becomes recoverable after the grace period. The MCP server's own close at
  end of input then no longer applies (its pid is not the one the lease names), and a resumed MCP server renews a lease
  that names the keeper.
- **While the controller can come back to an agent, it keeps it**: the agent's process runs, or it is stopped with an
  open lane, a provider session to resume and budget left (Decision 2). Otherwise, and when the run ends, the
  controller ends the connection as before (closed cleanly) and runs the ordinary recovery at once, which the MCP
  server's close used to do; with every agent stopped, nobody else would.
- **Bounded.** A kept lane cannot be taken over while the run lives: at most `--timeout-minutes` (30 by default). A
  controller that is killed stops renewing; its agents' lanes become recoverable after the existing five-minute grace,
  as for any lost process. A first process that exits before the controller's next look (2 s) closed its own lease;
  that agent then fell back to the old behaviour (a new connection on resume). Fixed on 2026-10-18 (below, 5).
- **The controller asks the connection's current session at every look.** A kept connection starts a fresh session
  when its lane has ended (Decision 4), so which participant it stands for changes; the observer had kept the first
  answer per connection, and in the first real run on this amendment it ended two kept sessions that had just taken
  their next lane, which went to recovery (`docs/agent/CURRENT.md`).
- **The Stop hook does not renew.** The agent it holds is a running process, which the controller keeps anyway; and
  for an agent without a controller, a live MCP process is never recoverable however quiet it is (`SUSPECTED_STALE`
  drives nothing).
- **Interactive agents without a controller**: unchanged. Their session lives as long as the provider's MCP process.
  Ending a turn keeps the process, so the lane stays theirs; closing the provider ends the session and its lane is
  recovered, as before. A provider resumed by hand starts a new MCP process with a new identity, so a new session.

### Decision 2: `--max-resumes` bounds idle resumes in a row (rule change)

The budget counted every resume, and every WAIT now costs one, so a busy agent ran out (8 for `claude#2`, 7 for
`codex#1`) and a review request addressed to it could no longer resume it. Now a resume is idle when nothing was recorded
in the log while that process ran (`BOOKKEEPING` excluded), and the budget bounds idle resumes **in a row**: any progress
resets the count. An agent resumed for a review request that it answers is not idle; an agent resumed again and again
into a project where nothing moves is stopped after the budget, as before. The resume trigger, `BOOKKEEPING`,
`holdsOpenLane` and `heldByOperator` are unchanged. Name, default (8) and `AGENT_RESUMES` stay; the option's meaning
narrows.

Progress recorded while the agent was stopped resets the count too, and that progress is what resumes it, so the budget
in practice bounds resumes for messages and answer deadlines after which nothing happens, and `RESUME_LIMIT_REACHED`
names those (or a budget of 0). Resumes for progress are bounded by the progress itself, by `holdsOpenLane` and by the
run's time limit, not by the budget. Counting them would starve the agent again: a waiting author resumed for other
modules' work finds nothing to do each time, and eight such resumes would leave it asleep when its review request came.

One addition with the same rule as the Stop hook's "your lane has ended" wake: an agent that stopped holding a lane and
whose lane then ended (integrated, cancelled, continued by another) is resumed once while work is open, on a new
connection and so in a fresh session, to take the next piece of work, as a Claude agent woken by its hook does in the
same process (Decision 4). Its kept connection is ended when its lane ends. A first start is not a resume and is not
counted.

### Decision 3: reviews are offered only for active authors (guidance only)

`reviewCandidatesFor` offers a snapshot only when its author's participant is `ACTIVE` in the log. A suspended author
cannot answer a review request any more than a held one, and neither can one whose lane has ended (completed, cancelled,
revoked, detached) or that the log does not know; a snapshot still waiting for review has an active author in every
ordinary history, so this hides only snapshots nobody could admit a reviewer to. Only the log counts, the same on every computer; a stale lease
does not, because an agent told WAIT makes no call until woken and is not absent.

### Decision 4: a completed session may start a fresh one on the same connection (rule change)

ADR-0030 and ADR-0055 forbid reviving a completed binding, and that stays. Commit `8b5f01e9` already makes
`ensure_session` start a fresh binding (new session, participant and worktree) for a connection whose lane ended. What
refused `claude#1` was the recovery fence in front of it, which reads only bound sessions. The fence now lets
`ensure_session` through for an exact completed binding, as it lets the closing `get_next_action` through; every other
tool stays fenced. The binding service, where the fresh binding is made and for both routes there (a binding marked
completed here, a lane that ended in the log), refuses `SESSION_AUTHORITY_OPEN` while the ended session still holds
authority (a review grant, a review request, a command: the blockers of terminalization), because the fresh binding
replaces the one that holds it; the agent is pointed to `get_next_action` to finish it first.

Finishing it has to be possible, and on an ordinary connection it was not. In the third real run on this amendment an
agent held the review grant of `roman.py` when its own `csvline.py` was integrated; its review decision was refused at
the same fence, nobody else could review `roman.py` while the grant held it, and the run stopped. The fence now lets a
completed binding through to the tools whose policy already admits one (`request_coordination`,
`respond_coordination`; the rule a managed connection had, `ManagedAdmissionState.REVIEW_ONLY`). Downstream only
consuming a review grant and recording a review decision accept a completed binding, and both check the grant
(`SessionAuthorityResolver.resolveReview`); every other coordination operation resolves a bound binding and fails
closed as before. The reads and test runs of that review were still fenced, so in the real PC + VM run s5-run6 two
reviewers whose own lanes were integrated could not read what they reviewed and stopped without a verdict
(ADR-0098, step 5 item 24). `read_file` and `run_command` now admit a completed binding at the fence too; downstream
only the immutable review snapshot of a consumed grant without a verdict accepts one (`ReviewSnapshotAccessService`),
and a completed lane holding no review is told to start a fresh session.

### Decision 5: one open lane per agent (documented, no new rule)

A session holds one lane, and with Decision 1 a controller agent has one session at a time, so it holds at most one
open lane; it takes the next piece of work only after its lane has ended. No admission limit is added.

### Merging a member's work no one here calls in for (ADR-0098, found in the same runs)

The leader merges a member's work in a pass after each member entry, and every agent call on the leader runs the same
pass. In the third run a member's `wrap.py` was accepted by a reviewer on the leader; its author on the member, woken,
found nothing to record, and every agent on the leader was waiting, so no pass ran and the accepted work waited until
the run's end (one pass by hand merged it at once). The leader's merge driver now also runs a pass every 30 s while the
project runtime lives (`MergeDriver.repeatEvery`); a pass with nothing to merge does nothing.

### Published contracts of the amendment

- Session lease file (local, never replicated): same format; `processIdentity` may now name the run controller that
  keeps the session rather than the MCP server process.
- `synesis agents run --max-resumes`: same name and default, narrower meaning (Decision 2).
- `ensure_session` on a connection whose lane completed now starts a fresh session instead of refusing
  `SESSION_RECOVERY_STATE_UNAVAILABLE`; new refusal reason `SESSION_AUTHORITY_OPEN` with `openAuthority` and
  `instruction` (additive).
- A connection whose lane completed may now call `request_coordination` and `respond_coordination`; consuming a
  review grant and recording a review decision succeed for it, every other operation is refused downstream as before
  instead of at the fence with `SESSION_RECOVERY_STATE_UNAVAILABLE`.
- Java API, additive: `SessionLeaseService.keep`, `ProviderSessionTerminalizationService.openAuthority`, and a default
  method `keep` on `AgentRunController.Connections` (an implementation without it keeps the old behaviour).
- Changed by the fixes of 2026-10-18. Additive unless said otherwise: `Connections.end`, `Observation.sessionFenced` and
  `Observation.newestOperatorAnswerFor` (default methods), and `Observation.reviewsOffered` (default) with
  `CollaborationResponseMapper.openReviewSnapshots` (fix 9), and `Observation.newestAnswerFor` and
  `Observation.holdsRequestOrGrant` (defaults) with `ProviderSessionTerminalizationService.holdsRequestOrGrant`
  (fix 10); `SessionLeaseService.reserveKeeper` and `releaseKeeper`;
  `HeadlessOptions.hookLauncher` with `withHookLauncher` (a fourth record component: the two- and three-argument
  constructors stay, so callers compile unchanged; a record pattern over three components would not);
  `ProviderIntegration.trustNote` (default); `ProviderApplicationService.launcherPath` made public. Local file
  `<connection>.keeper` beside the session lease, read only by the MCP server that opens that lease. The wait note
  gains an `operator` entry; a note from an older build lacks it and is not compared on it. Codex agents the controller
  starts get two more `--config` arguments and one more environment variable; a `.cmd` provider shim is started
  through `cmd.exe /d /s /c` with one quoted line. Not additive, a changed meaning of existing output (needs the
  owner's approval with this ADR): `provider install codex` prints `SUCCESS` where it printed `DEGRADED` for hook
  review alone, and `provider status codex` prints `HEALTHY` in the same case; the new key `TRUST_NOTE` says why. Not
  additive, a narrowed ADR-0093 rule: a lane's request to the operator being closed now resumes that lane's agent.

### Fixes of 2026-10-18: the limits this amendment listed

The owner asked on 2026-10-18 to fix every limit below (USER-STATED); each fix has a test that fails on the code
before it. The ADR stays Proposed. Source-level evidence only: none of these has run in a real two-computer run yet.

1. **A kept session fenced for recovery.** `keep` reads only the lease, so a kept participant that the log had
   suspended (its keeper stopped renewing past the grace period) was resumed on the same connection, refused
   `SESSION_EPOCH_FENCED` at every call, and idled until the run ended. The controller now reads the fence itself
   (`Observation.sessionFenced`: the participant of the connection's binding is `SUSPENDED` or `RECOVERY_HELD`, the
   rule the MCP server's recovery fence applies). Such a session is not kept: the connection is ended and the agent is
   resumed once on a new connection, without waiting for progress (suspension and holding are bookkeeping, not
   progress), where continuing its held lane is offered to it through the ordinary continuation. No fence moves: the
   old binding stays fenced, and the new session gets the lane only by the continuation every other session would use.
2. **A lane waiting on the operator.** ADR-0093 made a lane's request to the operator, and the operator closing it,
   resume nobody, because the closing used to resume every stopped agent. A lane that asked and ended its turn to wait
   for the answer was therefore never woken and kept its lane until the run ended. The closing is now addressed to the
   lane that asked, alone: the lane view gains an `operator` entry (that lane's requests and their state), so the Stop
   hook wakes it, and the controller resumes its stopped agent once for the closing, as for a message
   (`Observation.newestOperatorAnswerFor`). It is still not progress, so no other agent is resumed. This narrows one
   sentence of ADR-0093 ("neither does the operator closing it") to "nobody but the lane that asked". A lane whose
   published work waits for a request Synesis raised (`heldByOperator`) needed no change: that request's closing is
   progress, and the integration it lets through changes the lane's `task` entry.
3. **An end the project lock refused.** `ControllerConnections.ended` waited up to 30 s and then gave up, and the
   kept lease went on naming the controller, so the lane looked held by a live session until the run ended. While the
   run lives an end is now tried once (`Connections.end`) and, when the project could not record it, offered again at
   each observation; the run's end still waits a bounded time for any left. The controller's loop no longer blocks on
   it.
4. **A stale clean worker with private commits.** `ProviderSessionBindingService.resumeStale` gave such a worker a new
   binding, stranding the open lane of a session the controller keeps on that connection. A session that holds an open
   lane now keeps its binding and its worktree, as a managed attachment, a continuation and a no-change lane already
   did; a session without a lane is still reallocated.
5. **A first process that closes its lease before the controller looks.** The controller now reserves itself as the
   keeper of every identity it hands out, before starting the process (`SessionLeaseService.reserveKeeper`, a local
   note `<connection>.keeper` beside the lease). The MCP server that creates the lease names the reserved keeper
   when that keeper is alive (pid and start time) and the server descends from it, the fence `keep` already applied,
   and otherwise names itself as before. Its own close at end of input is then a no-op (pid fence) and the controller
   keeps the session from the start. A process can still keep only the sessions of processes it started; ending the
   connection drops the note, and identities are never reused.
6. (`link join` answering too early belongs to ADR-0098 step 3 and is fixed there.)
7. **Codex.** After the re-test above, an agent the run controller starts on Codex is given Synesis's own Stop hook on
   its command line, `hooks.Stop` running `<launcher> hook codex` with a 660 s timeout, and `hooks.state` trusting
   exactly that hook by its hash (`CodexStopHook`; the hash is pinned in a test to the one Codex 0.163.0 reported).
   Nothing else is trusted and nothing is written to the user's or the project's Codex files; the bypass flag is never
   passed. The Codex process now carries the connection identity in its environment, and `synesis hook codex` answers
   the Stop event as `synesis hook claude` does: the same lane view, the same ten-minute hold, three wakes without a
   WAIT. When the launcher's path cannot be run unquoted by every shell Codex may use (a space, a quote), no hook is
   given; when a Codex release hashes differently, it skips the hook. In both cases the agent is woken by the
   controller after it stops, as before. A `codex.cmd` shim is now started as `cmd.exe /d /s /c "<line>"`, each
   argument that holds a space or `< > & | ^` quoted inside the line, since the trust key holds `<` and `>`; plain
   `/c` also stripped the first and last quote of a line, which broke a shim in a folder with a space. `provider install codex` and `provider status`
   no longer report `DEGRADED` for hook review alone: the review is the person's step in Codex (trust the project,
   `/hooks`), Synesis must not take it for them, and an agent Synesis starts needs neither (Codex's read-only sandbox
   for writes, ADR-0091, and its own Stop hook). `TRUST_STATUS=REVIEW_REQUIRED` stays, with a `TRUST_NOTE` saying
   so; any other fault still degrades the install.
8. **Windows test.** `LeaseKeeperTest` proved the keeper fence with the test JVM's parent, which a fresh Windows 11
   install hides from `ProcessHandle.parent()`. It now starts a process through a launcher that exits, so the process
   descends from nothing in the JVM, and checks that before using it.
9. **A review only an agent here without a lane could take (the failure VERIFIED in a real PC and VM run, mp17-link, 2026-10-18).**
   Both Claude agents on the PC stopped with their lanes integrated. Ten minutes later a Codex agent on the VM
   published work whose review is mandatory, and the other Codex agent there held a lane of its own, so it could not
   take the review. The controller printed `AGENT_RUN_WAITING=FOREIGN_LANE_OPEN` and resumed nobody: a stopped agent
   was resumed only for its own open lane, once when that lane ended while it was stopped, or for a message or an
   answer deadline of its lane. The goal stopped at 6 of 7 modules. Now a stopped agent that holds no lane is also
   resumed when the log offers it a review it may take, the candidates `get_next_action` offers a session without a
   lane (`reviewCandidatesFor`: review required and pending, author active as Decision 3 says, no reviewer holding
   it, not authored in its lineage by the current session behind any connection the agent had), read through
   `Observation.reviewsOffered`. Each agent is resumed once per snapshot while it stays offered, and one agent here
   at a time per snapshot: if it stops without taking it, the next stopped agent is tried, once. A snapshot taken and
   then given up is offered again, and counts as a new offer. Such a resume needs the agent to be under
   `--max-resumes`, and one that records nothing counts as idle; the publication that offers the review is progress
   and resets the count, so in practice only a budget of 0 stops it. It gets the same neutral prompt, and a new
   connection when its connection ended, as after a lane ends. This is a rule change agents can see: more resumes,
   at most one per agent per offer. A Claude agent's Stop hook has no
   matching gap to close: it holds only an agent that was told WAIT with a lane, and one without a lane stops and is
   resumed by the controller. Source-level evidence only. No real run has shown it yet.
10. **An answer to a request of an agent here without a lane (the failure VERIFIED in a real PC and VM run, mp18-link, 2026-10-18).**
    A Claude agent on the PC whose lane for `case.py` had been integrated asked to review `count.py`, which a producer
    lane of the same group that has published may do (ADR-0089, amendment). It was told to wait for the author's
    answer and stopped (08:04). The author, a Codex agent on the VM, accepted at about 08:14. Nothing resumed the PC
    agent: it held no lane, and fix 9 does not offer a review the agent itself holds. Both computers printed
    `AGENT_RUN_WAITING=FOREIGN_LANE_OPEN`; `count.py` waited for ever for the reviewer it had admitted, and `cli.py` for
    `count.py`. Resuming alone would not have been enough: the controller had ended that agent's connection when it
    stopped with no lane, a closed session is never reopened, and the grant belongs to the session that asked
    (Decision 4), so a new session could not have used it. Two rules now, with or without a lane:
    - The controller keeps the session of a stopped agent it may resume while that session holds a coordination
      request that is pending or a review grant it has not finished (`Observation.holdsRequestOrGrant`, the
      `PENDING_DEPENDENCY` and `PENDING_REVIEW` checks of `ProviderSessionTerminalizationService`, read as
      `holdsRequestOrGrant`). The same rule closes the gap Decision 4 described for a stopped agent: one holding a
      review grant when its own lane was integrated had its session ended, and was resumed in a new one that could
      not finish the review; it is now resumed in its own. A run whose agents have all stopped also keeps waiting
      while another computer is connected and such a session is kept (`OTHER_COMPUTER_CONNECTED`), by the same rule
      that keeps the session, since that computer may answer.
    - A stopped agent is resumed once when a request one of its current sessions made is answered after it stopped
      (accepted, rejected or otherwise resolved: a `COORDINATION_RESPONDED` event for it, read through
      `Observation.newestAnswerFor`). The grant an accepted review request issues is part of that answer and is not
      counted again, and a response given again appends nothing. An answer it saw before it stopped resumes nothing,
      a later answer resumes it once more, and this applies too when its own published work waits for an operator.
    The controller's wake decision now lives in one method, `wakeFor`. Contract line visible to agents: an agent that
    asked something through Synesis is resumed, on the session that asked, when the request is answered. The Stop
    hook needs no change: it holds only an agent told WAIT with a lane, and one without a lane stops and is resumed
    by the controller as above. Also from this run: a second reviewer lane for `count.py`, whose author had accepted
    the first reviewer's request for its only snapshot, waited (`WAIT_FOR_ELIGIBLE_PRODUCER`) for an admission that
    would not come. When every snapshot it could review was admitted to another participant (an accepted review
    request, or a review grant that participant holds; a request still pending admits nobody yet), its
    `get_next_action` wait now says that the author admitted another reviewer and that it may call `cancel_lane`, as
    a reviewer whose module was integrated elsewhere is told. Source-level evidence only. No real run has shown it
    yet.
11. **A computer coming back (the failure VERIFIED in a real PC and VM run, s1-run4, 2026-10-18; ADR-0098 step 4,
    designer item 23).** A Claude reviewer on the PC held the grant for a snapshot whose code was on the VM, whose
    cable had just been cut. It could read nothing, recorded no verdict and stopped. Twenty minutes later the VM came
    back with nothing new to send, so no entry arrived, the PC's controller printed `AGENT_RUN_WAITING=FOREIGN_LANE_OPEN`
    and resumed nobody, and the review waited for ever. "Every entry of the lane view changes only through a progress
    event" holds, but what the reviewer waited for was reachability, which no log records. Now a stopped agent whose
    lane may move, or whose session holds a request or a grant, is resumed once when a session with another computer
    of the project began after it last looked (`Observation.otherComputerConnectedSince`, read from
    `replication.log`), and once more for each later session. Contract line visible to agents: an agent may be resumed,
    with the same neutral prompt, when another computer of the project reconnects. Source-level evidence only. No real
    run has shown it yet.

12. **A reviewer of another computer's snapshot is asked again** (s5-run14, 2026-10-19; ADR-0098 step 5, designer item
    29). Fix 11 resumes an agent once for each session that began after it last looked, and a session's first moments
    are no proof that code can be had; in the run the journal recorded no reconnection at all. A stopped agent whose
    session holds an unfinished review grant for a snapshot another computer published is now also resumed again
    while that computer is connected: 30 seconds after it stopped, then 60, 120 and so on for each resume in a row
    that recorded nothing, bounded by `--max-resumes`.

13. **An agent whose lane only waits for its review is offered reviews like an agent with no lane** (s6-run1, 2026-10-19;
    ADR-0098 step 6, designer item 20). Fix 9 woke only agents that held no lane, and the only agent of each computer held
    a producer lane whose snapshot waited for review. `Observation.laneAwaitsOnlyReview` is true for a producer lane
    whose snapshot is `REVIEW_PENDING`; `wakeFor` treats such an agent as idle for the review wake, once for each
    review offered, as fix 9 does. A lane with anything left to write still gets no review wake. The wake changes
    nothing about the lane's own wait.
14. **A producer with unpublished work is not shown another lane's review wait** (s5-run15, PC and VM, 2026-10-19).
    The integration pass answers `WAITING / VALIDATION_REQUIRED, snapshotIds` while a snapshot of its computer's lanes
    awaits review, and `get_next_action` returned it to every caller no review candidate could be offered to. A Codex
    agent resumed after its `finish_lane` was refused as stale was shown that wait for the VM's `primes` snapshot (whose
    author was held, so no candidate was offered), stopped, and its `NOTES.md` lane never published. A producer lane
    with no snapshot of its own now gets its own step (`IMPLEMENT`, then publication) in place of the pump's review
    wait (`AgentNextActionService.producerStillToPublish`); after it publishes, fix 13 offers it other lanes' snapshots.
    The cause is proven from the agent transcript; the held-author condition is not reproduced in the scripted
    scenario (`LaneWithWorkWaitsForNoOtherLaneScenarioTest` passes with and without the change). Same run, not fixed
    here: the PC reviewer's reads of the VM's snapshot answered `CODE_PEER_UNREACHABLE` until its resumes ran out, its
    session ended and its grant was revoked (code over Link); and `finish_lane` was refused as stale four times in a
    row while the other computer's review entries (`COORDINATION_*`, `LANE_GRANT_*`, `REVIEW_VALIDATION_RECORDED`) arrived,
    which `NOT_STALING_A_FINISH` does not list.

15. **A takeover offer wakes an agent with no lane** (s7-run5, PC and VM, 2026-10-20, remaster86; ADR-0098 step 7, items
    10 and 19). The run controller resumes a stopped agent for what it is offered: open reviews, re-merge reviews and combine
    lanes (`Observation.reviewsOffered`, each once while it stays offered). The lane of an away computer, which step 7
    offers to a lane-less agent when this computer is stalled, was not in that list. Whether a computer is away is
    `replication.members`, which no entry announces, so nothing resumed a stopped agent when the record turned, and its next
    `get_next_action` was never asked. The offer is now counted like a review: `EventLogObserver.reviewsOffered` adds the
    lanes `AwayLanes.offered` gives, by lane ID, only when none of the agent's sessions holds a lane (a session holds one
    lane, so a taker with a lane is refused: `CURRENT_PARTICIPANT_HOLDS_A_LANE`). The same once-per-offer rule applies, and
    the offer is forgotten when the takeover makes the lane no longer offered. Proved by `AwayLanesTest` (the offer
    appears with the record, not before, not while this computer is not stalled; it failed before the change). Not proved
    in a real run. The wake is not what ended s7-run5: see ADR-0098 step 7 item 19.

### Not done

- Fix 9 counts only agents resumed for a review as looking at it. A running agent here with no lane may see the same
  review and a stopped one be resumed for it at the same moment; the claim settles it, at the cost of one resume. An
  agent none of whose connections has a known session is never resumed for a review.
- Fix 10 resumes an agent for an answer only to a request one of its current sessions made. A request whose session
  was ended by an older build, or because the agent ran out of `--max-resumes`, has no session left to use the answer.
  As for a message, an answer recorded in the 30 seconds before the agent's process ended is taken as unseen, so an
  answer it read just before stopping can cost one resume. `AgentRunController` is now about 1,020 lines; its
  `Agent` bookkeeping class (about 200 lines) is the piece to move into its own package-private file next.
- A lane its agent cancelled does not start a fresh session on `ensure_session`: the session is told its lane is
  closed (`CLOSE`), as an existing test requires so that cancelled work is not announced again, and a task in the same
  process is refused. The controller starts such an agent again on a new connection once its lane has ended. The owner
  chose to keep this (USER-STATED, 2026-10-18): the restart costs seconds and the CLOSE rule stays. It is listed for
  ADR-0101 as a rule that may be loosened.
- The Codex Stop hook rests on Codex internals (the trust key and the hash recipe) that its documentation does not
  describe. Nothing checks at run time that Codex accepted it: a skipped hook is silent, and only the slower controller
  wake shows it. `provider status` could ask app-server `hooks/list` for `trustStatus: "trusted"`; not built.
- A Codex session a person starts in a Synesis project still runs this project's hooks only after they review them.
- The keeper note of an identity whose controller was killed is left beside the lease. It is never read again, and
  nothing sweeps it.
- An end the project could not record is offered again once per observation, and each attempt may wait the project
  lock's two seconds, so a busy project slows the controller's loop by that much per pending end.
- A kept binding behind a moved control head (fix 4) keeps its worktree as it is, as the other kept bindings do; no
  test shows such a lane integrating afterwards.
- The session lease treats a process whose start time it cannot read as unverified (`AMBIGUOUS`, never recovered),
  and the Windows tests showed one more such gap: a transient failure to read `.synesis/project.json` sends lease
  reads to a `default-project` folder (`ProjectAdministrativeLayout.resolveProjectId`), which made
  `ControllerConnectionsTest`'s lock tests fail about one run in four on this PC, before and after these fixes.
  Recorded, not fixed here.

## Future

- **Resume only for what is addressed to the lane.** The controller still resumes a stopped agent on any progress.
  With idle resumes counted in a row, that cannot starve it, but a Codex agent waiting for review is resumed for
  unrelated progress too. The Stop hook's lane-view comparison could decide the controller's resumes as well.
- **One list of managed hook entries per provider.** `ProviderIntegration` now has three parallel pairs
  (`managedHook`, `managedSessionHook`, `managedStopHook` and their `isManaged...` checks), and
  `ProviderHookConfiguration` repeats the replace/remove/compare steps for each. A list of managed entries keyed
  by event would replace all three; not done here, to keep this change to its responsibility.
- **A fitness test over every WAIT reason**, checking that the event which clears it changes the lane view. The
  view is a hand-kept reading of what `get_next_action` acts on; the review of this change found two gaps in it
  (a lane that ended, the consumer side of capability requests), both fixed, and such a test would catch the next.

- **Per-member wake permission** (USER-STATED 2). Today any member may wake any lane. Later, the owner may limit
  which members may message or request which lanes. That is a rule on who may write those events, not on the hooks:
  on the leader in step 2, and from ADR-0098 step 4 a rule on how an arriving entry counts, applied by every
  computer.
- **Codex.** Done for agents the run controller starts (fixes of 2026-10-18, 7). Left: a run-time check that Codex
  trusted the hook, and the `PreToolUse` hook given the same way, which would route a native write of a headless Codex
  agent into its lane instead of relying on the read-only sandbox alone.

### Other waits and polls the same mechanism can replace next

1. `get_next_action`'s own 20 s hold (`ProjectChangeWait`) and the `retryAfterMs`/`maxUnchangedPolls` polling
   contract: with every agent woken, an agent need not poll at all.
2. The run controller's own answer-deadline resume of a stopped owner (`answerDeadline`): with a live owner now
   woken at that moment by its hook, the controller's clock is needed only for an owner that has already stopped.
3. The run controller's own fixed observation interval (`--poll-seconds`): it can watch the log head as the hook
   does, so a resume follows an event by under a second.
4. The member's 15 s pull (`MemberReplication.PULL_INTERVAL_SECONDS`) is a keepalive as well as a catch-up, so it
   stays; notices already make replication prompt.
