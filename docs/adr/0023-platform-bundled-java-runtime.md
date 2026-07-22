# ADR-0023: Platform-specific bundled Java runtime

## Status

Accepted for SYN-009C.1.

## Decision

Build each platform bundle on a compatible operating system and include a
minimal `jlink` runtime beside the Java application. The launcher resolves the
runtime relative to itself and never requires `JAVA_HOME`, Gradle, or source
paths.

## Context and alternatives

The existing Gradle Application distribution requires a system Java runtime.
Requiring system Java makes a clean-machine claim false. Reusing one runtime
across operating systems or architectures is also invalid. A new native
launcher is unnecessary: a short platform shell/batch launcher is sufficient.

## Consequences

The bundle is larger but self-contained. Native QUIC libraries must be built
and packaged per target. jdeps output is the source of the module list; the
runtime is not aggressively stripped beyond verified jlink options.

## Invalidation

Reconsider only if a supported packaging service supplies a smaller,
equivalent, reproducible runtime with the same clean-machine guarantees.
