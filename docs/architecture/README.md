# Architecture

Synesis is a modular monolith. `:link` owns the transport/session boundary;
`:project-record`, `:workspace`, `:coordination`, `:mcp`, and `:cli` add bounded
local capabilities around it. The root Gradle build delegates verification to
these modules.

- [Architecture baseline](BASELINE.md)
- [Package boundaries](package-boundaries.md)
- [Provider boundary](provider-boundary.md)
- [AGENTS.md contract](agents-md-contract.md)
- [Zero-touch provider maturity](zero-touch-provider-maturity.md)

Serverless direct internet connectivity, operator-exchanged invitations,
manual forwarding, optional router mapping, and coordinated hole punching are
deferred capabilities. Hosted rendezvous, STUN, TURN, relay infrastructure, and
production peer discovery are intentionally not product dependencies. The
[deferred register](../agent/DEFERRED.md) and
[network validation matrix](../operations/NETWORK_VALIDATION_MATRIX.md) control
these claims.
