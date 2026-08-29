# SYN-009A Package and Module Boundaries

```text
:link
  ↑
:project-record
  ↑
:coordination
  ↑
:workspace
  ↑         ↑
:cli       :mcp
```

The CLI and MCP modules are peer transport adapters.

- `:cli` owns Picocli command parsing and terminal formatting.
- `:mcp` owns stdio JSON-RPC 2.0 frames and MCP tool handlers (`org.synesis.mcp`).
- `:cli` does NOT depend on `:mcp`.
- `:mcp` does NOT depend on `:cli`.
- Both depend on shared application services in `:workspace` (`org.synesis.workspace.application`,
  `org.synesis.workspace.agent`).

The boundary is enforced by `:mcp:architectureCheck` and Gradle dependency locks.
