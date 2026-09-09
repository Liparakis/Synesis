# SYN-051 shared-home security-model audit — 2026-09-03

## Classification

**PASS-B — safe with one minimal additional Synesis invariant.**

The raw Codex observation is not, by itself, a Synesis authority compromise.
Codex thread visibility and resumability are provider resource properties.
Synesis authority is granted only after the Synesis-managed attachment boundary
has authenticated the exact binding, proof, and current generation. The current
production slice already implements the per-binding proof and generation fence,
and the disposable broker spike demonstrated exact pre-provider thread pinning.

The missing invariant is a Synesis-owned, durable, compare-and-set provider
thread ownership constraint: one active managed binding may own an exact
`(provider, threadId)` pair. The invariant must be established before managed
attachment admission and checked by the trusted broker. It is not a provider
change and it is not a second identity graph. Production implementation of the
invariant, broker, and owned process tree remains unauthorized by this audit.

This result narrows ADR-0057. ADR-0057 remains accurate as an operational
provider experiment: the normal Codex store does not itself provide worker
thread ownership. It is not sufficient evidence that provider-enforced thread
ownership is required for Synesis authority under the accepted local threat
model.

## 1. Starting state and scope

| Item                    | Result                                                                                                                |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Starting HEAD           | `50865a2a67344ef37d597a9f92a578a21ce11921`                                                                            |
| Starting Git state      | `master`, clean; no push                                                                                              |
| Active task             | `SYN-051`, active; managed acceptance remains blocked                                                                 |
| Existing implementation | Dedicated retained Codex home, App Server, exact thread, proof, and generation slice; no production shared-home mode  |
| Audit mode              | Source/evidence analysis only; no production code, provider state, credentials, fixtures, or `.synesis` state changed |
| Targeted probes         | None. Source and existing bounded broker evidence resolved the requested ambiguity                                    |

## 2. Accepted threat model

The audit used the existing SYN-050 continuity threat model, not a newly
invented standard. In scope are same-human independent chats, accidental worker
confusion, provider/runtime restart, stale generations, malformed or replayed
attachment attempts, concurrent managed workers, and ordinary provider
behavior. Same-human Chat A, B, and C remain separate unless an authenticated
protocol deliberately relates them. Project, participant, WorkIntent,
WorkGroup, path, newest-state, thread, connection, or public IDs are not
supposed to be credentials.

The accepted local boundary excludes a fully compromised same-user OS account,
direct malicious editing of Synesis durable state, arbitrary same-user process
memory inspection/debugging, and deliberate hostile local injection. A malicious
local process that merely knows public IDs is still not entitled to recover
authority; that requirement is why thread/connection IDs are not accepted as
managed attachment proof. The supported-workflow question is narrower: whether
an ordinary raw or managed worker can obtain another worker's Synesis authority
without the protected managed attachment path.

The existing records do not promise worker-to-worker confidentiality of all
provider conversation data under one provider account. They do promise distinct
Synesis logical workers and fail-closed managed authority. Thus raw provider
conversation visibility is recorded separately from Synesis authority.

## 3. Required Synesis security property

The correct property is:

> A runtime that is not the current authenticated managed attachment for
> Synesis Worker A must never acquire or exercise Worker A's Synesis authority.

Authentication answers whether runtime `R` may speak as logical binding `A` at
current attachment generation `G`. Authorization then decides whether A may
read or mutate a path, consume a grant, publish, review, complete, or
integrate. Provider thread selection is an input to the managed boundary; it
is not a substitute for either authentication or authorization.

## 4. Authority-layer model

