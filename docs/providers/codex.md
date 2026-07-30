# Codex

Install the project integration with:

```powershell
synesis provider install codex
synesis provider status codex
```

The user-scoped Codex MCP configuration is `%USERPROFILE%\.codex\config.toml`.
Synesis manages only `mcp_servers.synesis` and preserves unrelated TOML. Codex
remains experimental and degraded until trusted real `/hooks` evidence exists.
Legacy project `.codex/mcp.json` Synesis entries are removed during install;
unrelated entries are preserved. Installation does not modify Codex trust
state.
