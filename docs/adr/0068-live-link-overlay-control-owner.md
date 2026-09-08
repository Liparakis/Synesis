# ADR-0068: Bounded live Link/overlay owner for the local control plane

- Status: ACCEPTED FOR SYN-052 CONTINUATION
- Date: 2026-09-08
- Scope: live Link sessions and overlay read-model composition for the local control plane
- Architecture mode: EVOLUTION

## Context

ADR-0067 deliberately kept `LinkNetworkProjection` free of transport
lifecycle. The control plane can therefore expose verified Link and overlay
state only when an existing runtime supplies a long-lived owner. The current
CLI has `Onboarding` and a durable coordination host, but no signed membership
authority lifecycle. Treating an invitation, a project allowlist, an address,
or an unsigned peer as membership would violate ADR-0066.

The existing onboarding handles are bounded one-shot operations: their normal
commands wait for `PeerSession` termination and then close the endpoint. That
is correct for the terminal CLI flow but cannot own a live control-plane
connection.

## Decision

Add one small in-process `LinkRuntimeOwner` at the workspace control boundary.
It composes the existing `Onboarding`, authenticated `PeerSession`, overlay
membership/topology views, propagation components, direct-peer registries,
and `OverlayPeerSessionBridge`. It supplies one consistent
`LinkNetworkProjection.LinkState` snapshot and implements the control-plane
onboarding operation seam.

The owner has two valid modes:

- with an explicitly supplied, signature-verified `OverlayMembershipSnapshot`,
  it binds authenticated sessions that are current project members, installs
  the existing overlay forwarding/propagation callbacks, and publishes signed
  local topology observations;
- without that snapshot, it may retain authenticated physical sessions for
  Link visibility but reports the overlay as `UNCONFIGURED` and installs no
  overlay forwarding authority.

The CLI uses the second mode until a separately scoped membership-authority
source exists. It must not create a self-signed one-member project snapshot or
derive membership from onboarding. Tests may supply a real signed snapshot
from an explicit authority identity to prove the full projection path.

The control HTTP adapter receives a narrow operation interface. Its existing
`Onboarding` constructor uses a compatibility adapter with the original
one-shot close behavior. The live owner uses new Link-owned retained-session
methods that return after authentication and leaves endpoint/session cleanup
to the owner. Invite, answer, expiry, identity, replay, and single-use
validation remain inside `Onboarding`.

## Lifecycle and failure behavior

- A session is admitted to the owner only after Link authentication and
  control readiness; configured membership rejects an authenticated node that
  is not in the current signed snapshot.
- Replacing a peer never lets the old session's terminal callback remove the
  newer session's binding.
- Session terminal completion removes physical and overlay bindings and emits
  the existing safe Link event surface.
- Owner shutdown closes retained onboarding handles and sessions, unregisters
  the read source, and leaves no Link endpoint owned by the HTTP handler.
- Relay state remains the existing safe disabled projection until an explicit
  live relay client is supplied; no relay identity or connection is invented.

## Rejected alternatives

- Deriving membership from `ProjectConfig`: it is a local authenticated-peer
  allowlist, not the signed membership authority defined by ADR-0066.
- Keeping one-shot onboarding in the HTTP handler: the handler would close a
  healthy session as soon as the command returned and could not provide live
  network state.
- Adding a second daemon or persistence store: the owner is an in-process
  composition seam and must not create a competing runtime authority.
- Adding reconnect, path migration, authority succession, or relay lifecycle:
  those remain separately bounded capabilities.

## Validation and invalidation

The owner must pass focused tests for retained HTTP invite/join/answer
sessions, verified membership versus direct adjacency, safe snapshot routes,
session replacement, and clean shutdown. The decision must be revisited if
the CLI gains a durable membership-authority lifecycle, a live relay client,
or requirements for reconnect/path migration.
