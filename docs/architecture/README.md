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

Remote networking, rendezvous, relay fallback, hole-punching, and hosted
coordination remain deferred. The [deferred register](../agent/DEFERRED.md)
controls those claims.
