# Collaborative Agent Fabric phase map and first record slice

## Status

Planning baseline with SYN-001 closed at CP-R4 - 2026-07-21. CP-R5 is
explicitly deferred. The
current repository decisions and verification evidence remain authoritative
over the supplied Collaborative Agent Fabric (CAF) concept PDF. Link and
`:cli` remain unchanged.

`SYN-001` is DONE at CP-R4. The verified Link seam and CP-R4 protocol remain
frozen. `SYN-002` implements only the bounded local searchable view inside
`:project-record`; this checkpoint stops after SYN-002.

## Evidence ledger

| ID | Claim or constraint | Class | Source | Architectural effect | Validation / owner |
|---|---|---|---|---|---|
| E1 | Link provides authenticated, control-ready, live two-profile sessions. | VERIFIED | `docs/evidence/ONBOARDING-PROCESS-2026-07-20.md`, SL-012/SL-013 tests | Reuse Link identity and authenticated session; do not build a new transport. | Existing strict verification; Link owner. |
| E2 | The generated CLI is a development distribution; physical launcher validation is unclaimed. | VERIFIED | ADR-0010, `docs/evidence/CLI-DISTRIBUTION-VALIDATION.md` | Do not turn it into the project-record command surface in this slice. | Existing physical-validation gate remains separate. |
| E3 | `PeerSession` exposes only `requestDemoWork`; `Onboarding` owns host/join and closes after its fixed demo exchange. | VERIFIED | `link/.../PeerSession.java`, `link/.../Onboarding.java` | A project module cannot publish or receive an authenticated record without a Link transport seam. | Review before activation; Link owner. |
| E4 | The product intent is one trustworthy, current, explainable project reality shared by two humans. | USER-STATED | CAF PDF, pp. 1-9; current request | Start with one signed, versioned decision record, not supervisors or workers. | Two-profile vertical test; Synesis owner. |
| E5 | Local storage location, project bootstrap ceremony, and scale targets are unspecified. | UNKNOWN | Repository and request | Keep file storage, explicit peer allowlist, and a single-record protocol reversible; make no scale or availability claim. | `SYN-001` test plan; product owner. |

## CAF phase map against the workspace

| CAF phase | Required outcome in the PDF | Current Synesis evidence | Classification | Next boundary |
|---|---|---|---|---|
| 0 - specification | Record schemas, freshness rules, threat model, ownership semantics | Link has ADRs, wire formats, threat model, task memory, and a deferred register. It has no project-record schema or project trust policy. | PARTIAL | This document and proposed ADR-0011 define the first record contract only. |
| 1 - shared project brain | Canonical event log and structured knowledge store; decisions/failures searchable | CP-R4 proves one signed decision type and local immutable storage/sync; SYN-002 adds only a local read-only view, not an event log or second record type. | PARTIAL | Keep failed-experiment type and canonical log deferred. |
| 2 - two human nodes | Two local supervisors synchronize context and status | Two independent profiles exchange one signed decision through Link; no physical claim, supervisor, or continuous sync exists. | PAIRWISE PROOF ONLY | Keep CP-R5 deferred; do not add supervision or background behavior. |
| 3 - isolated workers | Worktrees and worker publication | No workers or worktrees. | DEFERRED | Do not introduce before a shared-record proof is stable. |
| 4 - ownership and leases | Semantic ownership, leases, contention paths | Link has no project ownership. Task documents have a human-maintained active-task rule only. | DEFERRED | Record owner is immutable signer authority, not a lease system. |
| 5 - autonomous negotiation | Policy-governed low-risk approvals | No such policy or autonomy exists. | DEFERRED | Keep all record creation and peer configuration operator-invoked. |
| 6 - federation | Multi-organization replication and trust policies | No server, replication topology, or organization model exists. | DEFERRED | Two explicitly configured profiles only. |

The repository is therefore at the useful precondition for CAF Phase 1: a
tested authenticated transport, not a project brain. Treating the existing
Markdown files as the shared plane would repeat the PDF's rejected
"shared prose" design.

## Selected smallest target

Use exactly one record type: `decision`.

A decision is the smallest record that exercises identity, evidence,
provenance, versioning, immutable history, a clear owner, and a useful human
inspection path. A failed-experiment record is explicitly deferred; adding it
now duplicates the envelope and conflict machinery before it has a second
consumer.

