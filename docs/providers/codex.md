# Codex

Install the project integration with:

```powershell
synesis provider install codex
synesis provider status codex
```

The user-scoped Codex MCP configuration is `%USERPROFILE%\.codex\config.toml`.
Synesis manages only `mcp_servers.synesis` and preserves unrelated TOML. Codex
remains experimental and degraded until trusted real `/hooks` evidence exists.
Stale project `.codex/mcp.json` Synesis entries are removed during install;
unrelated entries are preserved. Installation does not modify Codex trust
state.

Continuity mode is explicit: ordinary sessions are `SESSION_BOUND`. The
opt-in managed mode is `MANAGED_CONTINUITY`, using one dedicated `CODEX_HOME`,
one App Server, one exact Codex thread, and a server-issued attachment proof.
Synesis reports the mode; workers must not invent proofs, provider/session
identities, or durable state. If managed authentication is unavailable, the
runtime fails closed rather than copying credentials or falling back to a
different session.
