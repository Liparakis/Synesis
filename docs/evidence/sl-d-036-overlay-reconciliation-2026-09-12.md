# SL-D-036 Checkpoint 1 — overlay adjacency reconciliation evidence

- Date: 2026-09-12
- Scope: fresh local authenticated Link reconnection after physical adjacency
  loss
- Repository: `C:\Users\Liparakis\Desktop\Synesis`
- Coordination boundary: direct repository work only; no Synesis metadata,
  coordination calls, or `.synesis` state were created

## Implementation

`OverlayReconciler` now compares the accepted signed membership/topology policy
with usable `PeerSession` bindings. Terminal sessions are removed with exact
compare-and-remove semantics, route inputs are withdrawn, and topology updates
are published from the live registry. Missing owned edges reconnect through an
explicit connector contract that must use the existing normal Link candidate,
identity, replay, and control-readiness path. Connection ownership is the
lexicographically smaller node ID; concurrent attempts are coalesced and retry
delay is bounded. Physical loss never changes signed membership.

## Verification commands and results

The host's default Gradle startup has a known Windows/JDK loopback limitation.
The documented process-local workaround was used for every command below:

```powershell
$env:TEMP='C:\t'
$env:TMP='C:\t'
$env:GRADLE_OPTS='-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
New-Item -ItemType Directory -Force -Path 'C:\t\synesis-loopback-probe'
```

| Command | Result |
|---|---|
| `\.\gradlew :link:test --tests org.synesis.link.session.OverlayReconcilerTest --no-daemon --console=plain` | PASS, 3 tests |
| `\.\gradlew :link:test --tests org.synesis.link.transport.quic.OverlayReconcilerQuicTest --no-daemon --console=plain` | PASS, real three-member native QUIC loss/rejoin |
| `\.\gradlew :link:check --no-daemon --console=plain` | PASS, strict compile/static analysis, format check, Javadoc, 89 tests, zero failures/errors |
| `git diff --check` | PASS |

The real test proves initial three-member full-mesh adjacency, abrupt endpoint
loss, direct-route withdrawal, continued reachability of the surviving peer,
automatic rejoin without a new invitation, a fresh authenticated session ID,
and byte-for-byte unchanged signed membership. Because the accepted v1 policy
uses full mesh for three members, this is not evidence of a B-required transit
chain. No Internet, NAT, VM, relay, installer, UI, HTTP, MCP, or path-migration
claim is made.

## Changed-file boundary

Production changes are limited to the Link overlay registry/reconciler and its
safe diagnostics/retry policy. Tests cover deterministic state-machine behavior
and native QUIC behavior. Documentation updates record the existing
SL-D-036 lineage, ADR-0076, protocol/security/operations boundaries, and this
evidence. The result is intended for a runtime that lawfully retains signed
candidate descriptors; it does not invent discovery or a central daemon.

## Publication

The result is committed locally after this evidence is recorded. No remote
repository is pushed, tagged, or modified.
