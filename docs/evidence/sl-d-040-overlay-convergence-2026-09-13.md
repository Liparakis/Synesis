# SL-D-040 Checkpoint 2 — bounded multi-node overlay convergence

- Date: 2026-09-13
- Starting HEAD: `f26fb03cc03eec56e2275b01bd07b53df56ad154`
- Starting commit: `Heal overlay adjacency after Link loss`
- Active capability: `SL-D-040`
- Checkpoint: 2
- Final local status: COMPLETE FOR CURRENT SCOPE
- Durable checkpoint: `docs/agent/checkpoints/CP-0934.md`
- Remote publication: none

## Scope and authority

This checkpoint continued the existing SL-D-040 overlay above authenticated
Link `PeerSession` adjacencies. It did not redesign Link, change membership
authority, add a daemon or HTTP plane, add a hosted relay, or mutate source
`.synesis` state. The signed membership snapshot remained the only membership
authority. A physical session loss changed local route inputs, not membership.

The deferred-register review kept SL-D-035 paused for controlled/physical
traversal evidence. VM/process-isolated churn, NAT/Internet traversal, session
resumption, QUIC path migration, long soak, randomized chaos, installer work,
and UI redesign remain outside this checkpoint.

## Source-derived topology policy

`link/src/main/java/org/synesis/link/overlay/OverlayTopologyPolicy.java` defines
the result rather than relying on visual balance:

| Source fact | Value |
|---|---|
| Full-mesh threshold | `DEFAULT_FULL_MESH_THRESHOLD = 3` |
| Circular shortcut distance | `DEFAULT_SHORTCUT_DISTANCE = 2` |
| Intended maximum degree | `MAX_DEGREE = 4` |
| Ordering | ascending stable node ID |
| Larger-group neighbors | circular offsets `+/-1` and `+/-2` |

For N=4 and N=5 the circular offsets still cover every other member, so those
populations are effectively full mesh despite using the larger-group branch.
N=6 is the smallest population with an intentionally non-direct valid pair.
The deterministic acceptance asserts effective full mesh for every smaller N
and derives the N=6 graph from the production policy at runtime.

Using sorted symbolic members `m0` through `m5`, the expected undirected edges
are:

```text
(m0,m1) (m0,m2) (m0,m4) (m0,m5)
(m1,m2) (m1,m3) (m1,m5)
(m2,m3) (m2,m4)
(m3,m4) (m3,m5)
(m4,m5)
```

That is 12 edges and degree 4 at every member. The opposite pairs
`(m0,m3)`, `(m1,m4)`, and `(m2,m5)` are intentionally non-direct. No node is
selected as a bootstrap hub.

## Scenario 1 — physical graph convergence

`OverlayGraphQuicTest` generated six persistent `NodeIdentity` values, sorted
them by node ID, and created a signed revision-1 membership snapshot containing
all six active members. Every test node installed that same verified snapshot;
the separate membership-propagation contract remains covered by the existing
overlay tests rather than being reimplemented in this acceptance.

The test started six native QUIC listeners and one direct client connection
manager per node. The server uses the normal Link handshake, expected-member
key checks, replay guard, liveness settings, and application-stream binding.
The observed live-session edge set was asserted equal to the production-policy
edge set above. It also asserted all six degrees were 4, maximum degree was at
most 4, and the graph remained unchanged during a stability interval after
rejoin. Duplicate or unwanted bindings therefore could not be hidden by a
mere all-to-all communication check.

The graph acceptance is six native QUIC endpoints in one JVM. It is a runtime
transport test, not a topology-object-only unit test. It is not six independent
OS processes; the existing `NettyQuicLoopbackTest` separately preserves the
Link two-process boundary, and process-isolated multi-node churn is reserved
for Checkpoint 3.

## Scenario 2 — real peer transit and E2E confidentiality

The test selected the first source/destination pair that the normal route
selector returned as a non-direct route. It asserted:

- both nodes are active members of the signed snapshot;
- the destination is absent from the source's live direct-neighbor set;
- `OverlayRouteSelector` returned `PEER_TRANSIT` with a two-hop path;
- the route was not injected into the selector by the test;
- the source forwarded the key-agreement record and encrypted data through
  actual authenticated application streams;
- the destination decrypted and delivered the original plaintext; and
- the selected transit node observed the opaque envelope bytes but had no E2E
  session for the source/destination pair and could not recover the plaintext.

The original frame was sent again after recovery and was rejected by the
existing duplicate guard. Hop consumption remained enforced by the existing
`OverlayForwardingFrame`/`OverlayForwarder` path.

## Scenario 3 — meaningful abrupt transit loss

The selected two-hop route identified a real transit node. The test terminated
that node through an abrupt transport shutdown path: it did not call the
reconciler's graceful close or `PeerSession.closeGracefully`. Its active client
connections, accepted QUIC channels, server UDP channel, and event-loop group
were stopped so the surviving sessions observed transport terminality.

The surviving nodes then proved:

1. the stale live binding disappeared;
2. `ADJACENCY_LOST` and `ROUTE_WITHDRAWN` diagnostics were emitted;
3. signed membership still contained the failed node;
4. the surviving observed graph converged to the production-policy induced
   subgraph after removing that member;
