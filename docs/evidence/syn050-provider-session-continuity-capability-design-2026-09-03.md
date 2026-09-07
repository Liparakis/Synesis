# SYN-050 provider-neutral runtime authentication / continuity design — 2026-09-03

Status: DESIGN COMPLETE / RESULT A (architecture) / IMPLEMENTATION BLOCKED for
the current ordinary Codex stdio profile. This is a source-backed design
decision only. No production code, `.synesis` state, provider state,
historical fixture, or worker lane was changed.

## 1. Starting state and provenance

- Original trace source HEAD: `01e0e71056f025c3a8f5a9716fdb9f03596fb8be`.
- This design revision started from clean source HEAD
  `2434a5e5b1070d547abcaa888cd4b21c336402d5`; the revision itself changes
  documentation only.
- Branch: `master`; working tree was clean.
- Preserved stash: `stash@{0}` (`preserve unrelated process portability work`).
- The Synesis source checkout is not being used as a Synesis-managed target and
  contains no project-local `.synesis` state.
- `GOONSQUAD`, KogMaw, and the preserved SYN-049 task-tracker fixture were not
  opened, restarted, re-admitted, copied, or mutated.
- The disposable architecture graph covered 23 relevant source files and
  reported 585 nodes, 1,798 edges, 15 communities, and no import cycles. It is
  diagnostic output outside this repository, not runtime or acceptance state.
- Local provider observations: `codex-cli 0.145.0`; `codex mcp get synesis`
  reports stdio, the installed Synesis launcher, a static `mcp --provider
  codex --project ...` command, and `env: -`.

## 2. Current identity model

The source does not define a standalone `ProviderSession` domain type. The
durable provider-session identity is `ProviderSessionBindingService.Binding`'s
`sessionId`.

```text
ProjectApplicationService.ProjectLocation.projectId
    │
    └── ProviderSessionBindingService.Binding
          ├── durable sessionId
          ├── provider and node identity
          ├── providerInstanceFingerprint
          ├── worktree and branch allocation
          └── binding status/version
                │
                ├── WorkspaceCollaborationService.participantHandle(sessionId)
                │       └── Participant projection and claims
                │
                └── WorkIntent announced for the same participant/session
                        ├── intentId, workGroupId, authorityLineageId
                        └── selectors, version, lifecycle, dependencies

MCP process
    ├── connectionInstanceId supplied by launcher or generated randomly
    ├── SessionProcessIdentity (PID, executable, start time, nonce)
    └── SessionLeaseRecord keyed by connectionInstanceId

Codex App Server path (separate from ordinary stdio MCP)
    └── bindingSessionId → checkpoint threadId/turnId
         → attachmentGeneration/connectionGeneration
```

This relationship is partly derived rather than represented by a foreign-key
object: the participant handle is derived from `Binding.sessionId`, and the
normal work-intent identifier is derived from the provider and binding session.
The source does not treat a participant ID, WorkIntent ID, or workgroup ID as a
recovery credential.

