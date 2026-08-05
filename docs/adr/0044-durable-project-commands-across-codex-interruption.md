# ADR-0044: Durable project commands across Codex interruption

- Status: Accepted
- Date: 2026-08-04
- Task: SYN-038 extension
- Supersedes: none
- Extends: ADR-0043

## Decision

Extend the completed SYN-038 Codex App Server lifecycle implementation with
durable project-command admission and interruption state. The existing
`synesis coordination serve` owner, ten-tool MCP catalog, direct `run_command`
schema, `ProjectCommandService`, `ProjectProcessExecutor`, lease/workspace
authority, cleanup/repair entry points, and independent Codex interruption
semantics remain unchanged.

The extension uses a host-wide command namespace under
`AdministrativeStateLocator.applicationStateRoot()/commands/`. Its
`namespace.lock` and published physical-worktree lock files are permanent
filesystem synchronization objects and are never replaced, renamed, deleted,
quarantined, or recreated. Mutable metadata is written through unique sibling
temporaries and atomic replacement.

Physical exclusion uses only the verified real worktree identity. Command
records are keyed by one fresh immutable MCP process anchor plus the canonical
typed JSON-RPC request ID. Existing-request replay/conflict lookup is read-only.
New admission uses the optimistic release/reacquire protocol: capture command
authority, release command protection, renew and heartbeat the lease, reacquire
protection, re-read authority, and persist `STARTING` only when the expected
state is unchanged.

Durable command state remains `STARTING`, `RUNNING`, `AMBIGUOUS`, or verified
`TERMINAL`. Unknown newer formats, corrupt state, unresolved identity, changed
authority, and unsafe cleanup fail closed. Only verified terminal evidence may
be replayed or compacted. Real-Codex acceptance is limited to identity,
durability, replay, admission, protected interruption, and natural completion;
capacity, cleanup, unsupported-format, crash-injection, and pinned-evidence
cases are deterministic fixtures only.

## Compatibility and history

The previous SYN-038 App Server implementation remains completed history at
the existing commit, checkpoints, and acceptance evidence. This ADR does not
overwrite or reinterpret that evidence. The durable project-command work is an
extension phase of SYN-038 and does not create SYN-039.

## Consequences

- Durable command state can survive MCP/Codex conversational interruption.
- Permanent lock objects prevent metadata replacement from splitting process
  contention across different file objects.
- New-request lease renewal can race with other authority changes, so the
  release/reacquire validation must abort safely without a second renewal.
- Capacity and cleanup behavior require deterministic boundary and fault
  fixtures rather than destructive real-Codex acceptance.
- No universal provider command-cancellation guarantee is introduced.
