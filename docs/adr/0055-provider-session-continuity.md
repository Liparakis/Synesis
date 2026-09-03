# ADR-0055: Provider-neutral runtime authentication and session continuity

Status: Design investigation complete; Result A under the A/B/C/D choices;
implementation blocked for the current ordinary Codex stdio profile pending a
trusted provider continuity contract. No production design is accepted.

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

## Provider-boundary feasibility spike — 2026-09-03

The bounded provider-boundary spike verified the current installed provider
edges before proposing any continuity implementation. The local Codex MCP
entry is a static stdio launcher with no per-conversation thread input. Codex
hook/session evidence is separate from that MCP process. The local Claude MCP
boundary likewise provides static command/environment configuration and a
project root, while Claude hook `session_id` metadata is not automatically
carried into MCP. The detailed evidence is recorded in
[`SYN-050-provider-boundary-feasibility-2026-09-03.md`](../evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md).

The resulting classifications are deliberately boundary-specific:

- Ordinary Codex stdio MCP: **D** — provider support is required for
  transparent per-conversation continuity.
- Synesis-supervised Codex App Server: **C candidate** — the existing exact
  thread/resume lifecycle is a managed-launch seam, but its current transport
  identifiers are not by themselves a generic conversation authenticator.
- Ordinary Claude stdio MCP: **D** — provider support is required for
  transparent per-conversation continuity.
- A future Synesis-managed Claude launch: **C candidate** — hooks may supply
  correlation metadata, but a protected channel joining that evidence to MCP
  would still be required.

A static wrapper, anonymous broker, PID/lineage check, project secret, or
model-visible bearer cannot supply the missing proof. A protected
Synesis-owned launch channel could do so, but it is a distinct product mode
with explicit UX cost and is not implemented or accepted by this ADR. Result
A therefore remains the provider-neutral core architecture and the ordinary
provider profiles remain implementation-blocked. No provider configuration,
production source, durable state, or historical fixture was changed.

The future implementation gate is unchanged: first obtain either a provider-
authenticated per-conversation MCP assertion or an explicitly accepted
Synesis-managed launch channel. Then re-open this ADR, bind the proof to the
existing authority resolver and generation fences, and validate restart,
replacement, replay, race, terminal, and same-human-chat cases. Do not turn
the feasibility result into a new identity graph or a transparent wrapper
workaround.

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

## Decision under the A/B/C/D architecture choices

**Result A — existing core plus a generic runtime-authentication seam.**

No safe implementation is possible in Synesis alone for the current ordinary
Codex stdio path. Synesis can specify a hash-backed, rotating,
generation-fenced continuity protocol, but it cannot prove that the same Codex
conversation will automatically retain and present the proof after an MCP
restart while an unrelated conversation cannot obtain it. A model-visible
token, latest-binding fallback, or project-local secret would be a false fix
and would weaken the authority boundary.

The source already separates durable logical binding/session state from
ephemeral connection/process evidence, and it already has the strict
`SessionAuthorityResolver` authorization gate. A provider-neutral
authentication result can be inserted before that gate. A provider-specific
adapter belongs at the edge; a larger identity refactor is not justified.

The earlier shorthand “Result C” meant “blocked on a provider contract.” It is
not the A/B/C/D classification used by this ADR revision. Under that
classification, the architecture is Result A and the current implementation
disposition is blocked.

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

This plan is not authorized by the current implementation gate. After the missing
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

## Provider-neutral boundary

The conceptual, not-yet-public API is:

```text
provider adapter or managed attachment boundary
    → authenticate new/continuation runtime
    → verified logical binding + attachment generation
    → existing strict authority resolution
    → existing claims and coordination lifecycle
```

Authentication may establish only which durable logical binding a runtime may
speak for and which current attachment generation it holds. It must not grant
claims, review, completion, publication, integration, or task permissions.

`RuntimeAdapter` is needed conceptually to verify provider-specific evidence.
A local Runtime Broker is optional only for a genuinely Synesis-managed
provider boundary; it is not required for provider-authenticated mode and is
not authorized by this ADR. Anonymous providers remain session-bound.

| Profile | Root of trust | Supported continuity |
| --- | --- | --- |
| Provider-authenticated | Verifiable provider-controlled conversation/session assertion | Full continuity after scope, replay, and generation checks. |
| Synesis-managed | Protected Synesis launcher/broker attachment credential | Continuity only when Synesis truly mediates the provider boundary. |
| Anonymous | No runtime authentication root | Current transport only; restart continuity is unsupported. |

Ordinary Codex stdio is currently in the anonymous profile at this boundary.
Codex App Server is a separate supervised adapter-shaped path with exact
thread/turn and attachment/connection generations. Claude hook metadata is
provider correlation, not yet ordinary-MCP authentication. Neither thread ID
nor hook metadata becomes the core identity.

## Attachment and failure invariants

- A new attachment must authenticate before it can select the existing binding.
- A successful replacement advances one current attachment generation and
  fences the old generation; two races have one durable winner.
- A live or ambiguously live old attachment is not silently taken over.
- A consumed or stale proof is rejected; rotation is crash-safe or fails closed.
- Terminal binding/session/participant state is irreversible. `DETACHED` and
  `RECOVERY_HELD` retain their current meanings and are not reinterpreted as
  same-session reattachment.
- `SessionAuthorityResolver` remains exact and never falls back to the latest
  provider binding. Existing claim epochs, event revisions, workgroup versions,
  and execution-time fences remain in force.
- Any future durable attachment record/event must be project-, provider-,
  binding-, lineage-, and generation-scoped and must not expose a live secret in
  ordinary project files or model-visible context.

## Conditional implementation order

This ADR does not authorize implementation until the provider contract exists.
The next implementation order is: verify the provider root; reopen and
revalidate this ADR; add the smallest generic authentication input at the
existing session-resolution ingress; reuse or add one durable attachment
generation/audit seam; implement one provider adapter; add a broker only if a
controlled provider boundary requires it; then test replay, races, stale and
terminal state, restart, and preservation of existing authorization. Rebuild
and hash the installed artifact before any disposable runtime acceptance.

## Explicit non-decisions

No provider-wide or latest-session fallback, stable value treated as proof
without a trust boundary, inferred participant recovery, durable-state rewrite,
new MCP tool, worker-facing manual identifier, worktree copy, protocol bypass,
or unrelated Review/Doctor redesign is authorized. SYN-049 remains PARTIAL and
is not reopened by this ADR.
