# ADR-0055: Provider-neutral runtime authentication and session continuity

Status: Provider capability model approved; generic core architecture is
Result A; stock Codex isolated-runtime managed delivery is PASS-A at
feasibility level for the conservative dedicated-home topology. Dynamic
thread-scoped child injection remains unavailable. Ordinary Codex/Claude stdio
remain SESSION_BOUND. No production code is authorized by this ADR.

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
Synesis-owned launch channel is a distinct product mode with explicit UX
cost. This ADR now approves that mode as the first managed-continuity design
target for Codex App Server, but does not claim that the current provider
already delivers the protected channel. The ordinary provider profiles remain
SESSION_BOUND and implementation-blocked for seamless restart continuity.
No provider configuration, production source, durable state, or historical
fixture was changed.

The disposable provider-boundary prototype has now been run. Its isolated
Windows inherited-pipe model passed proof isolation, generation fencing,
replay, race, live/ambiguous, terminal, and secret-hygiene checks. The real
Codex App Server probe passed configured MCP launch, two exact-thread tool
calls, and exact process-restart/thread-resume/thread-read, but it did not
prove delivery of a Synesis-created protected proof to the exact managed MCP
bridge/thread. The managed implementation therefore remains Result B /
PROTOTYPE_PARTIAL. Do not turn the managed path into a new identity graph or
a transparent wrapper workaround.

## Codex App Server child-launch boundary investigation — 2026-09-03

The bounded follow-up investigation answers the remaining provider question for
the approved managed target: whether an exact Codex thread can privately carry
a per-worker attachment proof into the MCP child that App Server launches.
The result is **FAIL for the dynamic child-launch carrier in `codex-cli
0.145.0`**, while the isolated Synesis-side inherited-pipe carrier remains a
separate PASS. This section did not test isolated worker homes or configured
`env_vars`; that follow-up is recorded below. It did not authorize production
managed continuity by itself.

Version-matched Codex source shows the following boundary:

```text
thread-owned McpRuntime
  → McpConnectionManager
  → codex-mcp::rmcp_client::make_rmcp_client
  → RmcpClient::new_stdio_client
  → rmcp-client::StdioServerLauncher
  → LocalStdioServerLauncher::launch_server
```

The configured stdio launch input contains only `command`, `args`, `env`,
`env_vars`, and `cwd`. The local launcher resolves the command, clears the
child environment, applies only configured environment values, sets the
working directory, and creates standard piped stdin/stdout/stderr. The
launcher boundary has no thread ID, attachment proof, extra-handle,
per-thread callback, or external private-carrier input. Its launcher trait is
sealed to the provider implementation.

The disposable Windows observation confirmed that one App Server can host two
exact threads with separate observed MCP child chains, and that two dedicated
App Servers can run concurrently with distinct thread and child processes. A
replacement App Server resumed the exact A thread and relaunched its child
while B remained live; a wrong-thread request was rejected. Every wrapper and
child received the static configuration marker, but not parent-only process
environment, thread context, or attachment proof. A wrapper is therefore
operationally viable but not an authentication boundary. The detailed record
is [`SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`](../evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md).

The conservative one-App-Server-per-worker topology is operationally viable,
but dynamic private proof delivery is not proven and is classified FAIL at the
current provider boundary. Shared-process multiplexing cannot be treated as a secure
continuity topology without a genuinely thread-scoped carrier. Exact
`thread/resume` is correlation and lifecycle evidence; it is not sufficient by
itself to mint a fresh Synesis proof.

The smallest upstream/provider feature required is a provider-managed,
non-model-visible attachment input at `StdioServerLauncher` /
`LocalStdioServerLauncher::launch_server`, with plumbing from
`make_rmcp_client` and the thread-owned `McpRuntime`. It must inject a unique,
rotated, generation-scoped context into the exact managed child without using
static configuration, a command-line bearer, a global secret, or model-visible
protocol data. No Codex source was patched in this pass.

## Stock Codex isolated-runtime feasibility — 2026-09-03

The bounded follow-up tested the separate hypothesis that static MCP
configuration becomes per-worker when each logical worker receives its own
`CODEX_HOME` and dedicated App Server process. The disposable experiment used
stock `codex-cli 0.145.0`, separate worker homes, distinct exact threads, and
distinct MCP child chains.

