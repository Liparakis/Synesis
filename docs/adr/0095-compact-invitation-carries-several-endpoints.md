# ADR-0095 — The compact invitation carries several endpoints, and only addresses a peer can use

## Status

Accepted on 2026-10-14 by the repository owner. Built on 2026-10-14; see "As built".

It supersedes two parts of ADR-0078: the single endpoint hint and the `SLB2`
revision. The rest of ADR-0078 stands. The format change is made under ADR-0088.

Claims are marked VERIFIED, USER-STATED or DERIVED.

## Context

`synesis host` on one computer and `synesis join` on another had never
connected with the installed product. Three causes were found by running it
between Windows and Linux virtual machines. (VERIFIED, 2026-10-14; the runs are
in `docs/agent/CURRENT.md`.)

- The invitation carried one endpoint, chosen by a fixed order that ranks a
  global IPv6 address above every LAN address. A joiner without IPv6 on the
  same private network had nothing to dial: `NO_USABLE_CANDIDATE`.
- The candidate provider skipped loopback addresses but not the loopback
  interface. WSL binds `10.255.255.254` there, that address sorted first, and a
  host invited its peer to an address no other machine reaches.
- The provider offered every IPv6 address of an interface. The listener is
  bound to the wildcard address, so the operating system chooses the source of
  each reply. With a stable and a temporary address in one prefix, which is the
  Windows default, it chooses the temporary one. The invitation carried the
  stable one, and the joiner dropped every reply as coming from an address it
  had not dialled.

The inviter cannot know which of its addresses the joiner's network reaches. A
better order would move the failure to another pair of machines. (DERIVED.)

## Decision

1. The compact invitation carries one to eight endpoints in the inviter's
   order of preference. The revision is `SLB3`: after the key come a count
   byte and, for each endpoint, an address-width byte (4 or 16), the address,
   and a two-byte port. Everything else, including what the host signs and how
   the session identifier is derived, is as ADR-0078 describes, with `SLB3` in
   the session domain. A repeated endpoint is rejected.
2. `SLB2` is no longer read or written. No release has shipped (ADR-0088), and
   keeping a reader for a form with one endpoint would keep its failure.
3. Selection keeps the existing order and takes one candidate of every kind
   present before a second of any kind, so a machine with more than eight
   addresses still offers a LAN address beside its public ones.
4. The joiner makes one attempt per endpoint whose address family it has, all
   at once, and keeps the first authenticated session. One wildcard-bound
   socket dials every attempt, so a pair per local address would only repeat a
   destination. The host admits one attempt; the single-use admission already
   refuses the rest.
5. The local candidate provider offers no address of the loopback interface,
   and offers an IPv6 address only when the machine sends from it to a
   neighbour in the same prefix. It finds that out by connecting a datagram
   socket, which selects a source and sends nothing.

## Alternatives rejected

- Prefer the LAN address over the global IPv6 one. It fixes two machines on
  one router and breaks a host reachable only over IPv6.
- Bind one listener per local address so that replies leave from the dialled
  address. It is the complete answer to the reply-source problem and a larger
  change to the endpoint, which also serves STUN and traversal on that socket.
  Decision 5 removes the case that was seen.
- Fall back to the legacy `SLO1`/`SLA2` exchange. It needs an answer carried
  back by hand, and `synesis host` does not produce it.

## Consequences

- A link is longer: 183 characters with one IPv4 endpoint, 7 or 19 bytes more
  per further endpoint, 376 characters at most. (VERIFIED by test.)
- A build from before this change cannot join or be joined by one from after
  it. (DERIVED.)
- More of the inviter's addresses are disclosed to whoever holds the link,
  including private ones. The link was already a bearer artifact that named
  one of them. (DERIVED.)
- `DirectConnectionResult` names the winning pair, and `PATH_SELECTED` reports
  it. Before, it reported the first pair tried.

## As built

`CompactBootstrapInvitation`, `CandidatePairs.onePerRemote`,
`LocalInterfaceCandidateProvider`, `Onboarding` and `DirectConnectionResult` in
`link`. `:link:check`, 180 tests, 0 failures on Windows. Between Windows and a
bridged virtual machine on one home network, and between Windows and a machine
without IPv6, a session was established in both directions. Two physical
computers were not used.