5. route inputs and signed topology advertisements were republished through
   the remaining authenticated peers;
6. an already-existing alternate peer-transit path was selected without the
   dead node and delivered a new encrypted payload;
7. only the deterministic lower-node-ID owner recorded the bounded reconnect
   failure/retry for the failed edge; and
8. the original duplicate frame remained rejected.

This recovery shape is intentionally not forced beyond the policy: because a
surviving alternate path already existed, no new surviving edge was invented
to replace the failed transit node. The normal reconciler still attempted to
heal the policy-required edge through its bounded retry path.

## Scenario 4 — balance after failure

The expected post-failure graph was calculated by removing the killed member
and its incident policy edges from the original 12-edge set. The observed live
session graph was compared with that induced set. The resulting degree
distribution depends on which runtime-selected transit member was killed, but
every surviving degree remained at or below 4, no unexpected edge was admitted,
and all expected surviving edges were present. After same-identity rejoin the
distribution returned to six members at degree 4 and 12 edges.

## Scenario 5 — same-identity rejoin

The killed node's port and `NodeIdentity` were retained. A replacement native
QUIC endpoint started at that port, used the existing signed membership and
normal Link authentication, and entered reconciliation without a manual
invitation or answer. The test observed `RECONNECT_AUTHENTICATED`, restored the
exact six-member edge set, restored the policy degree bound, verified the
replacement's live-neighbor count, and held the graph stable for 300 ms.

The reconciler tests additionally cover stale terminal callbacks after a
binding has been replaced, so an old session completion cannot remove the
fresh binding.

## Deterministic churn coverage

`OverlayReconcilerTest` covers the requested bounded cases:

- two missing desired edges with a maximum of two concurrent attempts;
- deterministic reciprocal connection ownership;
- a live peer returning during retry backoff;
- duplicate authenticated completion losing to an incumbent binding;
- a stale terminal callback not removing a replacement binding; and
- already-correct topology publishing convergence once rather than on every
  reconciliation tick.

The tests assert bounded attempt counts, cancellation/coalescing behavior,
fresh duplicate termination, and stable convergence rather than merely waiting
for eventual communication.

## Route preference and security

`OverlayProtocolTest` explicitly preserves the existing route preference:

```text
DIRECT -> PEER_TRANSIT -> ORGANIZATION_RELAY -> UNREACHABLE
```

The acceptance kept identity/key validation, signed membership checks,
expected-peer validation, replay guards, QUIC transport security, bounded
application frames, E2E endpoint encryption, duplicate suppression, and hop
limits. No IP-address-as-identity shortcut or generic proxy was introduced.

## Observability and UI/SSE boundary

The existing structured reconciliation event types explain missing desired
edges, scheduled retry, reconnect attempt/failure, authenticated adjacency,
adjacency loss, route withdrawal, and topology convergence. Normal stable
reconciliation no longer republishes or emits a diagnostic on every scheduler
tick.

The current browser/SSE projections do not expose this overlay topology
read-model or its reconciliation events. Consequently no browser claim is
made for live peer disappearance/route replacement/rejoin rendering, and no UI
or SSE redesign was added.

Local stale-origin removal is proven when the node owning the direct session
receives its terminal callback. The v1 implementation does not provide a
distributed tombstone for every cached advertisement held by a node that never
had a direct session to the failed origin; this checkpoint does not claim that
stronger global withdrawal semantics.

## Verification commands and results

The inherited Windows Gradle launch required the command-local workaround
already used by the Link tests:

```powershell
$env:TEMP='C:\t'
$env:TMP='C:\t'
$env:GRADLE_OPTS='-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
```

| Command | Result |
|---|---|
| `.\gradlew :link:test --tests org.synesis.link.session.OverlayReconcilerTest --tests org.synesis.link.overlay.OverlayProtocolTest --no-daemon --console=plain` | PASS |
| `.\gradlew :link:test --tests org.synesis.link.transport.quic.OverlayGraphQuicTest --tests org.synesis.link.transport.quic.NettyQuicLoopbackTest --no-daemon --console=plain` | PASS |
| `.\gradlew :link:check --no-daemon --console=plain` | PASS; formatting, compilation, static analysis, Javadocs, 96 tests |
| `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-deferred.ps1` | PASS |
| `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` | PASS; active task `SL-D-040`, goal revision 27 |
| `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1` | PASS; created `CP-0934.md` |
| `git diff --check` | PASS |

The first unworked Gradle launch without the workaround failed before test
execution with the host error `Unable to establish loopback connection`; it is
not counted as a code regression.

## Conclusion

Checkpoint 2 is complete for the current scope: the real native bounded
overlay converges to the source-defined non-full-mesh graph, performs lawful
peer transit with endpoint-only decryption, survives meaningful transit loss
with local withdrawal and automatic bounded reconciliation, preserves balance
and route preference, and reconverges after same-identity rejoin. Checkpoint 3
is the next local-VM/process-isolation matrix; it is not implemented here.