`SYN-001` proves one narrow statement only:

> Two preconfigured local profiles can authenticate each other through Link,
> publish, persist, inspect, and synchronize the same signed decision record;
> they detect duplicate, divergent, and stale state without overwriting a
> trustworthy local revision.

It does not claim a canonical global log, consensus, membership, task
coordination, availability while disconnected, or continuous replication.

## Smallest next slice after SYN-001

Compare the two plausible ways to extend the shared signed-decision proof:

| Candidate | New authority/surface | Evidence value | Decision |
|---|---|---|---|
| Add a `failed-experiment` record type | New canonical schema, lifecycle, signature vectors, storage, sync, inspection, and compatibility rules | Proves a second schema but has no current consumer or invariant | DEFERRED |
| Add a minimal searchable project view | Read-only derived query over existing verified decision heads; no wire or durable format | Proves that a human can find and compare signed project truth | SELECTED for SYN-002 planning |

SYN-002 remains read-only and local. It queries record ID, bounded title
or rationale text, status, owner/author, revision, and digest. Results must be
bounded, deterministic, verified-head-only, and fail closed on corruption. It
must not write an index or change Link, `:project-record`, or `:cli` unless a
proven read-only enumeration blocker is recorded and separately approved.

## Blocking boundary: frozen Link and CLI

The current code has no transport-neutral application-stream API. `Onboarding`
does not return a session to its caller, and the only operation on
`PeerSession` is the fixed `synesis-demo-work/1` request. Consequently, a
new project module cannot implement authenticated publish/sync above Link
without one of these choices:

1. **Selected, pending explicit approval:** unfreeze one minimal,
   transport-neutral Link application-stream seam in a dedicated prerequisite
   task. It may expose bounded framed bytes and authenticated remote identity,
   but may not name decisions, projects, records, owners, or sync.
2. Keep Link frozen and copy a local file out of band. This fails the required
   authenticated publish/sync proof and is rejected.
3. Create a separate socket, QUIC, or HTTP transport in a project module. This
   duplicates Link's security boundary and is rejected.

CLI remains frozen in every option. If the selected prerequisite is approved,
the project-record module owns a separate, minimal development launcher for
record creation, sync, and inspection. It uses JDK argument parsing; adding
Picocli or changing `:cli` is not justified for these fixed commands.

## Proposed ownership and module boundaries

```text
profile A :project-record  -- authenticated bounded record frames -->  :project-record profile B
        |                                                                  |
        +-- local record store                                            +-- local record store
        |                                                                  |
        +-------------------- :link (identity + authenticated stream) ---+
```

`link` remains the owner of long-term node identity, authenticated peer
identity, session readiness, framing transport, deadlines, and stream cleanup.
It neither parses nor persists project records.

The proposed `:project-record` module owns the decision schema, canonical
encoding, decision signatures, project-local peer allowlist, file persistence,
sync state comparison, conflict quarantine, and its own inspection launcher.
It depends one-way on `:link`; Link never depends on it. This new module is
justified only once the blocking Link seam is explicitly approved: it owns
durable application data that must not leak into Link, and CLI is frozen.

There is no service, database, broker, background daemon, Obsidian bridge,
worker, worktree, lease, or federation component.

## Decision record contract v1

The canonical signed payload is deterministic binary `SDR1`, not Java object
serialization and not ad-hoc Markdown. A readable rendering is produced only
by inspection. Exact field widths and vectors belong in a protocol document
when the task is activated.

| Field | Rule |
|---|---|
| `schemaVersion` | Fixed `1`; unknown versions reject before allocation. |
| `projectId` | Operator-created UUID; every local store and inbound frame must match it. It is a namespace, not membership. |
| `recordId` | Random UUID generated once at decision creation; stable for all revisions. |
| `recordType` | Fixed literal `decision`; no extensible type registry in this slice. |
| `version` | Positive monotonically increasing `long`, starting at `1`. |
| `previousDigest` | Absent only at version `1`; otherwise SHA-256 of the immediately preceding canonical signed record. |
| `ownerNodeId` | Immutable Link node ID. Only this identity can create or amend this record. |
| `authorNodeId` | Must equal `ownerNodeId` in v1; retained explicitly as provenance rather than inferred from a connection. |
| `status` | `PROPOSED`, `ACCEPTED`, `REJECTED`, or `SUPERSEDED`. Status transition policy is owner-only; no review workflow. |
| `createdAt` / `updatedAt` | UTC provenance timestamps; informative only, never used to order, authorize, or detect freshness. |
| `title` / `rationale` | Strict UTF-8, bounded fields; a decision needs readable content. |
| `evidence` | At least one and at most a small fixed number of `{kind, reference, sha256}` values. The digest is a 32-byte SHA-256 value; evidence references are not fetched or trusted by this slice. |
| `signature` | Ed25519 signature by `ownerNodeId` over every preceding canonical field. |

