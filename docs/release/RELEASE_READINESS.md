# Release readiness — v0.1.0-alpha.1 proposal

Draft only; no tag or release has been created.

## Evidence boundary

The repository contains automated local/two-process evidence for authenticated
QUIC, control readiness, liveness, candidate selection, and the bounded demo
request/result. Two-machine status remains `REQUIRES_PHYSICAL_VALIDATION` until
the procedure in `docs/demo/FIRST_DEMO.md` is executed and recorded.

## Must remain explicit

NAT traversal, PCP, NAT-PMP, UPnP, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, path migration, reconnection, resumption,
physical IPv6/public IPv4, all-firewall operation, packaging, GUI, and
production Synesis cooperation are not included claims. See `DEFERRED.md`.

## Security and release checks

Before any authorized release, run strict verification, deferred-register
validation, secret/path scan, and the physical scenarios. Do not commit demo
identities, descriptors, keystores, passwords, access tokens, or machine data.
