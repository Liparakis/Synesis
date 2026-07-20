# ADR-0009: Zero-configuration terminal onboarding

- Status: ACCEPTED
- Date: 2026-07-20

## Decision

Keep onboarding as a thin orchestration layer above the existing Link identity,
candidate, QUIC, handshake, control, liveness, demo-work, and close APIs. The
existing `DemoCli` remains available as a diagnostic/manual fallback.

The host binds its UDP listener before gathering candidates or signing a
`synesis://join/SYN1-...` invitation. The invitation contains the signed host
candidate descriptor, session UUID, protocol version, ten-minute expiry, and a
32-byte single-use capability. The host Ed25519 identity signs the canonical
invitation bytes.

The capability is carried in the existing signed handshake transcript. A host
atomically reserves one matching capability for at most 15 seconds, releases
only pre-authentication failures, and consumes it immediately after mutual
identity proofs bind both nodes. It never creates permanent trust and never
replaces identity authentication.

Long-term identities are created once in the per-user local profile and reused;
transport TLS material is generated ephemerally per host session and deleted at
cleanup. QR output is an optional compact Unicode rendering of the exact
copyable link; the link remains authoritative when the terminal is too narrow.

## Consequences

No identity, descriptor, port, address, fingerprint, TLS keystore, or protocol
argument is required for the normal source-run path. Direct LAN connectivity,
packaging, rendezvous, relay, traversal, and physical onboarding evidence remain
separate concerns.

## Reopen when

Packaging is promoted from `SL-D-024`, direct connectivity requires traversal or
relay, or a future protocol version needs an incompatible invitation format.
