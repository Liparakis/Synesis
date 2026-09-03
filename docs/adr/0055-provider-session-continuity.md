# ADR-0055: Provider-session continuity across MCP process restart

Status: Design investigation complete; Result C; implementation blocked pending
a provider continuity contract. No production design is accepted.

## Context

The compliant SYN-049 acceptance proved explicit completion and structured
capability dependencies, but Worker B could not continue after its MCP process
was restarted. The new process generated a different `connectionInstanceId`;
the existing binding remained owned by the original connection, so exact
authority resolution failed closed and the new connection required a fresh
coordination intent.

This is separate from completion and dependency admission. SYN-049 remains
PARTIAL, its fixture remains preserved evidence, and no worker may be
re-admitted or manually repaired to make it pass.

## Current identity model

The source has no standalone `ProviderSession` domain type. The durable logical
provider-session identity is `ProviderSessionBindingService.Binding.sessionId`.
The current relationship is:

```text
ProjectLocation.projectId
  └─ Binding.sessionId
       ├─ participantHandle(sessionId) → Participant projection
       └─ provider/session-derived WorkIntent → claims and authority lineage

MCP process → connectionInstanceId + process evidence + exact lease key

Codex App Server only → bindingSessionId + thread/turn
                         + attachment/connection generations
```

`connectionInstanceId` is an ephemeral transport identity, although its hash
is retained as binding evidence. PID, command line, start time, nonce, and hook
metadata are diagnostic/liveness evidence. Project/session/participant/intent,
claims, authority lineage, and signed event state are durable logical or
authority state. No current resume credential exists.

## Decision gate

Before production changes, trace MCP startup, provider metadata, durable binding
fields, `SessionAuthorityResolver`, clean and abnormal disconnect, wake/rebind,
and existing recovery. Classify values as diagnostic correlation or
authority-bearing proof; stability alone does not grant authority.

Evaluate both legitimate design classes:

1. A provider-supplied stable conversation identity at the ordinary MCP trust
   boundary, with explicit concurrent-generation handling.
2. A Synesis-issued continuity capability presented through a trusted provider
   channel, with durable transfer, replay protection, liveness rules, and
   one-winner generation fencing; the existing recovery path is considered
   separately and must not be mistaken for same-session reattachment.

## Verified source path

`SynesisMcpServer` receives `SYNESIS_MCP_CONNECTION_INSTANCE_ID` or generates a
random connection value. `McpProtocolHandler.initialize` extracts roots but no
Codex thread/conversation identity. `SessionResolutionRequest` carries only
project root, provider, connection ID, task intent, and refresh. The exact
`SessionAuthorityResolver` match requires the connection fingerprint/raw
session match and `BOUND`; it never chooses a latest binding.

The Codex hook recognizes `session_id`/`conversation_id`, but it is a separate
process and does not forward that value to ordinary MCP. The static Codex TOML
entry supplies provider/project arguments and no conversation-scoped value.

`ProviderSessionBindingService.ensure` creates a new binding when a new
connection cannot select an existing exact binding; after clean detach it
deliberately creates a new binding. `SessionLeaseService` records process
evidence for an exact connection and does not transfer authority merely because
the old PID is absent. The existing `continueFromRecovery` path is an audited,
single-use, snapshot-backed transfer to a new participant and WorkIntent, not
same-session reattachment.

The source trace and expanded capability analysis are recorded in
[`syn050-provider-session-continuity-capability-design-2026-09-03.md`](../evidence/syn050-provider-session-continuity-capability-design-2026-09-03.md).

## Capability assessment

A Synesis-issued capability is server-side feasible in principle:

```text
lawful S/C1 → high-entropy R1 (store H(R1))
C1 disappears
C2 presents R1 through a trusted provider channel
→ exact revalidation + atomic consume/rebind + generation advance + R2
```

