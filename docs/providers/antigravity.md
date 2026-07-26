# Antigravity

Install the project integration with:

```powershell
synesis provider install antigravity
synesis provider status antigravity
```

Project hook configuration is `.agents/hooks.json`. The production MCP
configuration is `%USERPROFILE%\.gemini\config\mcp_config.json`; Synesis
preserves unrelated entries. Antigravity is beta: synthetic checks are
implemented, but the recorded real-agent path remains unvalidated.
