# SYN-050: Codex managed-attachment continuity design

Date: 2026-09-03
Starting source HEAD: `ae565f22c0e983472868ed1ff1ab11a934a00495`
Branch: `master`
Working tree at start: clean
Disposition: design-only; no production implementation authorized

## Decision summary

This pass formally approves `MANAGED_CONTINUITY` as a Synesis provider
capability profile. The first target is the Synesis-supervised Codex App
Server path. Ordinary Codex and Claude MCP remain `SESSION_BOUND`. A future
provider-native assertion path is `NATIVE_CONTINUITY`.

The generic architecture remains **Result A**: a provider-neutral
`RuntimeAuthenticator` result is consumed before the existing exact
authorization path, while provider-specific verification stays at the
adapter boundary. The managed Codex design has **Result B** status for
implementation: one narrow provider-runtime experiment must prove that a
protected, non-model-visible attachment proof can reach the MCP bridge for
the exact managed App Server thread. No production code is changed until
that experiment passes.

This is not a new identity graph. The durable Synesis binding remains the
logical worker/session identity. `connectionInstanceId` remains an ephemeral
transport selector. The attachment proof only authenticates a runtime to an
existing binding; it grants no claim, review, completion, publication,
integration, or task authority.

No separate implementation task is created in this design-only pass. After
the prototype gate passes, the next bounded implementation task may be
created or SYN-050 may be explicitly promoted according to the repository's
single-active-task convention. SYN-049 remains `PARTIAL` and is not reopened.

## Evidence classification

### Source evidence

The current implementation traces are:

* `ProjectRuntimeHost` owns the project runtime lock, one `BindingRuntime` per
  binding, startup reconciliation, authority verification, and the local
  lifecycle route.
* `CodexAppServerLifecycleService` launches one `codex app-server` process for
  an exact provider binding, sends `initialize`, starts an exact
  `thread/start`, records the returned `threadId`, and starts a turn only
  after the exact thread identity is observed.
* The same service resumes only an exact stored thread through
  `thread/resume` followed by `thread/read`; a mismatch fails and records an
  ambiguous state.
* `CodexLifecycleStateStore.Checkpoint` already contains `threadId`,
  `attachmentGeneration`, `connectionGeneration`, process evidence, and a
  monotonic lifecycle `revision`. It explicitly contains no credential
  material.
* `ProjectRuntimeHost.verifyAuthority` rechecks the exact project, provider,
  binding, participant, WorkIntent, claim/worktree, event, and provider
  attestation predicates before a lifecycle operation.
* `SessionAuthorityResolver` resolves an exact bound connection and never
  selects the latest binding. `ProviderSessionBindingService.bindingVersion`
  is an authority snapshot/version, not a replacement attachment generation.

Relevant source locations include:

* [
  `CodexAppServerLifecycleService`](../../workspace/src/main/java/org/synesis/workspace/lifecycle/codex/CodexAppServerLifecycleService.java)
* [
  `ProjectRuntimeHost`](../../workspace/src/main/java/org/synesis/workspace/lifecycle/codex/ProjectRuntimeHost.java)
* [
  `CodexLifecycleStateStore`](../../workspace/src/main/java/org/synesis/workspace/lifecycle/codex/CodexLifecycleStateStore.java)
* [
  `SessionAuthorityResolver`](../../workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java)
* [
  `ProviderSessionBindingService`](../../workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java)

### Local runtime evidence

The previous feasibility record observed local Codex `0.145.0` ordinary MCP
as a static stdio configuration. The configured MCP launcher receives a
project and provider, but no dynamic conversation/thread input. The Codex
hook receives session metadata in its own hook process; the ordinary MCP
process does not receive that metadata. The local Claude installation has the
same relevant limitation at the ordinary MCP boundary. No provider
configuration or runtime state was changed for this design.

### Provider documentation