Relevant source symbols are [
`ProjectLocation`](../../workspace/src/main/java/org/synesis/workspace/application/ProjectApplicationService.java#L615),
[
`Binding`](../../workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java#L1343),
[`WorkIntent`](../../coordination/src/main/java/org/synesis/coordination/domain/collaboration/WorkIntent.java#L17),
[`Participant`](../../coordination/src/main/java/org/synesis/coordination/domain/collaboration/Participant.java#L17),
and [
`participantHandle`](../../workspace/src/main/java/org/synesis/workspace/application/collaboration/WorkspaceCollaborationService.java#L62).

## 3. Durability and authority classification

| Value                                                       | Classification                                         | Finding                                                                                                        |
|-------------------------------------------------------------|--------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| `ProjectLocation.projectId`                                 | durable logical identity                               | Project metadata identity.                                                                                     |
| `Binding.sessionId`                                         | durable logical identity                               | Current durable provider-session/binding identity.                                                             |
| Participant handle and `WorkIntent` IDs                     | durable logical identity                               | Signed/event-projected coordination identities; not secrets.                                                   |
| `workGroupId`, `authorityLineageId`, intent version, claims | durable authority state                                | Existing coordination and claim fencing.                                                                       |
| Node identity/signatures                                    | authority credential                                   | Existing local event-authentication mechanism; not a Codex conversation proof.                                 |
| `connectionInstanceId`                                      | ephemeral transport identity                           | Launcher value or random per-MCP-process value; also persisted only as binding fingerprint/lease evidence.     |
| MCP process, PID, start time, command line, nonce           | ephemeral/diagnostic                                   | Process evidence used for liveness and close fencing, not conversation identity.                               |
| `SessionLeaseRecord`                                        | durable diagnostic/liveness record                     | Exact connection key, session reference, and process evidence; it does not authenticate a replacement process. |
| App Server `attachmentGeneration`/`connectionGeneration`    | durable generation state in separate path              | Fences the supervised App Server attachment, not ordinary stdio MCP.                                           |
| App Server `threadId`/`turnId`                              | authority-scoped provider correlation in separate path | Exact-thread resume input for the existing App Server lifecycle; not supplied to normal stdio MCP.             |
| hook `session_id`/`conversation_id`                         | diagnostic provider correlation                        | Seen by a separate hook process and not forwarded to ordinary MCP.                                             |
| no resume token/capability                                  | absent                                                 | No existing continuity credential or generic same-session rebind record exists.                                |

## 4. Exact transport/authority coupling

`SynesisMcpServer` reads `SYNESIS_MCP_CONNECTION_INSTANCE_ID` or generates a
random value ([source](../../mcp/src/main/java/org/synesis/mcp/SynesisMcpServer.java#L36)).
`McpProtocolHandler` retains that value for the lifetime of the process and
passes it into `AgentSessionService.SessionResolutionRequest` and subsequent
operation requests. `AgentSessionService` calls
`ProviderSessionBindingService.ensure` with it. The normal binding path stores
the SHA-256 fingerprint under a connection-derived session file and
`SessionAuthorityResolver` accepts only an exact fingerprint/raw-session match
with `BOUND`
status ([resolver](../../workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java#L34)).

The coupling is therefore not that the durable binding lacks a session ID. The
coupling is that the only input which selects that binding is the current
connection evidence. A new MCP process gets a different value, so ordinary
`ensure` cannot select the prior binding. If the old participant is cleanly
detached, `ProviderSessionBindingService.ensure` deliberately creates a new
binding rather than silently reattaching the old one. This is the precise
source of the observed restart failure; it is not a reason to add a latest-
binding fallback.

## 5. Can logical authority and transport generation be separated?

Conceptually yes: the source already has a durable `Binding.sessionId` beside
an ephemeral `connectionInstanceId`. Operationally, ordinary MCP cannot safely
make the new transport authoritative because no trusted input proves that the
new process belongs to the same Codex conversation.

Existing fields do not close that gap:

- `WorkIntent.version` is a claim/intent epoch, not a transport takeover
  generation.
- `Binding.bindingVersion` is persisted binding metadata, but the current code
  does not use it as a cross-process transport-generation proof.
- `SessionLeaseRecord` is keyed by exact connection evidence and records
  process liveness; it does not transfer authority when a process disappears.
- App Server `connectionGeneration` is already a useful generation fence, but
  only inside the provider-supervised App Server lifecycle.

A future ordinary-MCP implementation would need one audited current-generation
fence (preferably reusing a proven existing field after source design review),
not a latest-session heuristic and not an unrelated second epoch.

## 6. Synesis-issued resume capability candidate

The candidate is technically expressible on the Synesis side:

```text
lawful binding S/C1
  → issue high-entropy R1
  → retain only H(R1) with exact project/provider/session/lineage scope
C1 disappears
new process C2 presents R1 through a trusted provider channel
  → lock, re-read, validate, consume R1, advance one generation, issue R2
  → retain Binding.sessionId, participant, WorkIntent, and claims
```

The candidate would require all of the following before it could be accepted:

- R1 independent of public Synesis IDs and at least the existing capability
  handle entropy, with only a hash durably retained where possible.
- Exact project, provider, logical binding/session, participant/authority
  lineage, and current-generation scope; transient process identity must not
  be part of the reusable scope.
- One atomic read/validate/consume/rebind/rotate sequence under the existing
  project serialization model, or an equivalently recoverable durable
  transaction. A crash may not leave an accepted R1 without a deterministic
  next-generation state.
- One-winner compare-and-swap semantics for concurrent recovery attempts.
- No recovery while the old connection is still authoritative, terminal, or
  ambiguously live. A deliberate audited handoff would be a separate protocol.
- Delayed C1 requests must fail against the new generation. Terminal session
  proofs remain irreversible.

This is a future design outline, not an accepted implementation. Synesis has
no current record, hash, rotation, or wire field for it.

## 7. How the correct Codex conversation could retain R1

The only currently available path would be model cooperation: return an opaque
value from an earlier MCP result, rely on the same Codex conversation retaining
that result in context, and have the model include it in a later
`ensure_session` call. MCP server restart does not cause the server to replay
tool results or receive the old conversation transcript. The current
`ensure_session` schema has no continuity
field ([schema](../../mcp-contract/src/main/java/org/synesis/mcp/contract/McpToolCatalog.java#L235)).

That path is not sufficient for this acceptance:

1. The model may receive the prior tool result while the conversation context
   remains intact, but retention after compaction, truncation, crash recovery,
   or a provider-side context rewrite is not an MCP guarantee.
2. The model must actively reproduce the bearer in the next request; the
   server cannot distinguish the correct conversation from another one that
   obtains the same value.
3. The bearer is visible to the model and may enter transcripts, logs,
   diagnostics, generated files, or a copied prompt. Guidance cannot make a
   model-visible secret confidential.
4. A leaked or copied value can be replayed by another local process before
   rotation. Single-use scope limits replay duration but does not establish
   conversation identity.
5. Losing R1 would fail closed, which is safe, but would not satisfy reliable
   same-conversation unattended continuation.

Therefore a model-visible R1 is not accepted as the provider-session trust
boundary.

## 8. Non-model-visible alternatives

| Candidate                     | Present in ordinary Codex today? | Assessment                                                                                                                                                                                 |
|-------------------------------|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Static `~/.codex/config.toml` | Yes, but static                  | Project/provider configuration is shared by every matching chat and carries no conversation binding.                                                                                       |
| Launcher environment          | Only for process-level values    | `SYNESIS_MCP_CONNECTION_INSTANCE_ID` is not conversation-scoped; current `env: -` config supplies no per-thread secret.                                                                    |
| Codex `CODEX_THREAD_ID`       | Shell/tool context only          | Local upstream issue [#19937](https://github.com/openai/codex/issues/19937) documents that the thread ID is not injected into local stdio MCP startup; the issue is closed as not planned. |
| Provider hook metadata        | Hook sees it                     | Hook and ordinary MCP are separate processes; no current authenticated bridge forwards it.                                                                                                 |
| Project-local attachment file | No                               | Shared/copyable by same-project processes and unsafe for unrelated-chat isolation.                                                                                                         |
| OS credential store           | No Synesis integration           | A credential lookup without a provider conversation binding would still be host/project/process scoped, not chat scoped.                                                                   |
| Synesis local broker          | No                               | Would be a new provider trust channel and would still need provider-authenticated conversation context.                                                                                    |
| Codex thread state            | Only in App Server path          | `CodexAppServerLifecycleService` and `CodexLifecycleStateStore` use exact binding-scoped thread/turn and generation state, but ordinary stdio MCP is not supervised by that path.          |

No current non-model-visible mechanism provides the required pair:

```text
same Codex conversation automatically receives the credential
different conversation/local process cannot obtain it
```

## 9. Lifecycle and race decisions for any future protocol

- **Dead C1:** potentially eligible only after exact capability validation,
  durable current-state revalidation, and a one-winner generation transition.
- **Cleanly disconnected C1:** current `PARTICIPANT_DETACHED` semantics are
  clean release, not same-session reattachment; the future protocol must not
  reinterpret that state implicitly.
- **Still-live C1:** reject takeover by default. Only an explicit audited
  handoff may supersede it; no silent dual authority.
- **Ambiguous liveness:** fail closed. Missing PID/process evidence never
  transfers authority by itself.
- **Races:** serialize exact candidate/session/generation comparison and token
  consumption; exactly one attempt may win.
- **Late C1:** reject after the new generation is durable, using the same
  generation fence for all authority-bearing operations.
- **Replay:** consumed R1 fails; R2 is the only next credential. A crash in
  rotation must be durably recoverable or fail closed, never silently reuse R1.
- **Terminal:** `isSessionTerminal`/`ProviderSessionTerminalPayload`, binding
  terminal/revoked/completed/cancelled states, and participant revoked,
  cancelled, or completed states must not be revived. `DETACHED` and
  `RECOVERY_HELD` retain their current distinct meanings.
- **Wake:** normal stdio has no Synesis wake attachment. The existing
  App Server host wakes waiters and fences attachments by its own generations;
  it cannot be reused as ordinary-MCP continuity without changing the
  provider boundary.
- **ensure_session:** it is the likely future ingress because it already owns
  binding/admission. A future optional proof must authenticate before creating
  a new binding or WorkIntent; invalid/ambiguous proof must fail closed and
  must not fall back to fresh admission. It would be a schema change, not a
  new MCP tool.

## 10. Threat model

- An unrelated chat in the same repository must not recover A from project,
  provider, participant, session, WorkIntent, workgroup, path, or newest-state
  knowledge. Current exact resolver behavior preserves this.
- A malicious local process that knows public IDs must not recover authority;
  a bearer leaked to that process would violate the desired boundary.
- A replayed credential must fail after consumption/rotation.
- Two restart attempts must have one durable winner.
- Delayed commands from C1 must fail after C2 generation takeover.
- A credential in logs/transcripts is a material leak; model-visible storage is
  therefore rejected as the sole trust boundary.
- A credential copied to another machine/process must not grant authority
  outside the explicitly defined local-first boundary; the current provider
  path has no binding proof to enforce that.
- Copying/cloning a project must not copy a live recoverable credential. A
  future credential record would need ownership/provenance protection, not a
  plain project file.

## 11. Decision under the A/B/C/D architecture choices

**Result A — existing core plus a generic runtime-authentication seam.**

The existing `Binding.sessionId`/participant/WorkIntent model is already a
durable logical-worker layer, and `SessionAuthorityResolver` is already the
strict authorization gate. A provider-neutral authentication result can be
inserted before that gate without making a process, connection, thread, or
provider session ID the logical worker identity. A provider-specific adapter is
an edge concern behind that generic seam; it does not require a larger identity
model refactor.

The implementation is still blocked for ordinary Codex stdio: that transport
cannot currently retain and present a proof that is automatic for the same
conversation and unavailable to an unrelated conversation. The existing App
Server exact-thread resume is a separate supervised architecture; it is useful
evidence for one adapter-shaped path, but does not make Result D true for
ordinary stdio. Implementing a model-visible token, latest-binding fallback,
or project-local secret would create a false success and weaken the stated
trust boundary.

Earlier SYN-050 notes used “Result C” as shorthand for “blocked on a provider
contract.” That shorthand is not the A/B/C/D meaning requested by this design
pass. Under the requested choices, the source-backed architecture decision is
Result A and the operational disposition is implementation-blocked.

The missing provider primitive is one of:

1. an automatically injected, authenticated per-conversation identity or
   confidential continuity assertion in every ordinary stdio MCP process,
   including restarts; or
2. a provider-controlled launcher/callback that proves the new MCP process is
   attached to the same conversation and binds its new transport generation.

The primitive must be automatically carried, scoped to the conversation,
hidden from unrelated chats/processes, replay-safe, concurrency-aware, and
usable without asking the model to copy a secret.

## 12. Conditional future plan (not authorized in this pass)

After the provider primitive exists, reopen ADR-0055 and first revalidate its
trust contract. Then design the smallest change across the actual
`McpProtocolHandler`/`SessionResolutionRequest`/`AgentSessionService`/
`ProviderSessionBindingService` seam, with `ensure_session` as the candidate
boundary. Reuse the existing project append/atomic persistence and authority
fences where proven; add one durable continuity event/record only if required.
Prove hash storage, one-winner rotation, generation fencing, live/ambiguous/
terminal rejection, wake attachment behavior, and no-new-WorkIntent recovery
before implementation. Add focused tests, package/install provenance, then a
fresh restart/race/replay/terminal acceptance and the SYN-049 completion
acceptance. Do not touch historical fixtures or add an MCP tool.

## 13. Planning and validation result

- ADR-0055 was amended, not superseded: it remains the single bounded
  provider-session continuity decision record and now records Result A under
  the requested A/B/C/D architecture choices.
- SYN-050 remains `ACTIVE`, `DESIGN_COMPLETE / IMPLEMENTATION_BLOCKED`.
- No deferred capability is promoted; no broad milestone is created.
- Provider-facing guidance was not changed because no safe continuity workflow
  was accepted. Existing guidance remains conservative and does not promise
  restart recovery.
- Verified with `agent-resume.ps1`, the disposable Graphify query, direct
  source tracing, `codex --version`, and `codex mcp get synesis`.
- No production tests were run or changed; running them would not alter this
  design result.

## 14. Provider-neutral contract and capability classes

The following is a conceptual contract, not an accepted Java API. The core
should receive a verified result with the logical binding target, proof origin,
scope, and current attachment generation. It must not receive an unverified
provider string, thread ID, PID, or public Synesis ID and treat that value as
authentication.

```text
provider/runtime adapter
    → authenticate new or continuation attachment
    → verified runtime-authentication result
    → existing exact binding/authority resolution
    → existing claims, WorkIntent, review, completion, and lifecycle
```

The generic seam needs the semantic operations “authenticate new,”
“authenticate continuation,” “verify current attachment,” and “detach,” but
the names and request/response records remain deliberately undecided. Its
result may identify the durable binding and transport generation; it may not
grant path claims, review authority, completion authority, or task permission.

`RuntimeAdapter` is therefore needed conceptually at the provider boundary when
provider evidence differs. The core remains provider-neutral. A local Runtime
Broker is not required for provider-authenticated mode and is not selected now.
It becomes a possible Result-B extension only when Synesis controls a private,
authenticated launch/attachment channel for a provider that has no trusted
identity. A broker that merely reads a project file or a model-visible token
would not solve the trust problem.

| Provider capability class | Authentication root                                                     | Continuity result                                                                                                                              |
|---------------------------|-------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| Provider-authenticated    | Provider-controlled, verifiable per-conversation/session assertion      | Full restart continuity can be supported after exact scope and generation checks.                                                              |
| Synesis-managed           | Synesis-controlled launcher/broker plus protected attachment credential | Managed continuity can be supported if the provider boundary is actually mediated and the credential is not model-visible.                     |
| Anonymous/session-bound   | None at the runtime boundary                                            | Current transport may coordinate, but restart continuity is explicitly unsupported; use a new admission or the existing audited recovery path. |

Current classifications are conservative: ordinary Codex stdio is anonymous
at this continuity boundary; the Codex App Server route is a separate
provider-supervised, adapter-shaped path with exact stored thread/turn and
attachment generations; Claude Code's hook metadata is not yet an ordinary
MCP authentication channel; and generic stdio MCP has no trust root.

## 15. Restart cases and lifecycle semantics

The provider-neutral state model is conceptual and maps onto current binding,
participant, lease, and App Server states rather than introducing a second
parallel lifecycle:

```text
UNATTACHED → AUTHENTICATING → ATTACHED(generation=N)
                         ↘ rejected
ATTACHED → DISCONNECTED / current lease state
DISCONNECTED → REAUTHENTICATING → ATTACHED(generation=N+1)
                                      ↘ rejected
any terminal binding/session → TERMINAL (never reattached)
```

- If only MCP C1 restarts while a trusted provider runtime remains alive, its
  adapter may authenticate C2 as a new transport attachment. Synesis then
  revalidates the exact binding and advances one current attachment generation;
  C1 becomes stale. Without provider proof, C2 must not rebind.
- If the provider runtime also restarts, the surviving trust root must be a
  persistent provider assertion or a Synesis-managed protected launch/broker
  credential. A PID, command line, worktree, thread ID, or newest binding is
  insufficient.
- If all provider-side state dies and a human later resumes a conversation,
  only the provider or a Synesis-managed attachment channel can prove that it
  is the same logical worker. With neither, seamless continuity is
  unsupported; a new authenticated worker or the existing snapshot-backed
  recovery transfer is the honest result.
- A clean `DETACHED` connection remains a released lane, and
  `RECOVERY_HELD` remains the existing snapshot/grant transfer state. Neither
  is silently reinterpreted as same-session reattachment.

## 16. Authentication, authorization, and attachment fencing

Authentication answers only:

```text
runtime R may speak as logical binding S at current attachment generation G
```

Authorization continues to answer whether S may read or mutate a path, consume
a capability grant, publish, review, complete, or integrate. The existing
`SessionAuthorityResolver` can remain strict: the authenticated result should
feed an exact current binding/generation lookup, while the resolver continues
to reject missing, terminal, stale, or mismatched authority and never selects
the latest provider binding.

The source has no generic attachment generation today. `WorkIntent.version`
is a claim/intent epoch, `Binding.bindingVersion` is not currently a
cross-process takeover proof, `SessionLeaseRecord` is keyed to connection
evidence and liveness, and App Server `attachmentGeneration`/
`connectionGeneration` fences only its supervised lifecycle. A future generic
implementation should reuse one proven binding/event generation if possible;
otherwise it may add one durable current-attachment field or event, but must
not create a parallel claim epoch. The transition must atomically compare the
current generation, authenticate the proof, fence the old attachment, and
record the new generation.

The existing provider binding JSON and signed coordination event store provide
different kinds of durability. A future authenticated reattachment will likely
need an auditable binding event or an extension of an existing binding event
that records proof scope, generation transition, and supersession. Exact event
names are not selected. The current `PROVIDER_SESSION_TERMINALIZED` event,
lease states, and App Server journals remain intact.

## 17. Proof inventory and trusted roots

| Proof candidate                                      | Issuer/verifier                                            | Confidentiality and replay assessment                                                                                                                  | Decision                                                               |
|------------------------------------------------------|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| Provider-signed or provider-authenticated assertion  | Provider adapter verifies provider root                    | Can be non-model-visible, scoped, and renewed by provider; must carry conversation binding and expiry/rotation semantics                               | Preferred provider-authenticated input when actually exposed.          |
| Synesis-issued rotating capability                   | Synesis issues; core stores a hash and consumes/rotates it | Good server-side replay properties only if presented through a trusted channel; a model-visible bearer is copyable and not conversation-authenticating | Valid protocol shape, unavailable carrier in ordinary Codex stdio.     |
| Launcher-injected secret or signed attachment ticket | Synesis launcher/broker verifies protected process handoff | Requires private process channel, OS protection, scope, one-winner consume, and crash-safe rotation; a plain file/env value is shared                  | Candidate only for a genuinely Synesis-managed mode.                   |
| NodeIdentity/Ed25519 signature                       | Existing Synesis node key verifies signed Link/events      | Authenticates the Synesis node/event signer, not the provider conversation; must not be repurposed without an explicit binding contract                | Reusable event-authentication primitive, not current continuity proof. |
| Thread/session/connection/PID/worktree/public ID     | Caller or runtime supplies it                              | Stable or observable values have no trusted issuer, are replayable/selectable, and do not separate same-human chats                                    | Diagnostic/selector data only; rejected as proof.                      |

Any accepted credential must be scoped to project, provider adapter, logical
binding/session, authority lineage, and current generation; expire or rotate;
be unusable after consumption; avoid transcript/log exposure; and support
multiple independent workers without shared authority. If the proof cannot be
kept confidential from the model and unrelated processes, the provider profile
must remain session-bound.

## 18. Threat model conclusions

- Same-human Chat A, B, and C remain separate unless an authenticated protocol
  deliberately relates them. Same repository/provider/worktree knowledge is
  not enough.
- An unrelated local process cannot inherit a worker by learning project,
  participant, WorkIntent, workgroup, path, or newest-state identifiers.
- A stale C1 cannot act after C2 wins. A delayed C1 request must fail against
  the new generation.
- Two continuation attempts are serialized with one durable winner; losing and
  replayed proofs fail closed.
- Provider adapters must verify provider-controlled evidence, not accept
  caller-supplied identity strings as trusted.
- Model-visible continuity material is a leakable bearer and is not an
  acceptable sole trust boundary.
- Cloning a project must not clone a live attachment credential. A future
  credential belongs in protected administration state with provenance, not a
  normal project file.
- Machine restart is not automatically in scope: it requires a provider or
  managed root that survives the restart and an explicit local credential
  protection/recovery policy. Otherwise the result is session-bound.

## 19. Responsibility boundaries

**Core:** durable logical binding/session identity; exact attachment lookup;
generation fencing and one-winner takeover; terminal rejection; existing
claims, authorization, coordination, review, completion, and signed audit
state.

**Provider adapter:** obtain and verify provider-specific assertions; translate
provider lifecycle/restart signals into the generic authentication result;
preserve provider-specific confidentiality and expiry rules; never grant
Synesis claims directly.

**Optional managed broker:** protect and rotate Synesis-managed attachment
credentials; mediate an explicitly controlled launch/reattach boundary; record
auditable handoff evidence; never plan work, assign tasks, orchestrate fleets,
or replace normal provider UX. No broker is authorized by this pass.

## 20. Architecture choice and conditional roadmap

Result A is the selected architecture because the source already separates a
durable logical binding from disposable transport evidence and already has the
strict authorization seam needed after authentication. Result B is an optional
provider-specific deployment profile, not a reason to put broker logic in the
core now. Result C (larger identity refactor) is not justified by the source;
Result D is false for ordinary stdio because App Server continuity is isolated
to its supervised path.

The smallest future implementation order is:

1. obtain a provider contract and classify each provider profile;
2. reopen this ADR and define the generic authentication result at the existing
   `ensure_session`/session-resolution ingress;
3. reuse or add one durable attachment-generation/audit seam, with atomic
   proof validation, rotation, and old-generation fencing;
4. implement one provider adapter and its private carrier; add a managed broker
   only if that provider boundary is truly controlled;
5. add focused authorization-preservation, replay/race, restart, terminal, and
   provider-profile tests;
6. rebuild, hash, install, and prove artifact provenance before runtime
   acceptance;
7. run the fresh restart and multi-provider acceptance, then the preserved
   SYN-049 lifecycle acceptance if its evidence remains compatible.

No stage authorizes a new MCP tool, latest-binding fallback, worker-facing
manual identifier, durable-state rewrite, or production change before the
provider contract is verified.

## 21. Future acceptance model

The first compliant acceptance must use disposable state and record the
provider contract, source/build/install provenance, and durable revisions. It
must prove:

```text
same worker, MCP bridge restart
  → authenticated C2 attaches
  → same binding/participant/WorkIntent/claims
  → C1 fenced

same human, different chat
  → cannot attach to A

provider runtime restart
  → only a provider or managed trust root may continue A

two takeover races
  → exactly one generation wins

old/replayed proof or terminal worker
  → rejected

Codex provider-authenticated or managed profile
Claude provider-authenticated or managed profile
generic anonymous MCP
  → explicit refusal of seamless restart continuity
```

After attachment proof, the lane must pass through the unchanged exact
authority, claims, completion, review, integration, and terminal fences. No
worktree copy, manual ID, metadata edit, forced transition, or protocol bypass
is valid evidence.
