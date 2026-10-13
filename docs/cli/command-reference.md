# Unified CLI Command Reference

The only public application launcher is `synesis`.

```text
synesis
  help | version
  host | join
  init [--project <path>]
  identity show
  provider list|install|status|uninstall|migrate <provider>
  provider uninstall codex --global
  project create
  project validation set|show|clear
  constraint create
  sync host|join
  check-action
  workspace verify|mutate
  hook claude|codex
  doctor | cleanup | reconcile | repair | migrate
  mcp --provider <codex|claude>
  agents run --agent <provider[:model[:effort]]>... (--prompt <text> | --prompt-file <path>)
  collaboration announce|acknowledge|status|release|request|respond|handoff|contract|readiness
  coordination serve|status
  task create|claim|show
  ownership claim|show|release
  supervisor run|status
  events follow
  prediction create|show|list|respond|publish
  speculation prepare|validate|retire|invalidate
  integration gate
```

Ordinary project commands discover `.synesis` from the current directory. An
advanced `--profile <dir>` override is available on commands that support
isolated process tests and cross-profile experiments. Hook JSON stays on
stdout; diagnostics stay on stderr and hook exit code remains 0 for provider
contract responses.

`synesis --version` and `synesis version` report the same embedded build
version. Lifecycle workflows accept one operation mode per invocation;
combining dry-run, prepare, inspect, execute, or rollback modes is a usage
error. `workspace mutate` requires the exact `--session`; `--create-only` and
`--previous-hash` are exclusive preconditions checked under the assigned
worktree's cross-process mutation lock. An idempotency key replays only the
same project, provider binding, target, mutation kind, content, preconditions,
and output mode; reusing it with changed input is rejected.

Provider installation commands are project-local. Provider commands use
canonical IDs only. Codex MCP project context comes from the connected workspace
or process working directory; `synesis mcp --project` is not supported. Managed
Codex launches carry their pinned project root through trusted server
configuration. The global MCP registration is removed only with
`synesis provider uninstall codex --global`.

`synesis agents run` starts one headless session per `--agent`, gives each the
same prompt on standard input, and waits. A headless agent ends its process when
it has nothing left to do in one turn, including when it has polled its inbox
unchanged as often as the protocol allows. When such an agent has stopped, a
lane of its provider is still open, and other work has progressed since, the
command starts that agent's own provider session again with one sentence telling
it to continue. A provider writes its closing message after its last call, and
an agent that has decided to stop may still call once more, so an agent is taken
to have acted on the project only up to the earlier of its last recorded call
and 30 seconds before its process ended: work recorded after that also resumes
it, at the cost of one extra turn when the agent had in fact seen it. The command reads only the durable coordination log: it never reads or
relays an agent's conversation, never assigns work or picks a continuation, and
never writes to the coordination log. Heartbeats and lane takeovers do not count
as progress, so two idle agents cannot wake each other. A resumed agent is a new
connection and therefore a new session; it regains its lane through the ordinary
recovery and continuation path.

That path starts when a connection is known to have ended. A provider that kills
its MCP server instead of closing it leaves no such proof, and Synesis would
otherwise wait out its five-minute abrupt-loss grace period. The command gives
each process it starts its own connection identity and, when the process exits,
records that connection as closed in the project's session leases. It can do
this only for processes it started.

A lane bound to another lane's contract that owes an action counts as absent
after a stated time without a verified call (two minutes by default;
`SYNESIS_REQUESTER_ANSWER_GRACE_SECONDS` or the system property
`synesis.requesterAnswerGraceSeconds` changes it). The log does not change when
that time passes, so the command also resumes a stopped owner once when such a
deadline of its provider has passed, and does not end the run while one is
still pending. The owner's next read is what raises the operator request.

The command ends with `AGENT_RUN_RESULT`: `COMPLETED` when no lane is open and
every work group that ever had a lane is closed (a group created ahead of its
lanes that never received one holds no work) and at least one group finished its work,
`ALL_WORK_CANCELLED` when nothing is open because every lane was cancelled, `NEEDS_OPERATOR` when every agent has stopped
and an operator request is open (each is printed as `OPERATOR_REQUEST=<id> CONDITION=<condition>`), `STALLED` when every agent
has stopped and nothing has progressed since, `NO_WORK_ANNOUNCED`, `RESUME_LIMIT_REACHED`, or
`TIMED_OUT`. Only `COMPLETED` exits with 0. Each provider must already be
installed in the project. Claude Code is limited to the Synesis tools and its read-only
tools. Codex is started in its read-only sandbox with the Synesis server's
tools approved (`sandbox_mode=read-only`,
`mcp_servers.synesis.default_tools_approval_mode=approve`), whatever the
user's Codex configuration says: a headless Codex session does not run the
project's hooks, so without this nothing kept its own file tool or a shell
command out of the project checkout (ADR-0091). Neither provider's policy is
ever loosened outside the Synesis tools. Agent output is kept as log files under the Synesis state directory,
outside the project.

