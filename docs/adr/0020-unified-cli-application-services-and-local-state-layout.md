# ADR-0020 — Unified CLI, Application Services, and Local Project State

- Status: Accepted for SYN-009A
- Date: 2026-07-22
- Mode: EVOLUTION

## Decision

Keep the existing four-module modular monolith and make `:cli` its only public
composition root. `:workspace` becomes a library exposing small application
services for project lifecycle, synchronization, constraints, guardrails, and
hooks. Services return structured values and do not depend on Picocli, console
streams, or process exit.

Project-local state is discovered from `.synesis/project.json`. Shareable
metadata and shared records are separate from `.synesis/local`, which contains
identity, profile records, provider state, runtime files, and machine-specific
values. `project.json` contains only bounded schema metadata, a UUID, and a
timestamp.

## Evidence and constraints

- VERIFIED: the current build has `:link`, `:project-record`, `:workspace`, and
  `:cli`; existing workspace process tests prove the orchestration path.
- USER-STATED: one public `synesis` command, project discovery, `synesis init`,
  and no provider/package work in SYN-009A.
- DERIVED: a new Gradle module or network service would add coordination without
  independent ownership, scaling, or release evidence.
- UNKNOWN: adoption scale, external deployment, and physical network topology.
  These remain reversible and do not change this local modular baseline.

## Consequences

The old `synesis-workspace` and `synesis-project-record` application
distributions are retired without unreleased compatibility aliases. Protocol
classes and project-record domain/storage types remain where they are. Provider
installation and portable distribution stay deferred to SYN-009B and SYN-009C.

## Fitness functions

- `:workspace:architectureCheck` rejects provider imports from guardrail and
  reverse imports into `:project-record`.
- `:cli:installDist` is the only public distribution task.
- Project service tests cover nested discovery, malformed/partial state,
  repeated initialization, identity creation, and metadata redaction.
- Strict `clean check` remains the completion gate.
