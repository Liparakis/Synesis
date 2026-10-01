# ADR-0081: Invisible canonical project metadata boundary

- Status: Accepted
- Date: 2026-09-18
- Task: SYN-081
- Mode: EVOLUTION

## Decision

`.synesis` remains the fixed, cross-platform root for Synesis-owned project-
internal bookkeeping. Existing project identity, coordination records,
provider/runtime state, locks, journals, and metadata-write temporaries remain
under that root. A single `ProjectMetadataStorage` seam owns creation and
validation of the root and applies the local visibility policy.

On Windows, the seam uses the standard DOS Hidden attribute through the JDK
attribute view. Applying Hidden is best-effort and returns a bounded diagnostic
when the filesystem does not support it or denies the operation. On Unix-like
systems the seam does not add platform-specific behavior; the leading dot is
the complete visibility policy. The root is inspected without following a
symbolic link, and unsupported/non-directory roots fail closed as storage
errors rather than mutating an external target.

Lifecycle entry points re-ensure the policy when a project is initialized,
opened/attached, materialized as a lawful replica, explicitly forked, or
reopened after a move/copy. Re-ensuring does not rewrite project metadata,
rotate node identity, alter membership, change Git, or copy visibility into
portable identity. Project identity and authorization remain based on their
existing canonical metadata and authenticated/signed state.

## Context and verified repository reality

The write-surface audit for SYN-081 found that project-owned persistence is
already predominantly routed to `.synesis`:

| Surface | Current target | Classification |
| --- | --- | --- |
| Logical project identity | `.synesis/project.json` | PROJECT-PORTABLE, canonical |
| Node identity/profile | `.synesis/local/profile/**` | NODE-LOCAL |
| Provider configuration/bindings | `.synesis/local/providers/**`, `.synesis/local/workspace-binding.json` | NODE-LOCAL / recoverable |
| Runtime and coordination state | `.synesis/local/runtime/**`, `.synesis/local/coordination/**`, `.synesis/coordination/**` | RUNTIME-EPHEMERAL / DURABLE-RECOVERABLE |
| Project records/events | `.synesis/shared/records/**`, `.synesis/coordination/events/**` | PROJECT-PORTABLE / DURABLE-RECOVERABLE according to the owning store |
| Atomic metadata temporaries | sibling of target metadata beneath `.synesis` | RUNTIME-EPHEMERAL, bounded and cleaned |
| Baseline/migration/cleanup/recovery administration | host-local Synesis administration roots under `%LOCALAPPDATA%\Synesis` or `~/.synesis` | EXTERNAL ADMINISTRATION, intentionally outside checkout |
| Integration/validation worktrees | host-local Synesis workspace roots | EXTERNAL WORKSPACE, intentionally outside checkout |
| Git exclusion edits | `.git/info/exclude` | GIT-OWNED exception, not Synesis project metadata |
| Provider/user integration instructions | root `AGENTS.md` managed section | VISIBLE USER-FACING exception, not hidden bookkeeping |
| User, agent, build, test, and tooling output | ordinary project paths | PROJECT CONTENT, never hidden by process ownership |

The audit found no loose Synesis-owned project metadata file such as a
root-level state JSON, lock, node identity, recovery marker, or journal that
should be moved by this milestone. No Git ignore policy is changed.

## Why this is the smallest sufficient change

The repository already has one metadata root and many callers accept a
`ProjectLocation` whose paths are beneath it. Adding another storage hierarchy,
`ReplicaId`, metadata browser, filesystem filter, shell command, or new MCP tool
would create state and compatibility obligations without satisfying a missing
constraint. The narrow seam fixes the only missing cross-cutting behavior—the
local visibility policy—and gives lifecycle code one place to re-ensure it.

## Rejected alternatives

- **Hide every file created by Synesis:** rejected because ownership is not
  process identity; agent-created source/docs/build/test output is real project
  content and must remain visible.
- **Use ACLs, System, encryption, a renamed directory, or Explorer settings:**
  rejected because visibility is a presentation property, not a security
  boundary.
- **Shell out to `attrib +h`:** rejected because the JDK DOS attribute view is
  the narrower standard API and avoids command quoting/process failure modes.
- **Persist `hidden=true` in project identity:** rejected because filesystem
  attributes are local derived presentation state and may be lost during copy,
  archive, cross-platform movement, or extraction.
- **Move existing external administration/workspace roots into projects:**
  rejected because their Git-common-directory and host-global semantics are
  deliberate and moving them would change lifecycle ownership rather than fix
  project metadata hygiene.
- **Add a metadata browser or MCP inspection tool:** rejected as scope
  expansion; diagnostics may already reveal paths when useful.

## Consequences and invalidation

The normal Windows Explorer view no longer shows `.synesis` by default, while
diagnostics and `Show hidden items` can still reveal it. Attribute failure is
diagnostic-only, so a valid project remains usable and can retry normalization
on its next lawful open. A symlink/reparse layout that cannot be validated
without following an external target is rejected according to the existing
fail-closed storage policy.

This decision should be revisited only if a new project-owned persistence
surface is intentionally introduced outside `.synesis`, if a platform lacks a
reliable local attribute API, or if measured lifecycle evidence shows that
re-ensuring on open is insufficient.
