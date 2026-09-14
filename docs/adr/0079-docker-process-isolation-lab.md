# ADR-0079: Docker process-isolation lab for SL-D-040 Checkpoint 3A

- Status: accepted for the bounded local lab slice
- Date: 2026-09-13
- Scope: `SL-D-040` Checkpoint 3A only

## Context

Checkpoint 2 proves the bounded six-member overlay in one JVM and retains a
separate-JVM Link regression, but it does not prove six independent operating
system processes, container replacement, namespace partition, or repeatable
network impairment. The current workstation is Windows 11 with Docker Desktop's
Linux engine and its Docker-managed WSL2 kernel. It has no configured native
Ubuntu target, SSH target, or exposed KVM capability.

The product source intentionally keeps project membership separate from Link
authentication. The normal CLI supplies no signed membership authority, and
`LinkRuntimeOwner` currently retains authenticated sessions but does not wire
`OverlayReconciler` to a production direct-descriptor reconnect connector.
Treating a local allowlist, invitation, or Docker control file as membership
would invalidate the acceptance boundary.

## Decision

Use a disposable repository-local Docker lab under `scripts/p2p-lab/` with one
JVM process per container. The lab runner may create one explicit, signed
`OverlayMembershipSnapshot` for the six durable node identities and may pass
that snapshot to a test-only process adapter. The adapter must use the existing
Link identity, handshake, candidate, liveness, `PeerSession`, overlay
forwarder, topology, and reconciler classes; it must not replace them with a
mock transport or unsigned membership check.

The twelve first desired edges are bootstrapped through the normal compact SLB2
host/join path. Authenticated signed candidate descriptors are then retained by
the lab adapter for the existing bounded direct connector used by
`OverlayReconciler`. Endpoint refresh after container recreation is an explicit
lab operation that obtains the replacement node's newly signed descriptor and
updates the adapter; it is evidence of a controlled descriptor-refresh seam,
not a claim that the shipped CLI discovers changed endpoints automatically.

The harness controls only disposable containers and a private Docker network.
It may use bounded `tc` impairment inside the lab container when the Docker
engine grants the capability, and it must record the exact result or mark the
impairment scenario unavailable. The verified partition scenario uses
reversible `tc netem loss 100%`, then clears the cut and performs graceful
same-identity process replacement one node at a time before refreshing signed
descriptors. A raw Docker namespace detach/reattach of a live JVM is not
treated as an equivalent automatic-rebind claim. Hard kills and traffic cuts
leave the signed membership unchanged. All evidence must distinguish
Docker/WSL2 process isolation from native Linux, KVM, physical NAT, Internet
traversal, or production CLI acceptance.

## Consequences

- Six JVMs, not six in-process endpoints, become the primary Checkpoint 3A
  topology evidence.
- The lab can prove process/container churn and network namespace failure while
  preserving the existing authenticated protocol boundary.
- The lab does not promote membership authority, endpoint discovery, relay, or
  physical traversal into the shipped product.
- A failed or unavailable production seam is reported as an incomplete
  checkpoint; the harness must not compensate by bypassing signed state.
- The Docker image and run artifacts are disposable and must be cleaned by the
  harness. Durable node identities and signed membership are kept only under
  the explicitly selected run directory, never in the source checkout's
  `.synesis` state.

## Verification boundary

The final evidence must include the exact packaged build and image digest,
identity and membership digests, observed 12-edge/degree-4 graph, authenticated
transit result, kill/partition/replacement/reconnect transitions, impairment
parameters, repeat-cycle summary, cleanup result, and the KVM/native-Linux
limitation. The verified two-cycle record is
`docs/evidence/sl-d-040-checkpoint-3a-docker-2026-09-13.md`. It must not claim
more than Docker-on-WSL2 local evidence.
