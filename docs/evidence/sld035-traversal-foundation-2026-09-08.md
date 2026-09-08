# SL-D-035 traversal foundation evidence — 2026-09-08

## Scope

This evidence covers the first verified implementation slice after the Link
re-investigation and ADR-0065. It does not claim Internet NAT traversal,
physical two-network reachability, relay support, reconnect, or distributed
Synesis behavior.

Starting HEAD: `c9d8d538b47619287faf01e1aecf6bc0302d0775`.

## Implemented

- Human-mediated `synesis://join/SLO1-...` and
  `synesis://answer/SLA2-...` links. SLO1 wraps the existing signed `SYN1`
  invitation and host-signed offer; SLA2 is the joiner's signed answer.
- Session, expected-peer, durable node identity, invitation digest, expiry,
  nonce, and candidate-descriptor binding.
- RFC 8489 Binding request and mapped-address response parsing with strict
  transaction, family, length, padding, and response-size checks.
- Caller-owned `StunBindingTransport` and `StunCandidateProvider`; the provider
  owns no socket and is intended to use the same UDP socket as QUIC.
- `NettyStunBindingTransport`, installed after Link binds its UDP channel and
  before the QUIC dispatcher, with single-flight bounded request handling;
  optional `Onboarding` STUN configuration now uses it.
- `NettyTraversalExchange`, installed on the same parent channel, consumes
  signed `SLO1`/`SLA2` datagrams before QUIC, answers from the observed source
  endpoint, and forwards unrelated datagrams to QUIC; host/join onboarding now
  uses the responder/initiator roles and feeds the returned descriptor into
  the existing `CandidateRacer`.
- Bounded `TraversalCoordinator` with verified exchange admission,
  deduplicated candidate planning, expected-identity filtering, and
  deterministic winner selection.
- Stateful `Onboarding` handles keep the already-bound UDP/QUIC endpoint alive
  while the user copies SLO1, imports it on B, copies SLA2 back, and imports it
  on A. A valid second link immediately starts the existing direct race. The
  host and join handles reject reuse, and the host-side admission remains
  single-use.

## Verification

Command (the process-local JVM workaround is required by the existing host
environment and does not change repository or global settings):

```powershell
New-Item -ItemType Directory -Force C:\t\synesis-loopback-probe
$env:TEMP='C:\t'; $env:TMP='C:\t'
$env:GRADLE_OPTS='-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
.\gradlew :link:check
```

Result: `BUILD SUCCESSFUL`.

This passed strict compilation, formatting, static analysis, Javadoc doclint,
the traversal tests, human-mediated URI/binding/replay tests, embedded and
real-loopback same-socket Netty tests, the real local host/join lifecycle, and
the existing Link test suite. The generated CLI smoke test also passed the
two-process copy/paste sequence using only SLO1 and SLA2.

## Human-mediated flow and measured size

The normal commands are:

```text
synesis host
synesis join synesis://join/SLO1-...
```

`synesis host` prints `SHARE_LINK=synesis://join/SLO1-...`, keeps its bound
endpoint alive, and waits for one answer line on standard input. `synesis join`
verifies SLO1, gathers its candidates on its own bound endpoint, prints
`ANSWER_LINK=synesis://answer/SLA2-...`, and begins the direct attempt. The
answer is pasted into the waiting host process; no additional signaling step
exists. On the local interface fixture, observed URI lengths were 1,499
characters for SLO1 and 945 characters for SLA2. Both are below their parser
bounds (65,536 and 49,152 characters respectively); exact length varies with
the number and encoding of advertised candidates.

The encoded candidate types are the existing `LAN` and global `IPV6` local
interface candidates, optional `SERVER_REFLEXIVE` candidates when the caller
configures an RFC 8489 STUN endpoint, and any explicitly supplied `MANUAL` or
`MAPPED_IPV4` candidates accepted by the existing candidate policy. `RELAY`
remains reserved and is rejected for direct racing. Candidate descriptors are
signed and bounded; private key material is never embedded.

## Open gates

- No external Internet STUN-server exchange has been run. The real-socket
  STUN test uses a local Binding responder and proves mapping/port continuity,
  not public-NAT behavior.
- No rendezvous/bootstrap service is included. Humans carry the two links by
  chat, email, QR, USB, or copy/paste; Synesis has no mailbox, account,
  directory, or central signaling server. The explicit onboarding path is
  direct and invitation-bound. The older in-process compatibility API remains
  available for existing tests, while the normal CLI uses only the human-
  mediated links.
- No controlled NAT simulation or physical two-network run was available;
  localhost and unit evidence must not be labeled as Internet P2P success.
- Unsupported symmetric NAT, CGNAT, and firewall behavior remains to be
  classified by the future integration harness.
