# Provider guides

Synesis currently recognizes three provider IDs:

| Provider ID   | Support level                        | Configuration boundary                                    |
|---------------|--------------------------------------|-----------------------------------------------------------|
| `codex`       | experimental / trust review required | user Codex TOML plus project-local hooks where applicable |
| `claude`      | experimental                         | project-local `.claude/settings.json` and `.mcp.json`     |

- [Codex](codex.md)
- [Claude Code](claude-code.md)
- [Provider-neutral MCP behavior](mcp.md)

Provider installation is project-local and preserves unrelated configuration.
Real-agent enforcement or trust is not claimed merely because installation or a
synthetic health check succeeds.