## Project validation

A project can declare one command that must pass before its work is integrated.
It is project policy, kept as `validation` in `.synesis/project.json`, and it is
set here by whoever has authority over the project, never by an agent.

- `synesis project validation set [--timeout <seconds>] [--directory <dir>] [--also-in-lane] -- <argv>...`
  declares the command, replacing any the project declared. Everything after
  `--` is the executable and its arguments; no shell is used. `--timeout`
  defaults to 120 seconds and may be at most 3600. `--directory` is relative to
  the project root and defaults to `.`.
- `synesis project validation show` prints the declared command.
- `synesis project validation clear` removes it.

Each prints `VALIDATION=DECLARED` with `VALIDATION_ARGV` (a JSON array),
`VALIDATION_DIRECTORY`, `VALIDATION_TIMEOUT_SECONDS` and
`VALIDATION_ALSO_IN_LANE`, or `VALIDATION=NONE`. `set` and `clear` rewrite the
project file, keep its identity and review policy, and print
`PROJECT_FILE_CHANGED=.synesis/project.json`. When the project already declares
what was asked they change nothing and do not print that line.

Where Git tracks the project file, `set` and `clear` also commit it, that one
file alone, and print `PROJECT_FILE_COMMITTED=<short commit>`: nothing is
integrated into a checkout that holds an uncommitted change. They are refused
with `ERROR=CONTROL_CHECKOUT_FOREIGN_CHANGES` when the checkout already holds
changes Synesis did not make, by the rule integration itself uses, so the
untracked files a provider installs (`.claude/`, `.codex/`, `.mcp.json`,
`AGENTS.md`) do not count. When the commit fails, the file is put back as it
was and the command reports `ERROR=PROJECT_COMMIT_FAILED`. Outside a Git
repository, or where Git does not track the file, the file is rewritten and
nothing is committed. Both commands wait for an integration attempt that is
running, for at most two seconds, and are refused when it runs longer: run them
again when it has ended.

The command runs on the combined work: the snapshots of an integration attempt
applied to the project's head in a separate worktree, before the project's
branch moves. With `--also-in-lane` it also runs in each lane alone before that
lane's finish is published. That is off by default, because a lane whose tests
need another lane's code cannot pass the command alone.

When the command exits non-zero on the combined work, the snapshot added last
to the attempt is refused. Its lane is reopened and shown what the command
printed, corrects its own files and publishes again; the other snapshots are
tried without it. A lane is asked to repair twice. When the command fails while
a lane with a snapshot in the attempt has used its two repairs, when it timed
out or could not be started, or when the pass that ran it died twice in a row,
nothing is refused and Synesis raises an operator request with condition
`integration_validation_failing`. The command runs while the project's event
log is locked, so other writers wait or are refused for as long as it runs.

## Operator requests

When only someone with authority over the project can clear what blocks it,
Synesis records an operator request (ADR-0091). It raises one for a project
checkout holding changes Synesis did not make (nothing is integrated into such
a checkout, and the work that is finished waits), and for a lane bound to
another lane's contract that owes an action while its agent has not returned
(ADR-0092): start the agents again so one continues that lane. It raises one
with condition `integration_validation_failing` when the project's validation
command fails on the work waiting to be integrated and no lane is asked to
repair it. Nothing is integrated while that request is open and the command is
not run again by the agents' own calls. Change the command, change the project
or end the lane, then run `synesis operator recheck`: it is the one pass that
runs the command again. The request closes when the command then passes or
refuses a snapshot, when the project declares no command, or when no snapshot
is waiting; after a lane is ended, the pass that blocks its snapshot is
followed by the pass that closes the request. A recheck that fails in another
way, or prints something else, closes the request and raises a new one, so the
open request always shows the latest output.

`synesis operator list` prints the open requests, each with a `SUMMARY` and its
`EVIDENCE` lines exactly as the refusing tool reported them (`--all` adds the
closed ones). `synesis operator recheck` looks again and integrates what was
waiting; it exits with 0 when no request is left open. No command declares a
request Synesis raised resolved: it closes when its condition is observed not
to hold.

An agent can also put something to the operator itself (ADR-0093). Such a
request is listed with `CONDITION=lane_asks_operator` and `RAISED_BY_LANE`, blocks
nothing, and makes a run that stalls end `NEEDS_OPERATOR`. Synesis cannot observe
that it was dealt with, so `synesis operator close <id> [--because <text>]`
closes it.

## Conversations

`synesis conversations` lists what agents said to each other, one line per
conversation with its members and message count; `synesis conversations <id>`
prints that conversation's messages. It only reads: the operator cannot post.
