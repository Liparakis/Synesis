# Antigravity

Install the project integration with:

```powershell
synesis provider install antigravity
synesis provider status antigravity
```

Project hook configuration is `.agents/hooks.json`. The canonical MCP
configuration is `%USERPROFILE%\.gemini\config\mcp_config.json`; the
entry contains only the provider and native launcher. The active project is
selected from MCP initialize roots (with the process working directory as a
bounded fallback), so installing another project cannot retarget this global
file. Synesis removes only stale Synesis
entries from the unused `%USERPROFILE%\.gemini\antigravity\mcp_config.json` mirror
and preserves unrelated entries. Antigravity is beta: synthetic checks are
implemented, but the recorded real-agent path remains unvalidated.
