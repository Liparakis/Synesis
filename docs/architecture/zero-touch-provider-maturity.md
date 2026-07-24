# Zero-touch provider maturity matrix

| Provider | Project detection | Session bootstrap | Worktree proof | Mutation interception | Safe-boundary delivery | MCP Discovery Status |
|---|---|---|---|---|---|---|
| Codex | synthetic/project hook | automatic local binding; real trust incomplete | missing | synthetic only; real trust incomplete | missing | MCP_CONFIG_DISCOVERED |
| Antigravity | synthetic/project hook | automatic local binding; real trust incomplete | missing | synthetic; real hook bypass observed | missing | MCP_CONFIG_DISCOVERED |
| Claude Code | existing hook adapter | not planned in SYN-013 | not planned | existing adapter only | not planned | DEFERRED |

---

## Provider MCP Capability Audit Matrix (Stage 2A Slice 1)

| Capability / Attribute | Codex | Antigravity |
|---|---|---|
| Local stdio MCP servers supported | VERIFIED | VERIFIED |
| Actual configuration location | VERIFIED (`.codex/mcp.json`) | VERIFIED (`.agents/mcp.json` / `.gemini/mcp.json`) |
| Actual configuration schema | VERIFIED (`mcpServers.<name>.command/args/env`) | VERIFIED (`mcpServers.<name>.command/args/env`) |
| Project-local configuration supported | VERIFIED | VERIFIED |
| Command and argument arrays | VERIFIED | VERIFIED |
| Environment variables supported | VERIFIED | VERIFIED |
| Provider automatically launches MCP process | VERIFIED | VERIFIED |
| Visible terminal created | VERIFIED (No - headless pipes) | VERIFIED (No - headless pipes) |
| Project/workspace root communicated | PARTIAL (cwd or `--project` arg) | PARTIAL (cwd or `--project` arg) |
| Current working directory dependable | PARTIAL (Explicit `--project` arg required) | PARTIAL (Explicit `--project` arg required) |
| Provider restart required after config changes | PARTIAL (Reload workspace / session restart) | PARTIAL (Reload workspace / session restart) |
| Unrelated MCP servers preserved safely | VERIFIED (JSON map under `mcpServers`) | VERIFIED (JSON map under `mcpServers`) |
| Two simultaneous sessions distinguished | UNVERIFIED | UNVERIFIED |
| Stable connection/session ID exposed | UNSUPPORTED | UNSUPPORTED |
| Server restart reuses connection ID | UNSUPPORTED | UNSUPPORTED |

---

Promotion requires a trusted real run proving the complete column set, not only
the parser or a generated wrapper. A provider that fails a column remains
installed for diagnostics but cannot enter zero-touch mutation mode.

`READY_FOR_REAL_VALIDATION` means the project/node/provider session binding,
actor separation, and exact installed hook path are present. It is not evidence
that a real provider denied or intercepted a mutation.
