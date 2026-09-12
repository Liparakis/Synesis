# ADR-0076: Overlay adjacency reconciliation after physical loss

- Status: ACCEPTED FOR SL-D-036 CHECKPOINT 1
- Date: 2026-09-12
- Decision scope: local physical adjacency recovery above existing Synesis Link

## Context

`SL-D-040` established signed project membership, deterministic desired
topology, authenticated `PeerSession` bindings, topology publication, and
route selection. It deliberately did not reconnect a session after transport
loss. The existing liveness boundary can identify terminal sessions, while
retained signed `CandidateDescriptor` records can supply current direct
candidates. A dead socket must not revoke the signed member or remain in local
route inputs.

## Decision

Add `OverlayReconciler` as a transport-neutral coordinator over the existing
membership view, topology policy, direct-peer registry, and Link connector.

- Desired neighbors come only from the accepted signed membership snapshot and
  existing deterministic topology policy.
- Observed neighbors come only from usable authenticated `PeerSession` values.
- Terminal callbacks remove a binding only with compare-and-remove identity,
  preventing a stale callback from deleting a replacement session.
- The lexicographically smaller node ID owns each connection attempt, so both
  peers cannot independently win the same edge by default.
- The connector is an explicit adapter to the normal Link candidate race and
  handshake path. A descriptor never authenticates a peer.
- A returned session must be control-ready and match the current member ID and
  public key before admission. Duplicate authenticated sessions lose admission
  and are closed.
- Failures use bounded exponential retry/backoff and bounded concurrent
  attempts. Retries are cancelled when the edge is no longer desired or
  authorized.
- Physical loss publishes a new direct adjacency set and withdraws that peer
  from local route inputs. Signed membership bytes remain unchanged.

## Alternatives rejected

Session resumption or path migration would require a new authority, epoch,
replay, and work-safety contract and remains outside this checkpoint. Changing
the topology policy to force a three-peer transit chain would contradict the
accepted v1 policy, which is full mesh for at most three members. A central
daemon, relay, HTTP endpoint, UI action, or human invitation is not needed for
this local reconciliation boundary and is excluded.

## Consequences and evidence

The API remains transport-neutral and can be used by a runtime that retains
lawful signed descriptors. It does not invent discovery or provide Internet
reachability. Deterministic tests cover stale callbacks, duplicate attempts,
bounded backoff, and stable topology. A real three-member native-QUIC test
covers initial adjacency, abrupt loss, route withdrawal, automatic rejoin,
fresh session identity, and unchanged signed membership.