It would need exact project/provider/session/lineage scope, one-winner
serialization, single-use rotation, replay rejection, live/ambiguous/terminal
fail-closed behavior, and delayed-old-transport fencing. The capability must be
independent of public Synesis IDs; IDs are selectors, not secrets.

The current ordinary Codex/MCP boundary cannot deliver that capability safely.
The only available model-carried option would return R1 in tool output and
require the model to reproduce it in a later `ensure_session` call. That is a
model-visible bearer: context compaction can lose it, MCP restart does not
replay it to the server, the model can copy it into logs/files/output, and any
other process that obtains it can present it. Guidance cannot make it
confidential or prove conversation identity. A project file, static config,
environment value, hook record, or OS credential lookup is shared or lacks a
conversation binding. No current non-model-visible ordinary-Codex mechanism
has the required same-chat/other-chat separation.

The existing `CodexAppServerLifecycleService` is not a counterexample. Its
provider-supervised path stores exact binding-scoped `threadId`/`turnId` and
attachment/connection generations and can resume an exact thread. It is a
separate App Server architecture and cannot silently supply identity to normal
stdio MCP.

## Decision

**Result C — still blocked on a provider contract.**

No safe minimal fix is possible in Synesis alone for the target ordinary Codex
stdio path. Synesis can specify a hash-backed, rotating, generation-fenced
continuity protocol, but it cannot prove that the same Codex conversation will
automatically retain and present the proof after an MCP restart while an
unrelated conversation cannot obtain it. A model-visible token, latest-binding
fallback, or project-local secret would be a false fix and would weaken the
authority boundary.

The missing provider primitive is an automatically injected, authenticated
per-conversation identity or confidential continuity assertion for every
ordinary stdio MCP process, including restarts, or a provider-controlled
launcher/callback that proves the new MCP process is attached to the same
conversation. It must be hidden from unrelated chats/processes, replay-safe,
concurrency-aware, and usable without asking the model to copy a secret.

## Required future invariants

- Connection identity remains authority-sensitive; no latest-session fallback.
- A project/provider/participant/session ID alone never grants recovery.
- A live or ambiguously live C1 cannot silently share authority with C2.
- A valid recovery has exactly one durable winner and advances one current
  transport generation; delayed C1 requests are stale.
- Consumed credentials cannot be replayed; rotation is crash-safe or fails
  closed.
- Clean `DETACHED` and `RECOVERY_HELD` retain their current meanings; neither
  is silently reinterpreted as same-session reattachment.
- `isSessionTerminal`/terminal proof, binding terminal/revoked/completed/
  cancelled states, and participant terminal states cannot be revived.
- Existing App Server attachment wake/fencing remains separate unless a future
  provider contract explicitly unifies the paths.
- The ten-tool MCP catalog, `mcp-contract` boundary, and exact authority
  resolver semantics remain unchanged unless a separately accepted design says
  otherwise.

## Conditional implementation plan

This plan is not authorized by the current Result C. After the missing
provider primitive exists, re-open this ADR and revalidate its trust contract.
Then use `ensure_session` as the candidate ingress, extend the actual
`McpProtocolHandler`/`SessionResolutionRequest`/`AgentSessionService`/
`ProviderSessionBindingService` seam only as needed, and preserve the existing
participant, WorkIntent, claims, review, publication, integration, and
terminal state. Use one proven durable generation fence; do not add a second
epoch unnecessarily. Add focused tests for same-conversation recovery,
unrelated-process rejection, live/ambiguous behavior, one-winner races,
replay, late requests, terminal sessions, wake interaction, and the final
SYN-049 completion acceptance. Rebuild and prove installed artifact
provenance before any real fixture run. Do not add an MCP tool or alter
historical fixtures.

## Explicit non-decisions

No provider-wide or latest-session fallback, stable value treated as proof
without a trust boundary, inferred participant recovery, durable-state rewrite,
new MCP tool, worker-facing manual identifier, worktree copy, protocol bypass,
or unrelated Review/Doctor redesign is authorized. SYN-049 remains PARTIAL and
is not reopened by this ADR.
