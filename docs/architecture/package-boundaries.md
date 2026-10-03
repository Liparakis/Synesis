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

## Workspace package layout

The workspace module keeps its shared project application facade at
`org.synesis.workspace.application.ProjectApplicationService`. Project identity,
location, and initialization result types live under
`org.synesis.workspace.application.project` beside project commands and process
execution.

Agent session admission lives under `application.agent.session`. The
next-action and task-completion services and their package-local no-change
eligibility rule stay together in `application.agent.workflow`. Cancellation
and completion request/outcome types live in `application.agent.completion`.
Provider session commands, authority resolution, and terminalization live
under `application.provider.session`. The shared binding service remains in
`application.provider` because its hook materialization path uses the provider
application service's package-private helper.

The local control-plane's Link, membership, route, relay, and network DTOs live
under `org.synesis.workspace.transport.control.network`; HTTP routing and
control-plane operation ownership remain under `transport.control`. Codex App
Server framing, schema, request envelope, and JSONL client code live under
`org.synesis.workspace.lifecycle.codex.protocol`; the loopback HTTP adapter
and client live under `org.synesis.workspace.lifecycle.codex.http`. Both stay
inside the Codex lifecycle boundary that owns their protocol and runtime.

`lifecycle.process` groups the shared Git command runner with the semantic
index fingerprint. The fingerprint uses a package-private bounded-output
overload, keeping that inspection option internal to the process package.