The initial task selects a conservative maximum complete record size of 16 KiB
and a fixed maximum evidence count of 8. These are resource bounds, not
capacity promises; exceeding them is a deterministic rejection. Increase them
only with a compatibility review and a measured need.

## Trust, publish, sync, and freshness contract

### Local project configuration

Each profile is manually initialized with the same `projectId` and the other
profile's Link node ID. The allowlist is local configuration, not a project
membership system and not a canonical record. A record is accepted only when:

- the Link session is authenticated and control-ready;
- the session remote node ID is in the configured allowlist;
- the record `projectId` matches the local project;
- the record's owner and author are the authenticated remote node ID;
- the Ed25519 signature verifies against the authenticated remote public key;
- schema, bounds, version, and predecessor digest rules hold.

This double check deliberately makes the persisted record independently
verifiable after transfer while preventing an allowed peer from attributing a
record to a third identity.

### Publish and sync messages

The proposed project protocol has only four bounded message kinds:

`SYNC_REQUEST(projectId, recordId, knownVersion, knownDigest)`;
`RECORD(recordBytes)`; `RESULT(APPLIED | DUPLICATE | REMOTE_STALE |
CONFLICT | REJECTED, localHead)`; and `ERROR(code)`.

One request carries one record snapshot. There is no subscription, batch,
event log, remote execution, retry loop, or background replication. A
publisher first atomically persists its signed local revision, then sends it.
If the result is lost, retry is safe because duplicate handling is
deterministic; the sender reports delivery as unknown rather than claiming a
remote commit.

Freshness is version-plus-digest, not wall-clock time:

- a local amendment requires the current local `(version, digest)` as its
  explicit base;
- a request reports the caller's known head;
- a higher valid contiguous remote revision proves the local profile stale;
- an equal version with a different digest, a predecessor mismatch, or a
  version gap is a conflict, never a last-writer-wins overwrite;
- a lower valid remote revision is reported as remote-stale and retained only
  when already present as immutable history.

## Storage model

Use a per-profile filesystem store only. No database is warranted for one
record and its revision chain.

```text
<profile>/synesis/projects/<projectId>/
  config/peer-allowlist                local-only configuration
  decisions/<recordId>/<version>.sdr   immutable signed canonical revisions
  heads/<recordId>                     atomically replaced {version,digest}
  conflicts/<recordId>/<version>-<digest>.sdr  quarantined valid divergent input
```

Writes use a sibling temporary file, fsync where supported, then an atomic
move. A head changes only after its immutable revision is durable. Startup
ignores temporary files and reconstructs a missing head from validated
immutable revisions; it never silently selects between conflicting heads.
The CLI inspection renders a requested revision, its digest, signature state,
owner, status, evidence summaries, and whether the local head is known stale
or conflicted. It does not expose private keys or endpoint addresses.

## Failure cases and required behavior

| Case | Required result |
|---|---|
| Invalid bounds, schema, UTF-8, project ID, signature, owner/author, or unauthenticated peer | Reject before durable mutation; return a typed safe code. |
| Valid byte-identical revision already stored | Return `DUPLICATE`; do not rewrite the revision or head. |
| Incoming revision is lower than local head | Return `REMOTE_STALE`; retain existing immutable history only. |
| Equal version but different digest, predecessor mismatch, or version gap | Quarantine the valid signed input and return `CONFLICT`; retain local head. |
| Local base version/digest has changed before amendment | Reject the local amendment as stale; do not create a new version. |
| Disk failure or crash during write | No head advancement without a durable revision; temp files are ignored/recovered explicitly. |
| Connection closes before result | Remote outcome is `UNKNOWN`; retry uses the same immutable signed bytes and is idempotent. |
| Link liveness expiry / transport close | Stop the one operation, preserve local durable state, and make no reconnection claim. |

