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

Claude Code does not read `AGENTS.md`, where Synesis keeps its coordination
rules. Installation therefore also writes `.claude/rules/synesis.md`, a rule file
Claude Code loads by itself, which imports the managed `AGENTS.md` block rather
than copying it. The file is owned by Synesis: it is replaced on install and
removed on uninstall. A file you wrote at that path is left untouched, and the
install result reports `PROJECT_INSTRUCTIONS=CONFLICT`.

Claude Code support is experimental. Two Claude Code sessions have completed a
work group together. A mixed Claude Code and Codex run has completed a work group
with a negotiated capability, but only after its agent processes exited and
their lanes were recovered and continued; none has yet completed uninterrupted.
