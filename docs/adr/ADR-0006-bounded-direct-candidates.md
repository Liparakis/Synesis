# ADR-0006: Bounded direct candidate gathering and racing

Status: accepted

## Context

Synesis Link needs a small direct-connectivity layer without turning SL-008 into
a generic ICE, relay, or reconnection subsystem. Candidate providers can be
slow, unavailable, or unsafe to trust as raw endpoint input. A transport
connection also cannot be treated as a usable Synesis session until identity
and control readiness succeed.

## Decision

Ship only manual and local-interface providers. Gather providers concurrently
under explicit provider, candidate, worker, and total-time bounds. Normalize
addresses before use: canonicalize IPv4-mapped IPv6, reject unspecified,
multicast, disallowed loopback/private scopes, ambiguous link-local addresses,
and relay candidates, then deduplicate while retaining the best priority.

Generate same-family compatible pairs and rank them deterministically. Race a
bounded number of pairs with bounded concurrency, staggering, per-attempt and
global deadlines. A supplied attempt is successful only when it returns an
authenticated `PeerSession` for the expected remote identity with reciprocal
control readiness. Select one winner atomically, cancel other attempts, and
close late successful losers. Diagnostics are bounded and endpoint-redacted.

## Rejected alternatives

PCP, NAT-PMP, UPnP, STUN, TURN, relay transport, hole punching, DHT discovery,
automatic reconnection, and path migration are not implemented or implied by
this decision. They require separate threat models, operational policy, and
cross-machine evidence.

## Invalidation conditions

This decision should be revisited if the product requires public reachability,
NAT traversal, relay allocation, migration across network paths, or automatic
reconnection. Those features must preserve the authenticated control-ready
winner boundary and add their own bounded resource and security controls.