## Test plan and acceptance criteria

The plan intentionally separates the prerequisite Link seam from the record
proof. Both must pass from a clean root strict build when activated.

| Layer | Smallest falsifying checks |
|---|---|
| Canonical decision | Stable encode/decode/digest/signature vectors; tampered field, signature, owner, evidence hash, UTF-8, and size rejection. |
| Local store | Atomic revision/head update; restart reconstruction; duplicate no-op; failed write leaves previous head; base-version stale rejection. |
| Conflict rules | Contiguous successor applies; stale lower revision rejects; equal-version divergence and predecessor gap quarantine without overwrite. |
| Link prerequisite | A usable authenticated session can open/accept one bounded transport-neutral application stream; pre-ready, over-limit, and terminal-session use reject and clean up. |
| Two-profile sync | Isolated profiles with distinct identities and a shared configured project ID publish one decision from A; B authenticates, verifies, persists, and renders the same record. A retry is duplicate. A divergent same-version input becomes a conflict. B detects it is stale after A publishes a valid successor. |
| CLI inspection | The new launcher renders a local decision revision and safe status with no private key, full invitation, endpoint, or absolute personal path. |
| Regression | Existing Link and CLI tests remain unchanged and pass; no project package imports from Link. |

`SYN-001` is accepted only if the two-profile test proves all of the following:

1. the same `recordId`, version, digest, owner, status, provenance, and
   evidence digests are readable on both profiles;
2. each accepted remote revision is both session-authenticated and
   independently signature-verified;
3. duplicate, stale, conflict, and stale-local-amendment cases are observable
   typed results with no incorrect head overwrite;
4. a restart preserves readable state and validation; and
5. the evidence records no unsupported availability, automatic discovery,
   membership, or autonomous-operation claim.

## Checkpoint breakdown

No checkpoint in this list authorizes implementation. They are the minimum
durable milestones if the user later activates the work.

| Checkpoint | Scope | Required evidence |
|---|---|---|
| CP-R1 | Approve ADR-0011, promote the one Link stream prerequisite, record no-change compatibility rules | ADR, task/CURRENT agreement, Link API contract review. |
| CP-R2 | Canonical decision model, signature, local immutable store, and inspection launcher | Unit/store tests, strict build, storage recovery evidence. |
| CP-R3 | Bounded transport-neutral Link application-stream seam | Link unit/integration checks for authentication, bounds, and cleanup; no project terminology in Link. |
| CP-R4 | Record protocol, peer allowlist, publish/sync, duplicate/conflict/staleness handling | `ProjectRecordSyncProcessTest`, bounded codec/config tests, and strict build; PASS; SYN-001 DONE. |
| CP-R5 | Physical two-profile record transfer claim | DEFERRED as `SL-D-027`; no physical claim is made. |
| CP-R6 | Review SYN-002 query contract and prove whether existing read-only APIs suffice | ADR-0013, task/CURRENT agreement, and validated-head blocker review; PASS. |
| CP-R7 | Minimal local searchable decision view | Bounded query tests, corruption/no-mutation tests, and strict build; PASS. |
| CP-R8 | Evidence and re-evaluation of second record type | Restart-equivalence and safe projection evidence; second record remains deferred; PASS. |

## Deferred and explicitly unclaimed

The following are intentionally outside `SYN-001` and SYN-002: a second record type
(including failed experiments), a project-wide event log, project membership,
role delegation, multi-writer decisions, reviewer approval, task state,
ownership graph, leases, fencing, workers, worktrees, patch exchange,
Obsidian projection, daemon/background sync, subscriptions, queues, retries,
automatic reconnect, offline merge, authority election, consensus, server
hosting, federation, organization boundaries, revocation, and autonomous
negotiation.

The slice also does not claim that a decision is true merely because it is
signed, that evidence references are available or correct, that clocks agree,
that two profiles are physically on different machines, or that a disconnected
profile will reconcile automatically.

## Decision recorded for review

`SYN-001` is closed as DONE at CP-R4. CP-R5 is deferred. SYN-002 is verified
and remains active only for review/closure; no new CAF slice is authorized.