The Codex App Server protocol documents exact `thread/start` and
`thread/resume` operations, while the ordinary MCP interface is a separate
configured MCP process boundary. The local provider feasibility record links
the upstream protocol material and the observed configuration. Provider
documentation establishes protocol behavior; it does not by itself establish
Synesis authority.

### Architectural inference

The protected-channel protocol, its generation transaction, and the managed
UX below are design decisions derived from the source and threat model. They
are not claims that the current Codex App Server already propagates a private
attachment handle to its MCP child. That propagation is the explicit Result B
prototype gate.

## Capability model

| Profile              | Trust input                                                                         | Supported continuity                                                                                     |
|----------------------|-------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `NATIVE_CONTINUITY`  | A provider-controlled, verifiable, non-model-visible conversation/session assertion | Synesis verifies the assertion through a provider adapter and then uses existing authorization.          |
| `MANAGED_CONTINUITY` | A protected Synesis-managed attachment proof bound to a provider runtime/thread     | Synesis may replace a disposable runtime after exact revalidation and generation fencing.                |
| `SESSION_BOUND`      | The current authenticated transport/session only                                    | Coordination works for the attachment lifetime; unattended restart continuity is explicitly unsupported. |

The provider ID remains `codex`; managed continuity is a capability/profile,
not a second Codex identity namespace. Codex App Server is the first managed
adapter. A future Claude adapter must implement the same generic result and
must not move Claude session formats into core.

## Actual current App Server lifecycle

The source-backed lifecycle is:

```text
normal admission/binding/claims
        ↓
coordination serve → ProjectRuntimeHost → BindingRuntime
        ↓ signed, exact authority request
CodexAppServerLifecycleService
        ↓
launch `codex app-server` with the assigned real worktree
        ↓
initialize
        ↓
thread/start(cwd)
        ↓ exact provider threadId + thread/started
Checkpoint(IDLE, threadId, attachmentGeneration, connectionGeneration)
        ↓
turn/start(threadId)
        ↓ exact turnId
Checkpoint(RUNNING, threadId, turnId, process evidence)
```

For an App Server process replacement, the current source performs:

```text
old process exits or is unavailable
        ↓
increment attachmentGeneration and connectionGeneration
        ↓
launch a new App Server
        ↓
initialize
        ↓
thread/resume(exact stored threadId)
        ↓
require exact thread/resumed identity when present
        ↓
thread/read(exact stored threadId)
        ↓
install the new attachment and persist reconciled state
```

The current process monitor retains or transitions lifecycle state only when
the observed attachment generation still matches the checkpoint. Startup
reconciliation never adopts an orphan solely because a PID is present; an
untrusted live process is terminated only under the existing verified process
identity policy or the state becomes `AMBIGUOUS`.

The current source therefore proves a supervised exact-thread lifecycle. It
does not yet prove a private assertion delivered from that thread to the MCP
bridge.

## Managed trust root

The managed trust root is a combination, not a single identifier:

1. Synesis already owns the admitted logical binding, participant, WorkIntent,
   claims, worktree, and authority lineage.
2. Synesis launches and supervises the App Server process for that exact
   binding through `ProjectRuntimeHost`.
3. The provider returns an exact thread identity for `thread/start`, and the
   provider's `thread/resume`/`thread/read` path must return the exact stored
   thread on replacement.
4. A private OS channel carries a one-time attachment proof to the managed
   bridge. The proof is verified before the existing strict authorization
   checks are used.

Synesis can trust `W ↔ T` only after all four parts are present. The provider
thread ID is provider-controlled correlation and exact protocol evidence, but
it is not a secret and is not authority-bearing on its own. A different
ordinary Codex chat cannot claim `T` merely by knowing its string. A process
ID, worktree, repository, provider name, or same human also cannot select the
binding.

The initial managed implementation must preserve the current source invariant
of one App Server process and one exact thread per provider binding. If a
future Codex App Server mode multiplexes multiple logical worker threads in
one process, the managed adapter must require a provider-supplied
thread-scoped assertion or reject that mode. Process identity or process-wide
environment is never sufficient for multiplexed threads.

