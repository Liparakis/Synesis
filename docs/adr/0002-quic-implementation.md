# ADR-0002: Netty 4.2 native QUIC adapter

- Status: ACCEPTED FOR VALIDATION
- Date: 2026-07-20
- Candidate version: `4.2.16.Final`, subject to dependency-resolution verification

## Decision

Use Netty 4.2's `netty-codec-native-quic` behind an internal adapter for the first real transport slice. Do not expose Netty types in public APIs. Disable 0-RTT for v1.

## Evidence

Netty's official 4.2 API exposes `QuicChannel`, `QuicStreamChannel`, connection bootstrap, stream shutdown, and path events. Netty's official downloads list 4.2.16; the API documents path events including peer migration. The native codec uses platform-specific native packaging, which must be verified on supported operating systems.

Sources:

- [Netty downloads](https://netty.io/downloads.html)
- [Netty QUIC package API](https://netty.io/4.2/api/io/netty/handler/codec/quic/package-summary.html)
- [Netty QuicChannel API](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicChannel.html)
- [Netty QuicStreamChannel API](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicStreamChannel.html)
- [Netty QuicPathEvent API](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicPathEvent.html)

## Alternatives

- Jetty 12.1: maintained and Java-oriented, but its documented QUIC path is primarily an HTTP/3 stack wrapping native Quiche; using it would pull the public design toward HTTP semantics that Synesis Link does not need.
- Netty incubator QUIC: rejected because the official repository is archived as of 2026-05-08; the maintained 4.2 line is preferable.
- Direct Cloudflare Quiche FFI: rejected for the first slice because it creates a custom Java/native binding and lifecycle surface.
- JDK HTTP client: rejected because Java 25's standard HTTP client is not a raw QUIC session API.

## Risks and validation

Native access flags, BoringSSL/Quiche packaging, Java 25 compatibility, supported OS classifiers, path migration, shutdown, and license inventory require build and two-process validation. If native packaging cannot meet the documented platform target, reopen this ADR before public transport APIs are finalized.

## Slice 5 validation

The internal adapter now carries bounded `SLH1` envelopes over a bidirectional
QUIC stream. Version offers are transported in the envelope and select the
highest exact common version; the selected value is checked against the signed
transcript. Independent JVM processes have established sessions with
independently generated identities and serialized public connection material.
TLS trust remains intentionally insecure in these test fixtures; Synesis
identity is established by the application proof, not TLS certificate trust.
