# ADR-0088 — Internal APIs and formats may be redesigned before the first release

## Status

Accepted on 2026-10-10.

## Context

`AGENTS.md` lists the public Java API of a module, wire formats, and persisted
project state among the published contracts, next to the MCP and CLI surface.
A breaking change to a published contract needs an ADR and explicit human
approval.

Synesis has not had a first public release. No module is published as a
library: the build applies no publishing plugin and declares no `module-info`.
The only consumers of one module's public types are the other modules in this
repository, and the only producers and readers of its wire and persisted
formats are builds of this repository. The 2026-10-10 survey for the codebase
remaster found dead public surface, constructors kept only for tests, classes
in packages that no longer match their responsibility, decoders that still
accept superseded prerelease versions, and equivalent decoders that disagree on
bounds and trailing bytes. Under the deprecate-then-remove rule none of that
can be cleaned up, although nothing outside this repository depends on it.

## Decision

Until the first public release:

- Internal Java APIs, wire formats, and persisted-state representations may be
  redesigned when doing so materially improves correctness, simplicity,
  maintainability, or performance.
- Such a change migrates every current producer and consumer in the same
  change, updates the relevant tests and documentation, and preserves current
  cross-harness interoperability: sessions driven from Claude Code and from
  Codex keep working with each other and with the same project.
- Backward compatibility with superseded prerelease versions is not required.
  A decoder need not keep reading a version that no current producer writes.
- Regression fixtures are kept for the current canonical format of each wire
  and persisted form, not for every historical version.
- The MCP tool catalog and the CLI surface stay stable. They are what the two
  harnesses and their users see, so a change to either is a cross-harness
  change and is outside this ADR.

The repository owner decided this on 2026-10-10 for the remaster recorded in
`docs/plans/codebase-remaster-2026-10-10.md`.

## Consequences

- Dead public surface, test-only overloads, and readers of superseded format
  versions can be deleted, and types can move to the package that owns their
  responsibility, without a deprecation cycle.
- A format change is not behavior-preserving. Each one is stated as a format
  change in its commit and replaces the fixture for that form.
- Durable state written by an older prerelease build may stop loading after
  such a change. Disposable acceptance projects are recreated, not migrated.
- This ADR must be revisited before the first public release. From that
  release on, the contract rules in `AGENTS.md` apply in full again.