## Protected attachment channel

### Candidates considered

| Candidate                                                 | Boundary                                                                                  | Decision                                                                               |
|-----------------------------------------------------------|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| Static MCP environment/configuration                      | Shared by every process/thread using the entry; inspectable and not conversation-scoped   | Reject. It cannot distinguish Worker A from Worker B.                                  |
| Model-visible bearer returned by a tool                   | The model can copy, lose, disclose, or replay it; no conversation proof                   | Reject. Guidance cannot make a bearer confidential.                                    |
| Project file or ordinary local secret                     | Shared with other chats and tooling; survives longer than its authority scope             | Reject. It becomes a project-wide credential.                                          |
| PID, parent lineage, command line, or start time          | Liveness/diagnostic evidence only; values can be reused or become ambiguous               | Reject as an authentication root.                                                      |
| OS credential store lookup alone                          | Identifies a user/machine, not an exact provider thread or attachment generation          | Reject alone. It may protect a launcher secret but is not the binding proof.           |
| App Server WebSocket authentication                       | Protects App Server transport, not the relationship between one thread and one MCP bridge | Reuse only as transport protection; not the continuity root.                           |
| Per-attachment OS-local IPC with one-time challenge proof | Can be scoped to a managed launch, kept out of model context, and rotated per generation  | Select for the Codex prototype, with inherited-handle delivery as the primary variant. |

### Selected channel

The selected design is a Synesis-owned, per-attachment OS-local protected
channel:

* Windows: a private named-pipe instance or inherited kernel handle with a
  restrictive user/launcher ACL.
* Unix-like hosts: a private Unix-domain socket in a mode-0700 runtime
  directory, with the same one-time proof and no project-file credential.
* The preferred delivery mechanism is an inherited OS handle from the
  Synesis-managed launch boundary into the App Server/managed MCP bridge.
  The pipe name or opaque descriptor is not itself authority; the handshake
  still proves possession of the one-time secret and the exact expected
  scope.
* A named pipe/socket discovered through a global static configuration is not
  an acceptable fallback. If Codex cannot propagate the protected handle or
  an equivalent provider-controlled private carrier to the MCP bridge, the
  managed path remains blocked rather than degrading to an environment token.

This is the smallest channel that preserves a real OS trust boundary without
putting a Codex protocol type in Synesis core. It is also the unresolved
provider boundary in Result B.

### Credential lifecycle

For each managed attachment generation, the Synesis host creates a fresh
256-bit random proof `P_N` and a non-secret opaque `attachmentId`. It stores
only `H(P_N)` and scope metadata in the adapter-private lifecycle record. The
raw proof exists only in the trusted host process and the protected launch/
handshake channel. The handshake uses a domain-separated challenge response,
conceptually:

```text
HMAC(P_N,
     "synesis-managed-codex-attachment/v1" ||
     project || provider || binding scope || exact thread ||
     attachment generation || host nonce || bridge nonce)
```

The presented selector is a lookup hint only. The host binds the endpoint to a
pending attachment and verifies the proof hash, exact expected thread, scope,
generation, and freshness. A successful handoff marks `P_N` consumed, advances
the generation, and creates `P_(N+1)`. No raw proof is written to an ordinary
log, project file, prompt, AGENTS.md, provider guidance, exception, or audit
record.

The proof is not persisted in plaintext for restart continuity. If the host
restarts, it creates a new proof after it has safely established a new managed
App Server attachment. A durable old hash never authorizes a new process by
itself.

### Durable representation

Reuse the existing fields wherever they have the correct meaning:

* `Checkpoint.attachmentGeneration` is the monotonic runtime attachment
  generation.
* `Checkpoint.connectionGeneration` remains the protocol-transport
  generation.
