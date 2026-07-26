# Provider guides

Synesis currently recognizes three provider IDs:

| Provider ID | Support level | Configuration boundary |
| --- | --- | --- |
| `antigravity` | beta | project-local `.agents/hooks.json` and MCP configuration |
| `codex` | experimental / trust review required | user Codex TOML plus project-local hooks where applicable |
| `claude` | experimental | project-local `.claude/settings.json` and `.mcp.json` |

`claude-code` is a compatibility input alias for `claude`; it is not a second
provider. The hook adapter command is still `synesis hook claude-code`.

- [Codex](codex.md)
- [Antigravity](antigravity.md)
- [Claude Code](claude-code.md)
- [Provider-neutral MCP behavior](mcp.md)

Provider installation is project-local and preserves unrelated configuration.
Real-agent enforcement or trust is not claimed merely because installation or a
synthetic health check succeeds.
