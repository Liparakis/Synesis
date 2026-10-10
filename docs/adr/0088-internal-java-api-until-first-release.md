# ADR-0088 — Java API between modules is internal until the first release

## Status

Accepted on 2026-10-10.

## Context

`AGENTS.md` lists the public Java API of a module among the published
contracts, next to the MCP and CLI surface, wire formats, and persisted project
state. A breaking change to a published contract needs an ADR and explicit
human approval.

Synesis has not had a first release. No module is published as a library: the
build applies no publishing plugin and declares no `module-info`. The only
consumers of one module's public types are the other modules in this
repository. The 2026-10-10 survey for the codebase remaster found many public
types and members with no caller, constructors kept only for tests, and classes
in packages that no longer match their responsibility. Under the
deprecate-then-remove rule none of that can be cleaned up, although no reader
outside this repository exists to protect.

## Decision

- Until the first release, the Java API that one Synesis module exposes to
  another is internal. A change may narrow, move, rename, or remove a public
  Java type or member when it migrates every caller in the repository in the
  same change and the build and tests pass.
- The MCP tool catalog and response shapes, the CLI surface, wire formats, and
  everything persisted under `.synesis` or the administrative root remain
  published contracts. This ADR does not relax them.
- The repository owner approved this on 2026-10-10 for the remaster recorded in
  `docs/plans/codebase-remaster-2026-10-10.md`.

## Consequences

- Dead public surface and test-only overloads can be deleted, and types can
  move to the package that owns their responsibility, without a deprecation
  cycle.
- A change that renames a type other modules import touches those modules in
  the same commit.
- This ADR must be revisited before the first release that ships a Java
  artifact for outside use. From that release on, the rule in `AGENTS.md`
  applies to the Java API again.