* `Checkpoint.revision` remains the lifecycle CAS/order fence.
* `Checkpoint.threadId` remains exact provider correlation.
* `Binding.bindingVersion` remains the existing authority snapshot/version;
  it is not repurposed as the attachment generation.

The managed adapter needs one versioned `managedAttachment` object in the
existing Codex lifecycle state, or an equivalent adapter-private record keyed
by `(bindingSessionId, attachmentGeneration)`. The object contains only:

```text
formatVersion
attachmentId                 # opaque, non-secret
providerThreadId             # exact provider value, adapter-private
attachmentGeneration         # same value as the checkpoint generation
proofHash                    # H(P_N), never P_N
scopeDigest                  # project/provider/binding/lineage/thread scope
proofState                   # CURRENT, CONSUMED, REVOKED
```

No new Synesis logical-worker ID or second core epoch is required. A versioned
managed record is rejected if its required fields, scope, generation, or
format do not match. The current unversioned/ordinary checkpoint is never
silently interpreted as a managed attachment. Atomic replacement uses the
existing per-binding attachment lock plus the monotonic checkpoint revision;
any crash that leaves the managed record and checkpoint disagreeing fails
closed and requires a fresh managed attachment.

## Attachment flows

### First attachment

```text
existing admitted Synesis binding W
        ↓
ProjectRuntimeHost verifies normal exact authority
        ↓
host creates pending managed attachment, P1, and private OS channel
        ↓
Synesis-managed launcher starts App Server and managed MCP bridge
        ↓
App Server returns exact thread T from thread/start
        ↓
bridge proves possession of P1 over the private channel and supplies the
provider-controlled evidence required by the adapter
        ↓
host verifies W/project/provider/lineage/T/generation and writes one record
        ↓
W ↔ T ↔ attachmentGeneration 1 becomes authoritative
```

No new participant, WorkIntent, claim, or manually supplied Synesis ID is
created by attachment authentication.

### MCP bridge restart

If App Server thread `T` remains valid while bridge `C1` dies, managed
launcher/bridge `C2` obtains a new per-attachment proof through the protected
channel. The host accepts it only after it can establish that `C1` is closed
or an explicit provider handoff has occurred, then atomically advances
generation `N → N+1`. `C1` becomes stale; the logical binding, participant,
WorkIntent, and claims remain unchanged. A normal untrusted stdio process
cannot enter this flow.

### App Server process restart

For `A1 → A2`, Synesis initiates `A2`, calls `thread/resume` with the exact
stored `T`, requires the exact returned/resumed identity, and performs
`thread/read(T)` before installing the new attachment. The new bridge must
complete the protected handshake for the new generation. The old process and
connection generations are fenced. A thread mismatch, missing provider
evidence, failed handshake, or unresolved old liveness produces `AMBIGUOUS` or
another existing fail-closed state; it does not select a latest thread.

### Full local runtime restart

The current host does not adopt an orphan solely by PID. The managed
implementation may continue only when it can:

1. establish that the old managed process/bridge is absent or has completed a
   provider-audited handoff;
2. load the exact logical binding and stored thread correlation;
3. launch a new managed App Server;
4. resume the exact `T` and verify `thread/read(T)`;
5. establish a fresh private channel and proof; and
6. atomically advance the attachment generation under the existing authority
   and lifecycle fences.

If a live old process, missing proof, provider ambiguity, or unavailable
thread history prevents those checks, the state remains `AMBIGUOUS` and needs
an explicit safe operator/provider handoff. The durable logical binding is not
revived by a same-repository or same-PID guess.

## Isolation and fencing

### Multiple workers

Each managed binding has a distinct private channel, proof hash, opaque
attachment record, exact thread, and generation. For Workers A, B, and C in
one project, a proof for A fails scope/hash/thread checks for B. The current
one-process/one-thread-per-binding invariant keeps the first implementation
unambiguous. A multiplexed App Server is unsupported until the provider
supplies a thread-scoped assertion; a process-wide environment is never used
as the root.

