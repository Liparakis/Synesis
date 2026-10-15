# ADR-0097 — Two networks: an answer link for hole punching, and a router port forward

## Status

Accepted on 2026-10-15 by the repository owner. Built on 2026-10-15; see "As built".

It extends ADR-0095 (the compact invitation) and implements parts 1 to 3 of ADR-0065 on the
compact invitation instead of the two-link exchange ADR-0065 described. It removes the `SYN1`,
`SLO1` and `SLA2` formats under ADR-0088.

Claims are marked VERIFIED, USER-STATED or DERIVED.

## Context

Two computers on different home networks could not connect. Each home router drops traffic its
own side did not start, the invitation named only addresses the host saw on its own interfaces,
and nothing ever made the host's router let the joiner in. (VERIFIED in the two-router lab
described under "As built".)

The repository owner decided: no meeting-point server for now; both users paste the links the other
sends; the relay stays out of scope; STUN may use a public server, Cloudflare by default and
changeable. (USER-STATED, 2026-10-15.)

The old two-link exchange (`SLO1` invitation, `SLA2` answer, wrapping the older `SYN1` invitation)
was produced only by tests. `synesis host` and the browser made `SLB3` invitations. The exchange
also never sent anything from the host toward the joiner, so it could not have crossed two routers
either. (VERIFIED by reading.)

Hole punching was then tried and measured. On IPv6 behind two stateful firewalls it works. On IPv4
behind two NAT routers of the common Linux kind it fails and cannot be made to work by ordering
alone: the joiner's first packets reach the host's router before the host has sent anything; the
router records them as a connection to its own address and port; and when the host then sends
toward the joiner, the router cannot reuse that port and translates it to another, so the joiner's
packets keep arriving at a port nothing listens on. Whichever side sends first does the same to the
other side's router. Getting through needs both sides to send at the same instant, which pasted links
cannot arrange, or packets whose time-to-live ends between the two routers, which Java cannot set.
(VERIFIED: the router's connection table showed the joiner's entry for the router's own port, and
its counters showed the host's replies leaving from another port.)

## Decision

1. **The old formats are removed.** `SYN1`, `SLO1`, `SLA2`, the signed traversal offer and answer,
   the traversal coordinator and its UDP exchange, and every path that read them, including the
   control plane's `connect` command and the daemon's answer routing by return target.
2. **STUN.** The product asks a STUN server for each machine's public address: `stun.cloudflare.com:3478`
   unless `SYNESIS_STUN_SERVER` names another `host:port` or says `off`. It runs on the same socket
   as the session, as ADR-0065 requires. Tests and the CLI processes they start run with it off.
   Project sync does not use it.
3. **The answer link, `SLA3`.** Joining an invitation shows `synesis://answer/SLA3-...`: the
   invitation's session and up to eight of the joiner's endpoints, encoded like the invitation's.
   It is not signed. It authorizes nothing: the session is still authenticated by the invitation's
   capability and both identities; a forged answer can only make the host send small packets to the
   addresses it names.
4. **The host punches.** Given an answer for its own invitation (pasted into `synesis host`, or the
   browser's answer field, or opened as a `synesis://answer/` link), the host sends a one-byte
   datagram from its listening socket to each of the joiner's endpoints four times a second for
   thirty seconds.
5. **The joiner keeps dialing** until the invitation expires, one race at least every five seconds,
   and says `WAITING_FOR_HOST` after the first race fails. Project sync, which shows no answer link,
   makes one race.
6. **The host may ask its router to forward its port.** On the user's choice only
   (`synesis host --open-router-port`, or a checkbox in the browser), the host asks the router over
   UPnP IGD to forward the public UDP port to its listening port, with a one-hour lease, and puts the
   router's public address in the invitation. The forward is removed when the host closes. A router
   that does not answer or refuses, or whose own address is private or carrier-shared, is reported
   and the invitation is made without it. The router's replies are bounded, parsed without DTDs,
   and its description is fetched only from the address that answered the search.

## Alternatives rejected

- **Keep `SLO1`/`SLA2` and add punching to them.** Two formats for one job, and a heavier link to
  paste; the compact invitation plus a short answer carries the same routing information.
- **Make the joiner wait for the host's packets before dialing.** The host's packets then reach the
  joiner's router first and do to it what the joiner's packets did to the host's router.
- **Synchronize both sides on the clock.** Clock skew between two home PCs is far larger than the
  time a packet takes between them.
- **NAT-PMP and PCP.** They need the router's address, which the JDK cannot read from the routing
  table; UPnP finds the router by asking the network. Deferred, not rejected.
- **A forward on the joiner's router.** The host would still dial or punch toward the joiner after
  the joiner had dialed it, and the host's router would be in the state described above.

## Consequences

- Across two networks a session needs one of: IPv6 on both sides, or a router that lets the host's
  forward be opened, or NATs that do not keep the joiner's early packets against the host's port.
  Two real home networks of the last kind connected over IPv4 with the answer link (VERIFIED,
  2026-10-15). Between two routers that do keep them, as the lab's Linux routers did, IPv4 without
  a forward does not connect and the joiner waits until the invitation expires.
- Every invitation and every join asks Cloudflare's STUN server unless configured off. It learns the
  machine's public address and nothing else. (DERIVED.)
- A build from before this change cannot join or be joined by one from after it. (DERIVED.)
- A forward changes the user's router until the host closes, or for an hour after a crash.
- The control plane's `join` returns at once with the answer link and dials in the background;
  `connect` no longer exists.

## As built

In `link`: `CompactAnswer`, `CompactEndpoints` (shared by the invitation and the answer),
`OnboardingEndpoint.punch` and `openRouterPort`, the joiner's repeated race in `Onboarding`, and
`org.synesis.link.portmap` (`UpnpGateway`, `RouterPortMapping`). In the CLI, `synesis host` reads
answer links from its input and takes `--open-router-port`. In the control plane and the browser, the
answer field, the background join, and the router-port checkbox.

Lab: two NAT routers in Podman on the WSL machine, each dropping unsolicited traffic, one peer
behind each, a STUN responder between them, scripts in `D:\SynesisAcceptance\link-vm\natlab`.

| Case | Result |
| --- | --- |
| IPv4, answer pasted | No session; the host's router held the joiner's entry for its own port |
| IPv6 behind both firewalls, answer pasted | Session, `IPV6/IPV6` |
| IPv6 behind both firewalls, no answer | No session |
| IPv4, `miniupnpd` 2.3.9 on the host's router, `--open-router-port`, no answer | Session on the first race |

Between two physical computers on two home networks: the joiner waited, the host's user pasted the
answer, and they connected over IPv4 (`docs/agent/CURRENT.md`, 2026-10-15).

Not run: a real router's UPnP service; NAT-PMP and PCP.
