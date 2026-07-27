# Release readiness — v0.1.0-alpha.1 proposal

Draft only; no tag or release has been created.

## Evidence boundary

The repository contains automated local/two-process evidence for authenticated
QUIC, control readiness, liveness, candidate selection, and the bounded demo
request/result. Two-machine status remains `REQUIRES_PHYSICAL_VALIDATION` until
the procedure in `docs/demo/FIRST_DEMO.md` is executed and recorded.

## Must remain explicit

Serverless direct internet connectivity, manual forwarding, router mapping,
hole punching, reconnection, physical IPv6/public IPv4, and all-firewall
operation are not included claims. STUN, TURN, relay infrastructure, hosted
rendezvous, production discovery, GUI, and production Synesis cooperation are
not product dependencies. See [`DEFERRED.md`](../agent/DEFERRED.md) and the
[`NETWORK_VALIDATION_MATRIX.md`](../operations/NETWORK_VALIDATION_MATRIX.md).

## Security and release checks

Before any authorized release, run strict verification, deferred-register
validation, secret/path scan, and the physical scenarios. Do not commit demo
identities, descriptors, keystores, passwords, access tokens, or machine data.
