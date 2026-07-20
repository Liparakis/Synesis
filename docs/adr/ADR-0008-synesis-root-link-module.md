# ADR-0008: Synesis root with Link transport module

## Status

Accepted — 2026-07-20.

## Decision

Make the repository root `Synesis` and place the existing Synesis Link
transport implementation in the `link/` Gradle subproject. The root Gradle
build remains a modular monolith and delegates verification to `:link`.

## Context

The current repository is named and documented as `synesis-link`, but Link is
only the transport/session layer of the wider Synesis system. Keeping it at
the root makes the repository boundary and future ownership misleading.

## Constraints and evidence

- The existing implementation is a coherent Java 25 Gradle project.
- No second Synesis module exists yet, so no new product module is invented.
- Link must retain its public transport-independent API and internal QUIC
  boundary.
- The existing strict build, dependency verification, locks, tests, and
  durable agent state must remain runnable from the repository root.

## Alternatives rejected

- Renaming only: rejected because it would leave Link files and build identity
  at the Synesis root.
- Separate repository: rejected because the user needs Synesis and Link in one
  working tree and no independent release boundary is evidenced yet.
- Placeholder sibling modules: rejected as speculative scope.

## Consequences

- Root `settings.gradle.kts` includes `:link`; root `check` delegates to it.
- Link sources, tests, module build script, and module lockfile live under
  `link/`.
- Root documentation and agent state remain repository-level and name Link as
  the current implemented module.
- Commands use `./gradlew clean check`; Link-only tasks use
  `./gradlew :link:<task>`.

## Fitness function

`gradlew.bat clean check --dependency-verification=strict` must pass from the
repository root, and the root `check` task must execute `:link:check`.