### Atomic takeover

Under the existing project/attachment serialization, the replacement
transaction is:

```text
read checkpoint and managed record
verify logical binding is active and current generation == N
verify private-channel proof, scope, exact thread, and freshness
verify current provider/participant/WorkIntent/claim/authority predicates
verify old attachment is closed or provider handoff is explicit
consume P_N
write generation N+1, new attachment record, and lifecycle revision atomically
record non-secret audit evidence
fence N and install N+1
```

Two concurrent replacements serialize on the existing binding lock. One
observes `N` and wins; the other observes the advanced generation or consumed
proof and fails. If the atomic durable write cannot be proven, both future
attachments fail closed until safe reconciliation.

### Live, ambiguous, late, and terminal cases

* A live old runtime is rejected by default. Dual authority is never silently
  created. Only an explicit provider handoff with a closed/serialized old
  channel may supersede it.
* Ambiguous liveness is not abandonment. Authentication proof and process
  liveness remain separate; proof alone does not justify takeover when the old
  runtime may still act.
* Late requests carry the managed generation through the adapter result and
  continue through existing `connectionGeneration`, lifecycle `revision`,
  binding version, claim epoch, event revision, WorkGroup version, and
  execution-time fences. Old generations are rejected before authority-bearing
  work.
* Authentication never bypasses terminal binding, participant, WorkIntent,
  claim, review, completion, integration, or WorkGroup checks. Terminal state
  remains irreversible; completed/cancelled/revoked/terminal sessions cannot
  be revived.

### Replay

`P_N` is scoped, single-use, and rotated. Replaying a consumed proof, a proof
from another binding/thread/project, or a proof from an earlier generation
fails before authorization. The provider's exact thread response does not
remove the need for this proof because a public thread ID is not a secret.

### Audit

The first implementation should reuse the existing Codex evidence journal
and provider-binding/runtime audit path rather than introduce a new
coordination graph. The minimum durable semantic records are equivalent to:

```text
MANAGED_ATTACHMENT_AUTHENTICATED
MANAGED_ATTACHMENT_REPLACED
MANAGED_ATTACHMENT_REJECTED
MANAGED_ATTACHMENT_DETACHED
```

Each record contains provider, logical binding scope, thread correlation,
old/new generation, authentication method/issuer, result, reason, and the
associated non-secret lifecycle revision. It contains no raw proof, HMAC,
private handle, model transcript, or credential-bearing environment value.

## Generic core and Codex adapter

### RuntimeAuthenticator seam

The minimum generic contract is conceptually:

```text
RuntimeAuthenticator.authenticate(RuntimeAuthenticationRequest)
    → RuntimeAuthenticationResult

RuntimeAuthenticationResult:
    logicalBindingReference
    provider
    authenticationMethod
    attachmentGeneration
    providerRuntimeReference   # opaque provider value, not a Codex type
    freshness
```

The request carries an opaque provider proof/evidence envelope and transport
context. The result identifies an already durable logical binding and the
current managed attachment generation. It does not grant a claim or mutate
coordination. Core then runs the existing exact `SessionAuthorityResolver`
and all existing participant/WorkIntent/claim/lease/review/completion/
terminal predicates.

The generic contract belongs in the existing
`workspace` application/provider boundary, next to the provider-session
authority services. No new Gradle module is justified. The Codex adapter
belongs beside the existing App Server lifecycle code under
`workspace.lifecycle.codex`. `ProjectRuntimeHost`/the existing session
ingress invokes the generic seam before the existing authorization path; core
does not parse Codex JSON-RPC, thread events, or Claude session formats.

### Codex adapter responsibilities

The adapter may:

* launch/resume the exact Codex App Server thread;
* create and close the OS-local protected channel;
* verify provider-thread evidence and the one-time handshake;
* manage proof rotation and adapter-private records;
* translate Codex runtime state into the generic authentication result; and
* emit non-secret lifecycle evidence.

