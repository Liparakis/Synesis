# Speculative capability coordination

## Purpose

Synesis coordinates independent local supervisors without making one global AI
the owner of all work. A supervisor may predict another node's capability,
request an explicit owner decision, implement in an isolated worktree, and
publish a capability only after validation.

## Bounded module graph

```text
:link -> :project-record -> :coordination -> :workspace -> :cli
```

`:link` supplies node identity and transport/session primitives. `:project-record`
supplies verified SDR2 decisions and scope matching. `:coordination` owns the
semantic protocol and durable event history. `:workspace` remains responsible
for provider policy and Git/worktree operations. `:cli` composes foreground
commands. No module below coordination imports provider or task behavior.

## Identities and ownership

Each node has an independent Ed25519 `NodeIdentity`. `requesterSupervisorId`,
`requesterWorkerId`, and `ownerSupervisorId` identify logical actors inside a
node; they do not become additional trust roots. An ownership claim binds a
capability and protected scopes to an owner node/supervisor and an intent
version. A lease only grants temporary execution permission and can expire
without changing that claim.

## Event history

The coordinator persists one immutable `SCE1` event per sequence in a project
directory. Each event includes project/prediction identity, actor node,
previous digest, current digest, signer public key, signature, and bounded
payload. Startup verifies the entire chain and replays the projection. The
projection is deterministic and rejects lifecycle violations.

The live interface is HTTP for commands and server-sent events for updates. A
client supplies `Last-Event-ID`/sequence on reconnect; the coordinator replays
durable events before streaming new ones. Delivery is at-least-once and event
identifiers are stable, so clients must deduplicate.

## Prediction and execution

The prediction contract records the requested task, owning capability, scopes,
base project sequence/commit/scope hashes, owner intent version, behavior,
errors, side effects, invariants, compatibility, performance, concurrency,
acceptance tests, confidence, risk, and expiry. The lifecycle is:

```text
PROPOSED -> ROUTED -> RECEIVED
  -> ACCEPTED_EXACT | ACCEPTED_EQUIVALENT | REVISED
  -> IMPLEMENTING -> PATCH_READY -> AVAILABLE -> VALIDATING -> RETIRED
```

Rejection, invalidation, or expiry are terminal side paths from any nonterminal
state. Provider actions must evaluate policy constraints first; a foreign owned
scope returns `REQUEST_OWNER` with the capability, owner, protected scope,
prediction/sequence context, and explicit next command. It never silently
mutates ownership.

## Isolation and evidence

Speculative changes live below `.synesis/local/speculation/<prediction-id>` and
are applied through a dedicated worktree/overlay. The first gate rejects
unresolved merges and missing base evidence. Validation records test commands,
commit/tree IDs, event sequences, and final Git status. Remote TLS enrollment,
multi-host transport, and richer knowledge projections remain deferred until a
real two-agent acceptance run supplies evidence.
