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
it to continue. It reads only the durable coordination log: it never reads or
relays an agent's conversation, never assigns work or picks a continuation, and
writes no coordination state. Heartbeats and lane takeovers do not count as
progress, so two idle agents cannot wake each other. A resumed agent is a new
connection and therefore a new session; it regains its lane through the ordinary
recovery and continuation path.

The command ends with `AGENT_RUN_RESULT`: `COMPLETED` when every work group is
complete and no lane is open, `STALLED` when every agent has stopped and nothing
has progressed since, `NO_WORK_ANNOUNCED`, `RESUME_LIMIT_REACHED`, or
`TIMED_OUT`. Only `COMPLETED` exits with 0. Each provider must already be
installed in the project. Provider commands keep the provider's own approval and
sandbox policy; Claude Code is limited to the Synesis tools and its read-only
tools. Agent output is kept as log files under the Synesis state directory,
outside the project.