It may not own Synesis claims, WorkIntents, review, publication, integration,
task planning, or arbitrary worker allocation.

### Core responsibilities

Core remains responsible for exact logical binding lookup, current authority,
participant and WorkIntent association, claim/lease fencing, generation/CAS
serialization, stale and terminal rejection, and all existing coordination
actions. It never accepts a latest binding, same repository, same worktree,
same provider, same PID, or same human as an authentication fallback.

## UX and product boundary

The minimum honest UX change is an explicit managed entrypoint. Existing
`synesis provider install codex --project <path>` remains the integration
prerequisite and ordinary `codex` plus static MCP remains `SESSION_BOUND`.
The prototype should add/evaluate a clearly named command such as:

```text
synesis provider run codex --managed --project <path>
```

This command is a proposed future surface, not a current command. It would
start or connect the local coordination host and launch the Synesis-managed
App Server boundary. The user continues using Codex for coding interaction,
but opts into a managed launch path. Synesis must not silently reinterpret an
ordinary static MCP entry as managed, and it must not promise transparent
continuity if the provider requires this explicit launcher.

Managed mode remains a coordination substrate: it may manage runtime
attachment, provider lifecycle, protected authentication, restart/resume, and
fencing. It does not choose prompts, reason, plan implementation, allocate
arbitrary workers, control coding decisions, replace the Codex UI, or become a
central planner.

## Ordinary and future provider profiles

Ordinary Codex and Claude MCP continue to work as `SESSION_BOUND`; their
limitation is explicit rather than hidden: a new ordinary MCP process cannot
seamlessly resume the old logical worker without a provider-native assertion
or managed boundary. No existing provider integration is degraded.

A future Claude managed adapter can use the same sequence:

```text
Claude-managed launch proof
    → RuntimeAuthenticator
    → existing logical binding and generation fences
```

The generic result exposes no Claude or Codex session type. Claude-specific
hook/session evidence remains inside the adapter. If Claude cannot provide a
protected carrier, it remains `SESSION_BOUND`.

## Threat conclusions

The design protects against accidental cross-chat selection, stale/replayed
replacement proofs, public thread-ID guessing, static project-secret reuse,
ordinary process confusion, and dual authority when the OS channel and
provider handoff contract are real. It does not claim protection against a
compromised local OS account that can inspect trusted process memory or defeat
the platform's process/IPC security; that is the local host trust boundary.

The design deliberately does not solve ordinary MCP continuity with a model
token, hook metadata, PID, worktree, repository, or latest-session lookup.
If the Codex runtime cannot privately deliver the managed proof to the exact
bridge/thread, the safe result is to keep the profile blocked or
`SESSION_BOUND`, not to weaken Synesis authorization.

## Result B prototype gate

Before production implementation, run one disposable, mutation-free Codex App
Server experiment against the installed local Codex version:

1. Start an instrumented managed MCP bridge through the same App Server launch
   path, with a per-attachment private inherited-handle/IPC bootstrap.
2. Start one exact App Server thread and record only redacted process/channel
   evidence: no proof bytes, environment secrets, or transcript material.
3. Determine whether the bridge receives the private carrier without static
   global configuration and whether it can return an authenticated,
   thread-scoped proof to the Synesis host.
4. Start a second exact thread/process and prove its bridge cannot use the
   first attachment. If one App Server process can multiplex threads, prove
   that the carrier is thread-scoped; otherwise record the one-process/
   one-thread managed invariant and reject multiplexing.
5. Kill and replace only the bridge, then restart the App Server and exercise
   exact `thread/resume`/`thread/read`; verify new-generation proof delivery
   and old-generation rejection.
6. Repeat after a host restart only far enough to classify whether a new
   managed launch can safely resume the exact thread. Do not mutate a Synesis
   fixture or make a production state change.

