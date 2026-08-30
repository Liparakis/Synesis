# Zero-touch provider maturity matrix

This matrix separates provider integration evidence from zero-touch readiness.
The current installable provider IDs are `codex` and `claude`; both remain
`EXPERIMENTAL` until their real-agent trust and workspace-transition evidence
is complete.

| Provider | Project detection | Session bootstrap | Worktree proof | Mutation interception | Safe-boundary delivery | MCP evidence |
|---|---|---|---|---|---|---|
| Codex | project hook or explicit project | automatic local binding; real trust incomplete | missing | synthetic only; real trust incomplete | missing | `MCP_CONFIRMED_WORKING` |
| Claude Code | project hook or explicit project | automatic local binding; real validation incomplete | missing | synthetic only; real validation incomplete | missing | `MCP_CONFIRMED_WORKING` |

---

## Provider MCP capability audit matrix

| Capability / attribute | Codex | Claude Code |
|---|---|---|
| Local stdio MCP servers supported | VERIFIED | VERIFIED |
| Actual configuration location | `%USERPROFILE%\\.codex\\config.toml` | `<project>/.mcp.json` |
| Actual configuration schema | `mcp_servers.synesis` TOML table | `mcpServers.synesis` JSON object |
| Project-local hook configuration | `<project>/.codex/hooks.json` | `<project>/.claude/settings.json` |
| Command and argument arrays | VERIFIED | VERIFIED |
| Environment variables supported | VERIFIED | VERIFIED |
| Provider automatically launches MCP process | VERIFIED by confirmed MCP evidence | VERIFIED by confirmed MCP evidence |
| Visible terminal created | No; headless pipes | No; headless pipes |
| Project/workspace root communicated | PARTIAL; cwd or explicit project | PARTIAL; cwd or explicit project |
| Current working directory dependable | PARTIAL; explicit project may be required | PARTIAL; explicit project may be required |
| Provider restart required after config changes | PARTIAL; reload/session restart | PARTIAL; reload/session restart |
| Unrelated MCP servers preserved safely | VERIFIED | VERIFIED |
| Two simultaneous sessions distinguished | UNVERIFIED | UNVERIFIED |
| Stable connection/session ID exposed | UNSUPPORTED | UNSUPPORTED |
| Server restart reuses connection ID | UNSUPPORTED | UNSUPPORTED |

---

Promotion requires a trusted real run proving the complete column set, not only
the parser or a generated wrapper. A provider that fails a column remains
installed for diagnostics but cannot enter zero-touch mutation mode.

## MCP integration evidence tiers

These tiers describe MCP connection evidence only. They do not promote a
provider's hook, workspace, mutation-interception, or zero-touch maturity.

| Tier                    | Meaning                                                                                                                                                                                            |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MCP_CONFIG_DISCOVERED` | The expected provider configuration location and schema were identified, but a live MCP connection has not been verified.                                                                          |
| `MCP_CONNECTED`         | A live MCP process initialized successfully and exposed the expected Synesis tool surface.                                                                                                         |
| `MCP_CONFIRMED_WORKING` | A live MCP process initialized, exposed the expected tools, read a repository-relative file, created a file in the assigned worktree, reread it, and preserved the returned content revision/hash. |

Current confirmed evidence:

| Integration                                            | Tier                    | Scope and evidence                                                                                                                                                                                                    |
|--------------------------------------------------------|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Claude Desktop via Synesis MCP on `SynesisTestProject` | `MCP_CONFIRMED_WORKING` | One isolated session; all 10 tools visible; `AGENTS.md` read; `scratch.txt` created and reread; creation and reread hashes matched. This does not prove Claude hook enforcement or full-project workspace population. |
| Claude via Synesis MCP on `Test case`                  | `MCP_CONFIRMED_WORKING` | `README.md` read; `claude-mcp-routed-demo.txt` created and reread in the assigned worktree; revision hash matched. Native file-edit hooks were not used.                                                              |
| Codex via Synesis MCP on `Test case`                   | `MCP_CONFIRMED_WORKING` | `README.md` read; `mcp-routed-demo.txt` created and reread in the assigned worktree; revision hash matched. Native desktop hooks remain unproven.                                                                     |

The confirmed tier is evidence that the MCP transport and basic managed-file
operations work for that integration. It is not evidence that every provider,
every project path, or every provider-native mutation hook is enforced.

`READY_FOR_REAL_VALIDATION` means the project/node/provider session binding,
actor separation, and exact installed hook path are present. It is not evidence
that a real provider denied or intercepted a mutation.
