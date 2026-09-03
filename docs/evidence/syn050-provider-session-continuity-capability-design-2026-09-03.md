# SYN-050 provider-session continuity capability design — 2026-09-03

Status: DESIGN COMPLETE / RESULT C / IMPLEMENTATION BLOCKED. This is a
source-backed design decision only. No production code, `.synesis` state,
provider state, historical fixture, or worker lane was changed.

## 1. Starting state and provenance

- Starting source HEAD: `01e0e71056f025c3a8f5a9716fdb9f03596fb8be`.
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

Relevant source symbols are [`ProjectLocation`](../../workspace/src/main/java/org/synesis/workspace/application/ProjectApplicationService.java#L615),
[`Binding`](../../workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java#L1343),
[`WorkIntent`](../../coordination/src/main/java/org/synesis/coordination/domain/collaboration/WorkIntent.java#L17),
[`Participant`](../../coordination/src/main/java/org/synesis/coordination/domain/collaboration/Participant.java#L17),
and [`participantHandle`](../../workspace/src/main/java/org/synesis/workspace/application/collaboration/WorkspaceCollaborationService.java#L62).

## 3. Durability and authority classification

| Value | Classification | Finding |
| --- | --- | --- |
| `ProjectLocation.projectId` | durable logical identity | Project metadata identity. |
| `Binding.sessionId` | durable logical identity | Current durable provider-session/binding identity. |
| Participant handle and `WorkIntent` IDs | durable logical identity | Signed/event-projected coordination identities; not secrets. |
| `workGroupId`, `authorityLineageId`, intent version, claims | durable authority state | Existing coordination and claim fencing. |
| Node identity/signatures | authority credential | Existing local event-authentication mechanism; not a Codex conversation proof. |
| `connectionInstanceId` | ephemeral transport identity | Launcher value or random per-MCP-process value; also persisted only as binding fingerprint/lease evidence. |
| MCP process, PID, start time, command line, nonce | ephemeral/diagnostic | Process evidence used for liveness and close fencing, not conversation identity. |
| `SessionLeaseRecord` | durable diagnostic/liveness record | Exact connection key, session reference, and process evidence; it does not authenticate a replacement process. |
| App Server `attachmentGeneration`/`connectionGeneration` | durable generation state in separate path | Fences the supervised App Server attachment, not ordinary stdio MCP. |
| App Server `threadId`/`turnId` | authority-scoped provider correlation in separate path | Exact-thread resume input for the existing App Server lifecycle; not supplied to normal stdio MCP. |
| hook `session_id`/`conversation_id` | diagnostic provider correlation | Seen by a separate hook process and not forwarded to ordinary MCP. |
| no resume token/capability | absent | No existing continuity credential or generic same-session rebind record exists. |

## 4. Exact transport/authority coupling

`SynesisMcpServer` reads `SYNESIS_MCP_CONNECTION_INSTANCE_ID` or generates a
random value ([source](../../mcp/src/main/java/org/synesis/mcp/SynesisMcpServer.java#L36)).
`McpProtocolHandler` retains that value for the lifetime of the process and
passes it into `AgentSessionService.SessionResolutionRequest` and subsequent
operation requests. `AgentSessionService` calls
`ProviderSessionBindingService.ensure` with it. The normal binding path stores
the SHA-256 fingerprint under a connection-derived session file and
`SessionAuthorityResolver` accepts only an exact fingerprint/raw-session match
with `BOUND` status ([resolver](../../workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java#L34)).

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
`ensure_session` schema has no continuity field ([schema](../../mcp-contract/src/main/java/org/synesis/mcp/contract/McpToolCatalog.java#L235)).

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

| Candidate | Present in ordinary Codex today? | Assessment |
| --- | --- | --- |
| Static `~/.codex/config.toml` | Yes, but static | Project/provider configuration is shared by every matching chat and carries no conversation binding. |
| Launcher environment | Only for process-level values | `SYNESIS_MCP_CONNECTION_INSTANCE_ID` is not conversation-scoped; current `env: -` config supplies no per-thread secret. |
| Codex `CODEX_THREAD_ID` | Shell/tool context only | Local upstream issue [#19937](https://github.com/openai/codex/issues/19937) documents that the thread ID is not injected into local stdio MCP startup; the issue is closed as not planned. |
| Provider hook metadata | Hook sees it | Hook and ordinary MCP are separate processes; no current authenticated bridge forwards it. |
| Project-local attachment file | No | Shared/copyable by same-project processes and unsafe for unrelated-chat isolation. |
| OS credential store | No Synesis integration | A credential lookup without a provider conversation binding would still be host/project/process scoped, not chat scoped. |
| Synesis local broker | No | Would be a new provider trust channel and would still need provider-authenticated conversation context. |
| Codex thread state | Only in App Server path | `CodexAppServerLifecycleService` and `CodexLifecycleStateStore` use exact binding-scoped thread/turn and generation state, but ordinary stdio MCP is not supervised by that path. |

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

## 11. Decision

**Result C — still blocked on a provider contract.**

Synesis can specify a hash-backed, single-use, generation-fenced continuity
protocol, but ordinary Codex cannot currently retain and present its proof in a
way that is both automatic for the same conversation and unavailable to an
unrelated conversation. The existing App Server exact-thread resume is a
separate supervised architecture (Result D does not solve the ordinary stdio
acceptance). Implementing a model-visible token, latest-binding fallback, or
project-local secret would create a false success and weaken the stated trust
boundary.

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

- ADR-0055 should be amended, not superseded: it remains the single bounded
  provider-session continuity decision record and now records Result C.
- SYN-050 remains `ACTIVE`, `DESIGN_COMPLETE / IMPLEMENTATION_BLOCKED`.
- No deferred capability is promoted; no broad milestone is created.
- Provider-facing guidance was not changed because no safe continuity workflow
  was accepted. Existing guidance remains conservative and does not promise
  restart recovery.
- Verified with `agent-resume.ps1`, the disposable Graphify query, direct
  source tracing, `codex --version`, and `codex mcp get synesis`.
- No production tests were run or changed; running them would not alter this
  design result.