| Object                          | Classification                             | Audit consequence                                                                              |
|---------------------------------|--------------------------------------------|------------------------------------------------------------------------------------------------|
| Codex account/authentication    | Provider authentication root               | Allows provider use; does not identify a Synesis worker                                        |
| `CODEX_HOME`                    | Provider storage/configuration scope       | Shared visibility is not Synesis identity; isolated homes add provider/runtime separation      |
| Persisted Codex thread ID       | Provider resource selector/correlation     | Resume target and scope check; not a credential                                                |
| Synesis project                 | Logical namespace                          | Selects durable project state; not worker authentication                                       |
| Participant/binding/session     | Durable Synesis logical identity           | Owns worker/session authority and exact connection binding                                     |
| WorkIntent/claims               | Coordination authorization state           | Governs task/claim permissions after session authority                                         |
| Managed attachment              | Runtime-to-binding authentication record   | Relates a disposable runtime to an existing binding; creates no new worker identity            |
| Attachment generation           | Freshness/fencing state                    | Only the current generation may exercise managed authority                                     |
| Attachment proof                | Runtime authentication credential          | High-entropy, process-private, hash-only at rest, rotated and replay-fenced                    |
| Broker                          | Trusted lifecycle boundary                 | Derives/pins the provider thread, controls App Server protocol, and mediates attachment        |
| Pinned provider thread          | Exact provider resource ownership selector | Must be durably unique per active managed binding; not itself a credential                     |
| Owned process tree / Job Object | Liveness and containment evidence          | Establishes which processes belong to an attachment and enables teardown; not thread identity  |
| MCP connection                  | Runtime transport/incarnation              | Carries requests after startup admission; exact connection identity alone is not managed proof |

## 5. Exact attack analysis

For A=`binding A`, `Thread A`, `Proof A`, generation 5, and B=`binding B`,
`Thread B`, `Proof B`, generation 8:

```text
fresh raw Codex process
  -> same normal CODEX_HOME/auth
  -> resume Thread A
```

The raw process can obtain provider authentication and can resume Thread A.
On the supported raw path it does not receive Proof A or the current managed
generation. `SynesisMcpServer` treats absent proof as ordinary
`SESSION_BOUND`; it does not synthesize managed continuity from thread
metadata. `ensure_session` resolves the supplied connection instance exactly;
it has no provider-wide/latest-thread fallback. With a fresh connection value,
the raw process can establish only its own ordinary binding/intent path, not A.

It cannot authenticate managed attachment A, obtain Proof A, or pass the
managed attachment startup gate merely from Codex auth plus Thread A. It would
need the protected managed proof and the current generation, plus the exact
managed binding/thread scope. Deliberate substitution of a known existing
connection selector is not the raw-thread-resume property being tested and is
not accepted as a managed credential; future managed admission should
explicitly reject proof-less recovery of a binding that is in managed mode.

## 6. Raw versus managed Codex

Raw/unmanaged Codex may access the provider thread store but is not launched by
the trusted Synesis lifecycle boundary and has no managed proof or generation.
Managed Codex is launched by the trusted lifecycle boundary, uses one pinned
App Server/thread, receives a process-private proof, and is admitted only after
exact binding/thread/generation checks.

The current source supports this distinction at startup: a supplied invalid
managed proof is rejected, while no proof remains ordinary `SESSION_BOUND`.
The future shared-home profile must make the distinction explicit at the
binding-recovery ingress so a proof-less runtime cannot be upgraded by a
matching thread or connection selector.

## 7. Managed MCP startup and `ensure_session`

`SynesisMcpServer` receives `SYNESIS_ATTACH_PROOF` only at the managed startup
boundary. When present, it resolves the exact provider binding, loads that
binding's adapter-private record, derives the record's exact thread and current
generation, and calls `ManagedAttachmentService.authenticate`. The service
checks project, provider, binding session, thread, generation, status, and
constant-time proof-hash equality. No valid proof means no managed startup.

`ensure_session` itself still calls the existing
`ProviderSessionBindingService.ensure` with the MCP connection identity. The
service keys lookup by the exact fingerprint and does not select the newest
provider binding. This proves that Thread A alone cannot cause recovery of A.
It also identifies a required future hardening for the shared profile: a
managed binding must not be silently downgraded into the ordinary session-bound
path when proof is absent. That is a managed-admission policy check, not a
provider guarantee.

## 8. Thread-ID usage audit

