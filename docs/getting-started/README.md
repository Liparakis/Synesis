# Getting started

This is the shortest path from a checkout to an initialized local Synesis
project.

## Build the local launcher

Use Java 25 and the Gradle Wrapper:

```powershell
.\gradlew.bat :cli:installDist --dependency-verification=strict
& ".\cli\build\install\synesis\bin\synesis.bat" --help
```

For the full verification matrix, see [build and test](../development/build-and-test.md).

## Initialize a project and connect an agent

From the project root (a Git repository), run:

<!-- WALKTHROUGH: GettingStartedWalkthroughTest runs this block in a disposable Git repository; a command that stops working fails the build -->
```powershell
synesis init --agent claude      # or --agent codex; a script names the agent
synesis provider status claude   # or codex: whichever init connected
synesis doctor
```

`init` finds the agents installed on this computer (Claude Code, Codex): it
looks for them on `PATH`, asks each for its version and, where the agent offers
a status command, whether someone is signed in. It never reads an agent's
credential files. One agent found is connected; with several it asks once which
to connect. In a script, say it yourself: `synesis init --agent claude`,
`--agent codex`, or `--no-agents`, and `--git-init` to create the Git
repository first. At the end it lists every file it wrote, in the project and
outside it, and prints the next steps: start the agent in the project folder
(`claude` or `codex`) and tell it what to do, and look at what is happening
(`synesis status`, or `synesis ui` in the browser; what needs you:
`synesis operator list`). Synesis starts no work of its own. To connect an
agent installed later, run `synesis init` again or
`synesis provider install claude` (or `codex`).

Initialization creates shareable metadata in `.synesis/project.json` and keeps
identity, provider, and runtime state under `.synesis/local/`. Add that local
directory to `.gitignore`. Codex's user-wide configuration
(`~/.codex/config.toml`) is written only when Codex is connected.

## See what is going on

```powershell
synesis status            # the project, each agent and its lane, what needs you, other computers
synesis status --watch    # draw again after each new entry (needs the project's runtime)
synesis agents log        # what the agents of the newest run said, called and were answered
```

`synesis status` reads files only, so it also works while the project's runtime
is stopped. `synesis agents log` reads what `synesis agents run` kept of its
agents, one line per entry with secret-looking values replaced. Both print
stable `STATUS_*` and `AGENT_LOG_*` lines with `--porcelain`. See the
[CLI reference](../cli/command-reference.md).

Synesis writes no instruction file into the project. The agent's instructions
are the installed `synesis-provider-manual` skill, which `provider install`
puts in `~/.claude/skills` or `~/.codex/skills`, and the Synesis MCP server
points the agent at it when it connects. A project initialized by an earlier
version loses only the `AGENTS.md` section and the rule file Synesis wrote
(your own text and files stay) the next time `synesis init` runs. To add a
second computer, see `synesis link invite` and `synesis link join`
([ADR-0098](../adr/0098-one-project-across-many-computers.md)).

`.synesis/` is the canonical Synesis bookkeeping boundary. Windows marks the
directory Hidden by default, while Unix-like systems rely on the leading dot.
That is only a presentation convenience, not a security or authorization
boundary. A move, copy, archive extraction, or cross-platform transfer may
drop the attribute; the next supported open or attachment re-ensures it
without changing the project identity. User and agent files remain visible.

## Normal workflow

Use a managed provider hook or MCP connection. Reads return a revision for the
exact file bytes, and modifications must provide that revision as a precondition.
One persistent MCP connection owns one provider binding and does not share a
worker worktree with another connection.

See [project layout](../installation/project-layout.md), [provider management](../installation/provider-management.md),
and the [CLI reference](../cli/command-reference.md).
