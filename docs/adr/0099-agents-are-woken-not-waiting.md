# ADR-0099 — Agents are woken, they do not wait

## Status

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

This example is step 2 of ADR-0098, as built. From ADR-0098 step 3 there is no leader: Ben's request is written to
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
every 15 s without a notice. See "Evidence" for the measured numbers. From ADR-0098 step 3 the same holds with
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

## Future

- **One list of managed hook entries per provider.** `ProviderIntegration` now has three parallel pairs
  (`managedHook`, `managedSessionHook`, `managedStopHook` and their `isManaged...` checks), and
  `ProviderHookConfiguration` repeats the replace/remove/compare steps for each. A list of managed entries keyed
  by event would replace all three; not done here, to keep this change to its responsibility.
- **A fitness test over every WAIT reason**, checking that the event which clears it changes the lane view. The
  view is a hand-kept reading of what `get_next_action` acts on; the review of this change found two gaps in it
  (a lane that ended, the consumer side of capability requests), both fixed, and such a test would catch the next.

- **Per-member wake permission** (USER-STATED 2). Today any member may wake any lane. Later, the owner may limit
  which members may message or request which lanes. That is a rule on who may write those events, not on the hooks:
  on the leader in step 2, and from ADR-0098 step 3 a rule on how an arriving entry counts, applied by every
  computer.
- **Codex.** If a Codex release runs a project's reviewed hooks headless, or offers a hook trust Synesis can
  lawfully set for its own hook only, install the same `Stop` hook for Codex.

### Other waits and polls the same mechanism can replace next

1. `get_next_action`'s own 20 s hold (`ProjectChangeWait`) and the `retryAfterMs`/`maxUnchangedPolls` polling
   contract: with every agent woken, an agent need not poll at all.
2. The run controller's own answer-deadline resume of a stopped owner (`answerDeadline`): with a live owner now
   woken at that moment by its hook, the controller's clock is needed only for an owner that has already stopped.
3. The run controller's own fixed observation interval (`--poll-seconds`): it can watch the log head as the hook
   does, so a resume follows an event by under a second.
4. The member's 15 s pull (`MemberReplication.PULL_INTERVAL_SECONDS`) is a keepalive as well as a catch-up, so it
   stays; notices already make replication prompt.
