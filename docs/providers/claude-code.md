# Claude Code

Install the canonical `claude` provider ID with:

```powershell
synesis provider install claude
synesis provider status claude
```

The project-local hook configuration is `.claude/settings.json`; project MCP
configuration is `.mcp.json`. The hook adapter is invoked as:

```powershell
synesis hook claude
```

Claude Code does not read `AGENTS.md`, and Synesis writes none: the coordination
rules live in the installed `synesis-provider-manual` skill (`~/.claude/skills`),
which `synesis provider install claude` puts in place, and the Synesis MCP
server's `initialize` instructions name it. Earlier versions also wrote
`.claude/rules/synesis.md`; installation and `synesis init` now remove that file
when it is Synesis's own (it begins with Synesis's ownership marker). A file you
wrote at that path is left untouched, and the install result reports
`PROJECT_INSTRUCTIONS=CONFLICT`. The result reports `PROJECT_INSTRUCTIONS=REMOVED`
when it removed one and `ABSENT` when there was none.

Claude Code support is experimental. Two Claude Code sessions have completed a
work group together. A mixed Claude Code and Codex run has completed a work group
with a negotiated capability, but only after its agent processes exited and
their lanes were recovered and continued; none has yet completed uninterrupted.
