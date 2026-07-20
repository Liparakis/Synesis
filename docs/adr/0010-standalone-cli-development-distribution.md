# ADR-0010: Standalone CLI and development distribution

## Status

Accepted for the SL-013 development-distribution slice.

## Context

`link` currently contains the zero-configuration terminal launcher. That
launcher combines handwritten parsing, terminal output, QR presentation, exit
mapping, identity bootstrap, and Netty onboarding lifecycle. This makes the
source-run command Gradle-only and prevents a generated launcher from owning a
stable command contract. Link also contains package-private Netty orchestration
that must not become CLI code.

The required physical source-run onboarding evidence is still pending. That is
a verification gate for completion, not a reason to alter protocol semantics or
claim production packaging.

## Decision

Add one outer `:cli` Gradle module with a one-way dependency on `:link`. The
module owns Picocli, command adapters, terminal rendering, exit codes, and
Gradle Application distributions. `link` never depends on Picocli or `:cli`.

Expose one small Link-owned onboarding façade in
`org.synesis.link.transport`. It owns the existing identity, invitation,
candidate, Netty, handshake, admission, liveness, work, and cleanup flow and
emits typed operational events. It does not print, parse shell arguments, or
choose process exit codes. Netty and handshake implementation types remain
internal to Link.

The generated distribution is a development distribution only. Signing,
installers, permanent PATH changes, native images, MSI, publishing, upgrades,
rollback, and production support remain deferred under `SL-D-024`.

## Compatibility

Invitation encoding, signature, expiry, capability admission, transcript
binding, expected-identity checks, candidate policy, QUIC behavior, liveness,
work exchange, and close semantics remain unchanged. The CLI renders the exact
`shareLink()` value carried by the Link event. Existing `DemoCli` remains a
bounded diagnostic fallback until physical and launcher parity are verified.

All stable status and failure lines remain on stdout. Help, usage diagnostics,
and redacted human explanations use stderr. Numeric process exits are mapped
from typed command/facade failures rather than arbitrary exception messages.

## Consequences

The CLI becomes independently launchable and testable, while the protocol
module retains ownership of resource lifecycle and security-sensitive
orchestration. The cost is a small public event/failure surface and a staged
migration period in which the legacy source launcher remains until generated
launcher parity is demonstrated.

## Deferred boundary

This ADR does not authorize production installation, artifact signing,
permanent environment changes, native-image work, or new networking behavior.
