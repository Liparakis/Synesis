# Agent instructions contract

Corrected 2026-10-20 (ADR-0103 decision 9). This document used to describe the
`AGENTS.md` contract of an initialized project; that contract no longer exists.

**One place holds the agent instructions for a user's project: the installed
skill** `synesis-provider-manual`, in `~/.claude/skills` and `~/.codex/skills`.
`ProviderManualService` writes it when a provider is installed (`synesis provider
install claude|codex`), attests it by content and catalog digests, and replaces it
when it is missing or outdated. It states the whole protocol once: claims,
the `get_next_action` inbox, mutation through the Synesis tools, recovery, and
safe stopping.

What a project holds instead of instructions: nothing. `synesis init` writes no
`AGENTS.md` section and no provider rule file, and `provider install` writes none.
Two things point an agent at the skill:

- the skill's own description, which a harness matches against the task;
- the `instructions` field of the Synesis MCP server's `initialize` result, shown
  to every connected agent, which names the skill.

## Existing projects

A project initialized before this change carries what the earlier `init` wrote.
Initialization on such a project (`synesis init` again, or a replica's
initialization) removes only what is Synesis's own:

- the text between `<!-- SYNESIS-BEGIN -->` and `<!-- SYNESIS-END -->` in the
  project root `AGENTS.md`; the user's text stays, with the file's own line
  separator, and a file left holding nothing else is deleted;
- a provider rule file (`.claude/rules/synesis.md`) that begins with Synesis's
  ownership marker; a file at that path without the marker is the user's and is
  left alone.

Markers that do not pair up (one without the other, or more than one of either)
and a symbolic link are never touched; the section stays for the user to remove.
The removal is a change to the user's working tree and is not committed. Where Git
tracks `AGENTS.md`, the change shows as a modification the user commits;
`synesis init` says so. `AGENTS.md` is otherwise the user's own source file: it
travels with a lane's changes and is no longer a managed path of the baseline.

The section is guidance, not an authorization mechanism, as it never was.
Authorization remains coordinator-side and provider enforcement remains a release
gate.