Pass requires a provider-controlled/non-model-visible carrier, exact
thread/process scoping, no static-secret fallback, and evidence sufficient to
implement the handshake. Failure means the managed profile remains a design
candidate and the ordinary path stays `SESSION_BOUND`; no production
dependency or authority workaround is allowed.

## Focused implementation/test plan after the gate

Only after the prototype passes, create the bounded implementation slice:

1. Add the generic `RuntimeAuthenticator` request/result at the existing
   workspace provider boundary.
2. Add the Codex managed adapter beside App Server lifecycle code and the
   protected-channel launcher seam.
3. Add the versioned adapter-private managed record, reusing existing
   attachment/connection generations and lifecycle revision.
4. Add one serialized consume-and-rotate transaction with crash-recovery
   fail-closed behavior.
5. Invoke authentication before existing exact session/authority resolution;
   do not change claims, review, completion, integration, or terminal rules.
6. Add restart, race, wrong-thread, replay, live-old, ambiguous, terminal,
   secret-hygiene, multi-thread, and ordinary-`SESSION_BOUND` regressions.
7. Rebuild, hash, install, and prove artifact provenance before any real
   fixture run.

The future real acceptance uses two independently admitted Codex managed
threads on the same machine, same user, and same repository. It proves bridge
restart, App Server restart, full host restart, one-winner takeover,
old-generation fencing, wrong-thread rejection, replay rejection, terminal
rejection, no credential leakage, and then the preserved SYN-049 path:
Worker B resumes, consumes Worker A's published capability, completes through
`completionRequested=true`, review, integration, and terminal WorkGroup
closure. It must use naturally created Synesis identities, no worktree copy,
no metadata edits, no manual state, and exactly ten MCP tools.

## Acceptance disposition

* Product capability decision: **approved** — `MANAGED_CONTINUITY` is a
  supported Synesis profile and Codex App Server is its first target.
* Generic architecture decision: **Result A** — existing core plus a generic
  runtime-authentication seam.
* Implementation decision for this pass: **Result B / PROTOTYPE_PARTIAL** —
  the isolated carrier passed, but the real provider attachment-delivery
  boundary did not.
* Production implementation: **not unblocked**.
* Review/Doctor behavior: not redesigned and not opened as a defect by this
  design pass.
* Historical fixtures, `.synesis` state, provider configuration, and the
  Synesis source repository were not changed.

## Unknowns kept explicit

1. The tested installed Codex App Server did not expose an inherited-handle or
   equivalent per-worker protected-carrier input to the configured MCP child;
   a documented provider-managed launch/IPC contract is still required.
2. Whether a future provider carrier can be scoped to a thread or only to an
   App Server process.
3. Whether the provider offers a safe explicit handoff when an old managed
   process remains live during host restart.
4. Whether the exact `thread/resume` response plus a newly established private
   channel is sufficient after a full local runtime restart on the installed
   provider version.
5. The smallest user-facing managed-launch command; the current CLI has no
   managed provider-run command.

These are provider-boundary questions, not permission to infer continuity or
to alter the Synesis authority core.

## Prototype follow-up — 2026-09-03

The disposable protected-carrier gate was executed after this design record
was approved. The isolated Windows anonymous inherited-pipe model passed 48
assertions for per-worker proof isolation, rotation, replay, race, liveness,
terminal, and secret-hygiene behavior. A real `codex-cli 0.145.0` App Server
probe also passed configured MCP launch, two exact-thread calls, and exact
process-restart/thread-resume/thread-read.

The real provider probe did not expose a provider-controlled input that could
carry a Synesis-created proof to the exact MCP bridge/thread. The static MCP
configuration marker was used only as a non-secret control. The result is
therefore **PARTIAL**: the managed provider lifecycle seam is observable, but
the protected attachment chain is not proven and production implementation
remains blocked. Full redacted commands, hashes, reports, and the no-bypass
record are in
[
`SYN-050-protected-carrier-prototype-2026-09-03.md`](SYN-050-protected-carrier-prototype-2026-09-03.md).