Production thread-ID uses were classified as follows:

| Usage                                               | Classification                                         | Result                                                                                     |
|-----------------------------------------------------|--------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `ManagedAttachmentRecord.threadId`                  | Durable exact provider scope                           | Compared with the attachment request; not standalone auth                                  |
| `AttachmentRequest.threadId`                        | Caller/runtime selector                                | Accepted only together with binding, generation, and proof; not sufficient alone           |
| `CodexLifecycleStateStore.Checkpoint.threadId`      | Durable lifecycle correlation and exact protocol scope | Used to constrain `thread/resume`, `thread/read`, `turn/start`, steer, wait, and interrupt |
| `LifecycleControlRequestEnvelope.expectedThreadId`  | Signed/digested lifecycle request scope                | Exact request predicate; not an independent credential                                     |
| App Server `thread/start` response                  | Provider correlation                                   | Stored only after exact response/event identity checks                                     |
| App Server `thread/resume` and `thread/read` params | Resume target                                          | Derived from the prior checkpoint and checked against returned identity                    |
| Evidence/journal thread values                      | Audit/log correlation                                  | Must be redacted/bounded; not authorization input by themselves                            |
| Provider binding `sessionId`/connection evidence    | Exact ordinary connection lookup                       | Not a managed attachment proof; no latest fallback                                         |

No production path was found that hashes or treats `threadId` alone as a
credential. Conversely, no current durable index enforces one active managed
binding per exact provider thread.

## 9. Managed B versus Thread A

The broker feasibility record proves that a trusted broker can derive one exact
thread pin, reject B→A locally before Codex receives the operation, and
serialize successor takeover. At the managed authentication layer, B's Proof B
against B's record is rejected if the presented thread is A while B's durable
record says B's thread. This is defense in depth.

The current production lifecycle API is not yet the complete broker boundary:
`ManagedCodexProcessLauncher.prepareFirst` and `prepareReplacement` accept a
thread argument, and no production `ProviderThreadLease`/unique ownership CAS
exists. Therefore the future implementation must derive the thread from
trusted durable binding/lease state rather than from a worker-facing request.

## 10. ProviderThreadLease assessment

No type or index named `ProviderThreadLease` exists. `ManagedAttachmentRecord`
is equivalent to a per-binding attachment record: it stores project, provider,
binding, exact thread, generation, proof hash, home identity, status, and
revision. `ManagedAttachmentStore.compareAndReplace` provides one-winner
generation/proof replacement for that one record.

It does not provide the cross-binding uniqueness invariant:

```text
one active managed (provider, exact thread) -> one binding
```

The minimal additional Synesis primitive is therefore a durable unique
provider-thread ownership CAS/lease, or an equivalent atomic index. It must
reject duplicate active ownership, bind the broker's immutable pin to the
winning binding, and release/rotate ownership only through the existing
generation/terminal lifecycle. It must not become a second participant or
identity graph.

## 11. Broker, generations, and process ownership

The security composition is:

```text
binding             = logical worker identity
thread lease/pin    = exact provider resource ownership
proof               = runtime authentication
generation          = current-authority freshness/fence
broker              = trusted provider selection and protocol boundary
Job/process tree    = attachment liveness, containment, and teardown
authority resolver  = exact Synesis authorization lookup
```

The Job Object does not decide which Codex thread belongs to which binding. It
proves which descendants belong to attachment generation N and supports
teardown before successor activation. The broker solves provider-thread
selection. Proof solves runtime authentication. Generation prevents a stale
runtime from acting after takeover. The existing resolver preserves exact
connection authority and no latest-binding fallback.

Two A successors may both technically resume Thread A at the provider layer,
but only one may win the durable generation/proof replacement. The loser must
fail authentication or generation validation and have its owned tree torn down.
No interval with two valid Synesis authorities is permitted. A stale A1 with
Proof A1 is rejected after generation N+1, even if process cleanup is delayed;
process death is a liveness/containment control, while generation is the
authorization boundary.

## 12. Confidentiality and same-human chats

