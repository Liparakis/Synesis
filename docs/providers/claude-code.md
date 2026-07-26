# Claude Code

Install the canonical provider ID with:

```powershell
synesis provider install claude
synesis provider status claude
```

The project-local hook configuration is `.claude/settings.json`; project MCP
configuration is `.mcp.json`. The hook adapter is invoked as:

```powershell
synesis hook claude-code
```

`claude-code` remains accepted as an installation/status/uninstall input alias
for compatibility. Claude Code support is experimental until a real
authenticated agent run is recorded.