Both supported configured-environment forms passed A/B delivery. A static
worker-specific MCP `env` value reached child A or B only, and the supported
allow-list `env_vars = ["SYN050_ATTACHMENT_PROOF"]` forwarded each managed
App Server's process-private value to its own child. A/B digests were distinct,
concurrent, and absent from the other worker's child. No global Codex
configuration was changed.

The same isolated A home resumed the exact A thread after App Server restart.
Replacing its configured value before restart delivered A2 and not A1. A
fresh home could not resume the thread, returning `no rollout found`; thread
history is therefore home-scoped in this stock configuration. Deleting config
after load did not affect the live child, while restarting without config left
thread resume available but made the MCP server unavailable.

Direct static `env` stores the proof in plaintext under inherited Windows ACLs.
The `env_vars` form keeps the proof out of `config.toml` while still placing it
in the managed App Server/child process environment. This remains within the
accepted non-compromised-local-OS threat boundary, not a kernel-isolated
secret. The disposable report and hygiene results are recorded in
[`SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`](../evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md).

This is **PASS-A feasibility**, not production implementation. The earlier
child-launch result remains a narrower FAIL for dynamic thread/proof/
extra-handle ingress and is not contradicted: isolated runtime configuration
is the carrier in this pass. A future bounded implementation may use one
retained isolated Codex home per logical worker, selected `env_vars`, exact
thread resume, and generation rotation, subject to a new implementation task
and production review.

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
classification, the provider-neutral core architecture is Result A. This
managed Codex design pass is Result B because the exact provider delivery
boundary still needs a narrow prototype; ordinary stdio implementation stays
blocked.

The missing managed-path proof is a provider-controlled or genuinely
Synesis-managed launcher/IPC callback that proves the new MCP bridge is
attached to the exact managed App Server thread. It must be hidden from
unrelated chats/processes, replay-safe, concurrency-aware, and usable without
asking the model to copy a secret. Ordinary stdio still lacks this primitive.

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

`RuntimeAuthenticator` is the generic seam for provider-specific evidence.
The Codex implementation is an adapter beside the existing App Server
lifecycle. A local broker/launcher channel is authorized only for the
explicitly managed Codex profile described below; it is not used by
provider-authenticated mode. Anonymous providers remain session-bound.

| Profile | Root of trust | Supported continuity |
| --- | --- | --- |
| Provider-authenticated | Verifiable provider-controlled conversation/session assertion | Full continuity after scope, replay, and generation checks. |
| Synesis-managed | Protected Synesis launcher/broker attachment credential | Continuity only when Synesis truly mediates the provider boundary. |
| Anonymous | No runtime authentication root | Current transport only; restart continuity is unsupported. |

Ordinary Codex stdio is currently `SESSION_BOUND` at this boundary. Codex App
Server is the first approved `MANAGED_CONTINUITY` adapter target, subject to
the protected-channel prototype gate, with exact thread/turn and
attachment/connection generations. Claude hook metadata is provider
correlation, not ordinary-MCP authentication. Neither thread ID nor hook
metadata becomes the core identity.

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

This ADR authorizes design and disposable provider-boundary experiments, not
production implementation. The next order is: obtain a documented
provider-controlled protected carrier; reopen this ADR against that provider
result; add the smallest generic authentication input at the existing
session-resolution ingress; reuse the existing attachment/connection
generations and lifecycle revision; implement one Codex adapter; add only the
required managed launcher/channel; then test replay, races, stale and terminal
state, restart, and preservation of existing authorization. If the carrier
cannot be proven, stop with ordinary MCP in `SESSION_BOUND` and do not
implement a workaround.

## Managed-continuity decision — Codex App Server — 2026-09-03

The approved capability model is:

| Profile | Trust input | Continuity disposition |
| --- | --- | --- |
| `NATIVE_CONTINUITY` | Provider-controlled, non-model-visible conversation assertion | Future provider adapter path. |
| `MANAGED_CONTINUITY` | Protected Synesis-managed runtime attachment proof | Approved capability; Codex App Server is the first target. |
| `SESSION_BOUND` | Current authenticated transport only | Ordinary Codex/Claude MCP; restart continuity is explicitly unsupported. |

The managed trust root is the combination of the existing admitted Synesis
binding and claims, Synesis ownership of the App Server launch, the exact
provider thread returned by `thread/start`/`thread/resume`/`thread/read`, and a
protected non-model-visible attachment handshake. A thread ID is exact
provider correlation, not a secret or authority proof. PID, process lineage,
worktree, repository, provider name, and same-human context remain diagnostic
or contextual evidence only.

