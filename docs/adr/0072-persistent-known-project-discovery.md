# ADR-0072: Persistent known-project discovery index

- Status: Accepted for `SYN-054`
- Date: 2026-09-09
- Scope: local project discovery metadata and the existing local control-plane adapter

## Decision

Add one small workspace-owned `KnownProjectRegistry` backed by the existing
`AdministrativeStateLocator.applicationStateRoot()` convention. The registry
is a local index, not a daemon, database, coordinator, or project runtime.
Its default file is below the application state root and is updated only when
normal Synesis code has already validated a project through its existing
identity mechanism.

The registry persists exactly the stable metadata supported by the current
project model: project UUID, normalized project path, project `createdAt`,
`firstObservedAt`, and `lastObservedAt`. The current `.synesis/project.json`
has no authoritative display-name field, so a name is not persisted. The
registry never stores agents, WorkGroups, claims, tasks, capabilities, peers,
overlay state, diagnostics, provider state, credentials, or runtime proofs.

## Source-backed architecture

Current source has no separate long-lived global daemon or runtime discovery
service. `synesis ui` composes one project-local loopback control plane;
`ProjectApplicationService` is the project identity/metadata authority; MCP
validates and attaches to that same project location; and
`AdministrativeStateLocator` already owns the user-level `Synesis` state root.
The CLI/UI/MCP processes therefore remain the registry's callers and file
owners through the shared registry implementation. No second process or
coordination plane is introduced.

Discovery hooks are deliberately narrow:

* successful `synesis init` observes the initialized location;
* UI/coordination server startup observes the serving location and may mark
  that current project live in the in-memory response only;
* valid MCP startup observes the attached location before entering the stdio
  loop.

The ten-tool MCP catalog is unchanged. MCP is an observation source, never
the registry authority.

## Identity and live-state behavior

`ProjectApplicationService.ProjectLocation.projectId()` is the identity. A
remembered path is not identity. Reading the registry validates an available
path using the existing project locator and compares the observed UUID with
the persisted UUID:

* matching identity produces `KNOWN` or `LIVE` when the current process has a
  real serving runtime for that project;
* a missing or unreadable path remains known but is `UNAVAILABLE` and never
  live;
* valid metadata with a different UUID is `IDENTITY_MISMATCH` and is not
  silently rewritten;
* no persisted field or stale file marker is used to infer liveness for an
  unrelated process.

Live IDs are an in-memory input from the current serving control plane. They
are never persisted, and closing MCP or the UI cannot remove a registry entry.
There is no automatic runtime start, relocation, deletion, or filesystem
scan. A moved project is simply unavailable at its remembered location until
an existing valid lifecycle path observes it at its new location; this slice
does not merge or repair such records.

## Persistence and failure rules

The registry uses bounded JSON and atomic replacement, with a process-safe
file lock around read-modify-write. Observation is idempotent by UUID and
does not duplicate entries. A failed registry write must not make project
initialization, MCP attachment, or the local UI pretend that the project was
not valid; the caller reports the discovery failure diagnostically while the
authoritative project operation remains governed by its existing behavior.

The control-plane `/api/v1/projects` adapter exposes only the bounded registry
view through the existing authenticated loopback boundary. It does not add a
remote endpoint or project-local coordination semantics. Inactive entries
contain discovery metadata only; project-local agents and coordination data
remain available only from that project's own authoritative runtime.

## Rejected alternatives

* A new daemon was rejected because no source-backed long-lived global owner
  exists and a second runtime would add lifecycle and security cost without
  enabling a required capability.
* Recursive disk scanning was rejected because it would invent knowledge,
  expose sensitive paths, and violate event-driven discovery.
* Persisting live flags or project-local projections was rejected because
  process disappearance and stale files cannot prove current reachability.
* Adding an MCP registration tool was rejected because valid startup already
  has project identity and the existing ten-tool contract must remain stable.
* Putting the registry in the project-local control plane was rejected because
  that server is authoritative for one project and cannot own installation-
  wide metadata without breaking isolation.

## Fitness functions

Focused tests must prove strict parsing, atomic restart persistence,
idempotence, mismatch classification, unavailable-path behavior, file-lock
boundedness, and absence of project-local state fields. Disposable lifecycle
acceptance must observe two real initialized Git projects through supported
hooks, restart the registry, stop runtime/MCP, and verify the authenticated
known-project view without changing the MCP catalog or project-local
control-plane behavior.

