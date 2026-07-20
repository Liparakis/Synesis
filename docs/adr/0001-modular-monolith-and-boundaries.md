# ADR-0001: One Gradle project with package boundaries

- Status: ACCEPTED
- Date: 2026-07-20

## Decision

Start Synesis Link as one Gradle project using explicit package boundaries. Keep public API types transport-independent and hide QUIC implementation types behind an internal adapter.

## Context

The contract requires a standalone library, deterministic testing, no wider Synesis concepts, and proportional architecture. No evidence supports independent deployment, scaling, ownership, or storage boundaries.

## Alternatives

Separate `api`, `core`, `quic`, `testkit`, and CLI projects would add release and dependency coordination before those boundaries have independent value. A single package would leak ownership and transport details.

## Consequences

The initial build is simpler and extraction remains possible. Dependency rules, package-info files, and public API tests become the enforcement mechanism. A module split requires a new ADR and evidence.

## Reopen when

Independent publication, native dependency isolation, incompatible runtime needs, or a measured dependency-boundary failure makes the current project materially costly.