The selected channel is a per-attachment OS-local protected IPC channel, with
an inherited handle as the primary delivery mechanism. Synesis creates a
fresh random proof for each attachment generation, keeps only its hash and
non-secret scope metadata in the existing Codex lifecycle state, and verifies
a one-time domain-separated challenge response. The raw proof remains in
trusted process memory/IPC and never enters model context, project files,
ordinary logs, guidance, exceptions, or durable audit plaintext. A static
environment/configuration value, model-visible token, project secret, PID
check, or OS credential lookup alone is rejected.

The first implementation preserves the current one-App-Server-process /
one-exact-thread-per-binding invariant. If a provider process multiplexes
multiple logical threads, a thread-scoped provider assertion is required;
process identity or process-wide environment cannot authenticate the threads.

The existing `Checkpoint.attachmentGeneration` is reused as the monotonic
runtime generation; `connectionGeneration` remains the transport generation,
`revision` remains the lifecycle CAS fence, `threadId` remains provider
correlation, and `bindingVersion` remains the authority snapshot/version. No
new core logical-worker ID or second epoch is introduced. A versioned
adapter-private `managedAttachment` record/object is required to hold an
opaque attachment ID, exact provider thread correlation, generation, proof
hash, scope digest, and current/consumed/revoked state. It contains no raw
credential.

The replacement transaction runs under the existing project and per-binding
attachment serialization: authenticate the private proof, re-read the
logical binding and all existing authority predicates, require old-channel
closure or an explicit provider handoff, compare the current generation,
consume the old proof, advance the generation, atomically write the new
managed record/checkpoint revision, and fence the old attachment. One of two
concurrent replacements wins; stale, replayed, live-old, ambiguous, wrong-
thread, and terminal requests fail closed. A crash that leaves the managed
record and checkpoint inconsistent also fails closed and requires a fresh
managed attach.

For App Server restart, Synesis starts the replacement, resumes the exact
stored thread, requires exact provider identity and `thread/read`, creates a
new protected channel, and advances the generation. For a full host restart,
the host may do the same only after proving the old managed runtime is absent
or has completed an explicit provider handoff. It never adopts by PID. If
liveness or provider state is ambiguous, the state remains ambiguous and no
automatic continuity is claimed.

The generic seam belongs in the existing `workspace` application/provider
boundary as `RuntimeAuthenticator`; the Codex implementation belongs beside
the existing `workspace.lifecycle.codex` App Server lifecycle. The seam
returns only an already durable logical binding reference, provider,
authentication method, opaque provider runtime reference, and attachment
generation. Existing `SessionAuthorityResolver`, participant/WorkIntent,
claims, leases, review, completion, integration, and terminal fences remain
unchanged and run after authentication. Codex JSON-RPC stays in the adapter.

The minimum UX is an explicit managed launch mode, recommended for the
prototype as `synesis provider run codex --managed --project <path>` after
the existing `synesis provider install codex --project <path>` prerequisite.
That `run` command does not exist yet; it is a proposed future surface. The
ordinary static MCP workflow remains available and remains `SESSION_BOUND`.
Synesis manages attachment, provider lifecycle, restart/resume, and fencing;
it does not choose prompts, reason, plan, allocate arbitrary workers, replace
Codex UI, or become a harness.

The provider-neutral decision remains **Result A — existing core plus a generic
runtime-authentication seam**. The stock isolated-runtime experiment is
**PASS-A feasibility** for the conservative dedicated-home topology, while the
dynamic child-launch boundary remains a bounded **FAIL** for thread/proof/
extra-handle ingress. Production managed continuity was not implemented in
this pass; ordinary MCP stays `SESSION_BOUND`.

The complete design, prototype result, focused tests, acceptance plan,
unknowns, and evidence classification are recorded in
[`SYN-050-protected-carrier-prototype-2026-09-03.md`](../evidence/SYN-050-protected-carrier-prototype-2026-09-03.md)
and
[`SYN-050-managed-attachment-design-2026-09-03.md`](../evidence/SYN-050-managed-attachment-design-2026-09-03.md).
The exact child-launch source trace and disposable result are in
[`SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`](../evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md).

## Explicit non-decisions

No provider-wide or latest-session fallback, stable value treated as proof
without a trust boundary, inferred participant recovery, durable-state rewrite,
new MCP tool, worker-facing manual identifier, worktree copy, protocol bypass,
or unrelated Review/Doctor redesign is authorized. SYN-049 remains PARTIAL and
is not reopened by this ADR.