The audit found no repository contract promising that a same-account raw Codex
process cannot read or resume another provider conversation. The provider
experiment therefore remains a provider conversation-confidentiality concern,
not automatically a Synesis authority failure.

The promised same-human A/B distinction is logical: separate bindings,
participants, WorkIntents, claims, and exact current authority. Under the
managed profile, broker pinning plus proof, generation, and binding checks
preserve that distinction. Same repository/provider/worktree knowledge alone
does not relate A and B.

## 13. Restart and race result

For A1→A2, the successor may resume only the trusted durable Thread A pin,
must receive fresh Proof A2, and must win generation N→N+1 after A1's process
tree is definitively dead. A2 cannot become B if the broker derives its thread
from A's binding lease. If liveness is ambiguous, successor authority is
rejected. If two successors race, the existing attachment store's atomic
compare-and-replace gives one winner for the per-binding generation; the new
unique thread lease is required to prevent cross-binding duplicate ownership.

## 14. `UNSAFE_FILE_AUTH`

The current `UNSAFE_FILE_AUTH` classification is correct for the implemented
isolated-home profile: copying file-backed provider credentials into a fresh
managed home would require Synesis to handle or duplicate provider secret
state. It is not evidence that a shared normal provider home is inherently
unsafe for Synesis authority.

If the shared profile is later authorized, policy must distinguish:

```text
isolated managed home + Synesis copies file-backed auth -> UNSAFE
normal provider-owned home + provider reads its own auth
  + Synesis never reads/copies the credential -> potentially permitted
```

That policy change is not authorized by this audit. It would require the
proof-gated managed ingress, unique provider-thread ownership CAS, immutable
broker pin, owned process-tree integration, and fresh authenticated acceptance.

## 15. Proposed invariant decision

The ten-part proposed invariant is sound as a security model after adding the
durable unique provider-thread ownership constraint and making proof-gated
managed admission explicit. Its first false premise in the current repository
is not provider visibility; it is premise 2/3 as an implemented invariant:
the current attachment record stores an exact thread per binding, but current
production code has no cross-binding unique thread lease and the launcher API
still receives a thread argument. The broker spike demonstrates feasibility,
not production enforcement.

Codex itself does not need to cryptographically enforce Synesis logical-resource
ownership. Synesis may enforce that invariant inside its trusted lifecycle
broker, just as it already enforces claims, WorkIntent, generations, and exact
connection authority. Provider-enforced ownership would be stronger for
provider conversation confidentiality, but it is not required for the stated
Synesis managed-authority property.

## 16. Required report fields

1. Starting HEAD: `50865a2a67344ef37d597a9f92a578a21ce11921`.
2. Threat model: existing SYN-050 local continuity model; ordinary independent
   chats/restarts/races/replays in scope; fully compromised same-user OS,
   direct durable-state tampering, arbitrary memory inspection, and hostile
   injection out of scope.
3. Synesis authority property: only the current authenticated managed runtime
   may exercise Worker A's Synesis authority.
4. Provider-thread property: exact provider resource selection and scope.
5. Provider thread is identity: no; Synesis binding/session is identity.
6. Provider thread is credential: no; proof is the credential.
7. Managed proof: private runtime authentication, hash-only at rest, replay and
   scope fence.
8. Attachment generation: freshness and stale-runtime authorization fence.
9. Broker: trusted lifecycle owner deriving and pinning the exact thread.
10. Job Object: owned process-tree containment, liveness, and teardown.
11. Durable binding: existing logical worker/session identity and authorization
    anchor.
12. Raw Codex classification: provider-capable but unmanaged for Synesis.
13. Raw Codex can resume A: yes, with shared provider auth/store.
14. Raw Codex can acquire A Synesis authority from that fact alone: no.
15. Managed B can request A through a correct broker: no; local pin rejects it.
16. Managed B can authenticate with Thread A: no when B's durable pin is B;
    proof/thread mismatch rejects it.
