# SL-D-040 Checkpoint 3A Docker lab evidence — 2026-09-13

## Result

Checkpoint 3A passed for the bounded Docker process/network lab scope after a
two-cycle run. The evidence run is:

- Run root: `D:\SynesisDocker\p2p-lab-3a\run-20260913-181647`
- Command: `powershell -ExecutionPolicy Bypass -File scripts/p2p-lab/run.ps1 -Cycles 2 -SkipBuild`
- Image: `synesis-p2p-lab:3a`
- Image manifest: `sha256:22978dc50c2945dde268b546763859442e31a54468dfd54328943e0c79b78135`
- Private network: `synesis-p2p-3a-20260913-181647`
- Result record: `evidence/result.json`

The host substrate is Windows 11 with Docker Desktop's Linux engine and its
Docker-managed WSL2 kernel. This is not native Ubuntu, KVM, physical NAT, or
Internet traversal evidence. No containers or `synesis-p2p-3a-*` network
remained after the run's bounded cleanup.

## Verified protocol and topology evidence

- Six independent JVM processes ran in one private Docker bridge network.
- The lab created and installed one verified signed six-member membership
  snapshot: project `9e2bc5bd-3d4b-45de-8259-05cff53bcb11`, digest
  `65d76da38a031555e63788ac6c5162831feb51550f277d75fc93101f8c1859a6`.
- The deterministic policy produced 12 undirected edges and degree 4 for each
  member (`expected-edges.json`).
- All 12 initial edges were bootstrapped through the normal compact `SLB2`
  host/join path and reached the authenticated first-contact evidence stage.
- Initial convergence, both container rejoin stages, both partition recovery
  stages, and both simultaneous reconnect stages recorded six nodes with four
  live neighbors each.
- Initial, alternate, and impairment routes are recorded as
  `PEER_TRANSIT` in the cycle route JSON files. The final snapshot records the
  encrypted netem payload delivered after recovery.
- Each cycle applied a reversible `tc netem loss 100%` partition to one node,
  then cleared it and performed the explicit stale-session recovery boundary:
  graceful same-identity process replacement one node at a time, signed
  descriptor refresh, and topology reconvergence.
- Each cycle applied `50ms` delay, `10ms` jitter, and `5%` loss through
  `tc netem`, then verified an authenticated peer-transit delivery.
- Each cycle hard-killed a transit node, verified withdrawal and alternate
  transit, replaced the container with the persisted identity, partitioned a
  node, exercised a two-node failure, and verified simultaneous reconnect.

## Source and product boundary

The harness is test-only (`link/src/test/.../ProcessLabNode.java`) and uses
the existing signed identity, candidate descriptor, SLB2 onboarding, QUIC,
authenticated `PeerSession`, overlay forwarding, topology propagation, and
`OverlayReconciler` classes. It supplies the explicit signed membership
snapshot and refreshes signed descriptors as a controlled lab operation.

The shipped CLI still does not provide membership authority to
`LinkRuntimeOwner`, and it does not automatically compose the production
descriptor reconnect path. The lab therefore does not claim shipped-CLI
automatic endpoint discovery, live-JVM socket rebinding after arbitrary
namespace detach/reattach, native Linux/KVM behavior, or physical/network-
traversal behavior.

## Verification

- `:link:compileTestJava` with the Linux x86_64 native QUIC classifier: PASS.
- PowerShell parser validation for `scripts/p2p-lab/run.ps1`: PASS.
- Two-cycle Docker run: PASS; `failure.txt` absent.
- Exact disposable container/network cleanup: PASS.
- Docker data was not pruned, and no source-checkout `.synesis` state or
  Synesis coordination state was created.
- Durable checkpoint: `docs/agent/checkpoints/CP-0941.md`.
