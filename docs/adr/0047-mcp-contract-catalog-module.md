# ADR-0047: Keep the standalone MCP contract catalog module

## Status

Accepted after architecture review on 2026-08-29.

## Decision

Keep `:mcp-contract` as a deliberately small standalone Java library containing
the production `org.synesis.mcp.contract.McpToolCatalog` class and its focused
test. Do not move the class into `:mcp`, `:workspace`, `:coordination`, or
`:cli`.

## Evidence

`McpToolCatalog` is not a passive DTO holder or a list of constants. Its one
immutable descriptor set is the authoritative source for:

- the ten raw MCP tool names and `tools/list` projection;
- wire-compatibility and catalog-content identities;
- provider-facing guidance renderer inputs; and
- rendered guidance artifact digests used by provider attestation.

The class is 556 lines and exposes nested immutable `Descriptor` and `Identity`
records. It is consumed in production only by:

- `:mcp`, which dispatches the raw tool surface and uses the catalog schemas;
- `:workspace`, which validates provider MCP metadata and guidance attestation.

The current Gradle edges are:

```text
:mcp-contract  -> no production project
:workspace     -> :mcp-contract, :link, :project-record, :coordination
:mcp           -> :mcp-contract, :link, :project-record, :coordination, :workspace
:cli           -> :link, :project-record, :workspace, :coordination
                 (runtime-only :mcp)
```

The class was intentionally extracted from `:mcp` in SYN-036. The introducing
change deleted the old `:mcp` catalog, added `:mcp-contract`, and added the
catalog dependency to both `:mcp` and `:workspace`, which is direct history
evidence that the module was created to share this contract without coupling
the consumers to each other.

## Alternatives rejected

### Move into `:mcp`

Rejected. `:mcp` already depends on `:workspace`, while `:workspace` consumes
the catalog. Making `:workspace` depend on `:mcp` would create the project
cycle `:mcp -> :workspace -> :mcp`. It would also make provider workspace
logic depend on the MCP implementation module.

### Move into `:workspace`

Rejected on ownership and layering grounds. This is technically acyclic today
because `:mcp` already consumes `:workspace`, but it would make the workspace
application module own the MCP wire contract that the MCP transport advertises.
That reverses the reason for the original extraction and couples future MCP
consumers to workspace implementation concerns.

### Move into `:coordination`, `:project-record`, or `:link`

Rejected. These are lower-level domain/infrastructure modules and do not own
the MCP protocol or provider guidance. Adding MCP protocol vocabulary there
would invert the current dependency direction and contaminate reusable domain
boundaries with an adapter contract.

### Move into `:cli`

Rejected. `:workspace` and `:mcp` cannot depend on the CLI without introducing
an upward dependency and, for `:mcp`, a cycle through the CLI's runtime
packaging. The CLI is an adapter, not the owner of the MCP contract.

## Consequences

The repository retains one small Gradle project and one extra artifact, but the
cost is bounded: the module has no production dependencies, has strict
compiler/Javadoc checks, and has a focused deterministic test. In return, the
MCP wire surface and provider attestation use one executable source of truth,
and the current dependency graph remains acyclic and ownership-aligned.

Reconsider this boundary only if the catalog becomes private to one consumer,
or if a future stable protocol API module is introduced with an explicit ADR,
independent ownership, and evidence that its publication value exceeds the
additional coordination cost.