17. Thread usage: selector/correlation/protocol scope, never standalone auth.
18. `ensure_session`: exact connection binding lookup; no latest/thread fallback;
    future managed mode must reject proof-less downgrade.
19. Managed MCP startup: proof → exact binding record → exact thread/generation
    verification before managed mode.
20. Authority resolver: exact connection identity and active/terminal checks;
    no latest fallback.
21. Stale generation: rejected by attachment proof/generation checks.
22. Race: one per-binding generation winner; unique thread lease needed across
    bindings.
23. Losing successor: must fail closed and have its owned tree torn down.
24. Process death versus authorization: death is cleanup/liveness; generation
    is authorization freshness.
25. Provider conversation confidentiality: not promised as a same-account
    provider-store property; separate from Synesis authority.
26. Same-human A/B: distinct logical bindings and coordination state remain
    separate.
27. Existing ProviderThreadLease: no.
28. Additional ownership needed: yes, minimal durable unique provider-thread
    CAS/lease.
29. Targeted raw probe: not run; source path resolved the question.
30. Second B→A probe: not run; broker and authentication evidence resolved it.
31. First false premise: current implementation lacks durable cross-binding
    thread ownership and broker-derived thread input; not provider visibility.
32. Provider-enforced ownership necessary: no for Synesis authority; desirable
    only for stronger provider-confidentiality guarantees.
33. `UNSAFE_FILE_AUTH`: correct for isolated-home credential-copy semantics;
    too broad only for a future explicitly provider-owned normal-home profile.
34. Minimum future production change: proof-gated managed admission plus one
    durable unique provider-thread ownership CAS, then broker/tree integration;
    no Codex patch.
35. Security argument: raw thread resume lacks proof/current generation; managed
    broker and exact fences constrain authority.
36. Counterargument: the provider can expose/read/resume another thread, and
    current production launcher input is not yet broker-derived; this blocks
    present implementation, not the provider-enforcement conclusion.
37. Final classification: `PASS-B`.
38. SYN-051 unblocked in principle: the security-model blocker is unblocked in
    principle; implementation/authentication/acceptance gates remain blocked.
39. Remaining unknowns: production unique lease/broker wiring, proof-gated
    managed fallback, owned-tree edge cases, safe auth policy, and fresh real
    authenticated restart acceptance.
40. Evidence/ADR changes: this record and ADR-0060; no production changes.
41. Commits: documentation-only audit commit to be recorded after validation.
42. Final HEAD: recorded after the documentation-only commit.
43. Final Git status: must be clean after the documentation-only commit.
44. Push: none.
45. Exact next action: preserve `UNSAFE_FILE_AUTH`; authorize a bounded
    production design slice for proof-gated managed admission and unique exact
    provider-thread ownership before any shared-home policy change.

## Evidence used

- `docs/evidence/SYN-050-managed-attachment-design-2026-09-03.md`
- `docs/evidence/syn050-provider-session-continuity-capability-design-2026-09-03.md`
- `docs/evidence/SYN-051-broker-pinned-thread-feasibility-2026-09-03.md`
- `docs/evidence/SYN-051-job-object-process-tree-feasibility-2026-09-03.md`
- `docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md`
- `docs/adr/0057-reject-shared-normal-codex-home.md`
- `docs/adr/0058-bounded-broker-thread-pin-feasibility.md`
- `docs/adr/0059-windows-job-object-process-tree-feasibility.md`
-
`workspace/src/main/java/org/synesis/workspace/application/provider/continuity/ManagedAttachmentService.java`
-
`workspace/src/main/java/org/synesis/workspace/application/provider/continuity/ManagedAttachmentStore.java`
- `workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java`
-
`workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionBindingService.java`
- `workspace/src/main/java/org/synesis/workspace/lifecycle/codex/ManagedCodexProcessLauncher.java`
-
`workspace/src/main/java/org/synesis/workspace/lifecycle/codex/CodexAppServerLifecycleService.java`
- `mcp/src/main/java/org/synesis/mcp/SynesisMcpServer.java`
- `mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java`
