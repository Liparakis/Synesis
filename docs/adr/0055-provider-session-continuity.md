# ADR-0055: Provider-session continuity across MCP process restart

Status: Design investigation complete; implementation blocked pending a
provider identity contract. No production design is accepted.

## Context

The compliant SYN-049 acceptance proved explicit completion and structured
capability dependencies, but Worker B could not continue after its MCP process
was restarted. The new process generated a different `connectionInstanceId`;
the existing binding remained owned by the original connection, so exact
authority resolution failed closed and the new connection required a fresh
coordination intent.

This is a separate defect from completion and dependency admission. SYN-049 is
PARTIAL, its fixture remains preserved evidence, and no worker may be
re-admitted or manually repaired to make it pass.

## Decision gate

Before production changes, trace the current MCP startup, provider launcher
and metadata, durable binding fields, `SessionAuthorityResolver`, clean and
abnormal disconnect, wake/rebind, and existing recovery paths. Inventory only
provider evidence actually available across an MCP restart. Classify each value
as diagnostic correlation or authority-bearing continuity proof; stability alone
does not grant authority.

Evaluate both legitimate design classes:

1. A stable conversation-scoped connection identity supplied or derived by the
   existing provider integration, with project/provider scoping and explicit
   handling for concurrent process generations.
2. A new connection presenting bounded continuity evidence to an existing,
   audited and fenced handoff/recovery path, with durable transfer, replay
   protection, liveness rules, and one-winner race semantics.

ADR-0055 is not accepted until source evidence selects one class and defines
the trust proof and concurrency rules. No implementation may weaken exact
authority lookup or select the latest provider/session/participant.

## Trace result

The source trace is recorded in
[`syn050-provider-session-continuity-trace-2026-09-03.md`](../evidence/syn050-provider-session-continuity-trace-2026-09-03.md).
The ordinary MCP process currently receives only the launcher-provided
`SYNESIS_MCP_CONNECTION_INSTANCE_ID`, or a random generated connection ID.
`McpProtocolHandler.initialize` does not consume provider conversation
metadata. The Codex hook can see `session_id`/`conversation_id`, but the
separate hook process does not pass that value to the MCP process. The Codex
MCP configuration is static and supplies no dynamic thread identity.

Option A therefore cannot be selected from the current trust boundary. A
stable shell variable, workspace marker, latest hook record, or model-supplied
value is not proof and would break concurrent-session isolation. Option B is
also not present in the normal MCP path: the existing continuation mechanism
is an audited, single-use, snapshot-backed transfer to a new participant and
intent, not same-session reattachment. A generic reattach ticket would be a
new provider/MCP trust protocol rather than a bounded change to an existing
resolver.

The design gate is consequently stopped. A provider integration must first
deliver an exact per-conversation identity or equivalent audited continuity
proof to the MCP process. Until then, production edits would either be
speculative or weaken authority. SYN-050 remains blocked and the preserved
SYN-049 fixture is not resumed.

## Invariants

- Connection identity remains authority-sensitive.
- An unrelated MCP process cannot inherit provider authority.
- Stale and terminal bindings remain fenced.
- Participant/session identity is never inferred from the latest binding.
- Provider lifecycle history remains monotonic.
- Recovery does not create a duplicate participant, WorkIntent, or claim.
- A live original and a recovering process cannot silently share authority.
- Restart races have exactly one lawful winner when recovery is allowed.
- Replayed or stale continuity proof fails closed.
- The existing ten-tool MCP catalog remains unchanged.

## Consequences and acceptance

The selected design must prove same-conversation restart recovery, unrelated
process rejection, live-original behavior, race behavior, replay/stale-proof
rejection, terminal-session behavior, and restart-plus-explicit-completion.
The recovered lane must continue through existing capability, review,
publication, integration, and terminal lifecycle semantics without re-admission
or manual state changes. Existing Doctor warnings remain separate unless the
compliant continuity acceptance directly reproduces a new one.

The preserved SYN-049 fixture may be resumed only if its existing durable
evidence is compatible with the selected design. Otherwise, use a fresh
continuity fixture and record the incompatibility rather than rewriting
history.

## Explicit non-decisions

No provider-wide or latest-session fallback, stable value treated as proof
without a trust boundary, inferred participant recovery, durable-state rewrite,
new MCP tool, worker-facing manual identifier, worktree copy, protocol bypass,
or unrelated Review/Doctor redesign is authorized.
