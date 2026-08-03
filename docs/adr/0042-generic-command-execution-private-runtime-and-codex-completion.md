# ADR-0042: Generic command execution, private runtime state, and Codex completion

## Status

Accepted for SYN-037.

## Context

The prerelease `run_command` contract selected command intent and project-specific
adapters. That made command evidence depend on an inferred toolchain and left
server validation on a different path from agent execution. Runtime state also
needs to remain physically available to local coordination without making
provider configuration implicitly Synesis-owned.

SYN-037 must preserve the ten raw MCP tools, the existing session/lane authority
model, immutable snapshots, and the narrow private Git visibility contract while
proving a real Codex lane can finish and integrate.

## Decision

### Direct argv and one executor

`run_command` accepts only a non-empty direct `argv`, an optional relative
`workingDirectory`, and a bounded `timeoutSeconds`. No implicit shell, quoting
reinterpretation, intent vocabulary, build-system adapter, or `run-tests.cmd`
route remains reachable.

`ProjectProcessExecutor` is the single workspace-layer process primitive. It is
used by `run_command`, server-owned pre-publication validation, and integration
validation. The project owns the optional validation argv in schema-version-2
`.synesis/project.json`; Synesis validates and invokes it but never infers a
toolchain or lets an agent override it. Version-1 metadata remains readable as
having no configured validation gate.

The executor resolves every working directory against the authoritative lane or
integration worktree, rejects absolute/traversal/cross-worktree escapes, starts
the argv directly with `ProcessBuilder`, filters the existing sensitive
environment keys, and terminates the full process tree on timeout or
cancellation. Execution failures use stable concrete outcomes, including
`command_executable_not_found`, rather than converting a start failure into an
exit code.

### Raw-byte command evidence

Both streams are drained concurrently. Each stream retains at most 65,536 raw
bytes: a 32,768-byte head and a rolling 32,768-byte tail. Overflow continues to
drain the process and inserts a stable display marker between the decoded head
and tail. A 1 MiB response ceiling accommodates two bounded streams, escaping,
and metadata.

For each stream, `BytesRead` is the total raw byte count drained before decoding,
`BytesRetained` is the raw byte count actually retained for decoding (excluding
the synthetic marker), and `Truncated` is true when any bytes are omitted, an
incomplete UTF-8 boundary is discarded, or EOF was not observed before collector
shutdown. Exact-limit output with EOF is complete; empty output is zero/zero and
not truncated. Sanitization happens after decoding and cannot alter these
fields. The same `CommandExecutionResult` shape is returned for direct and
server-owned executions.

### Private visibility and Codex hook ownership

Synesis maintains only these root-anchored lines in the canonical Git common
directory's `info/exclude`, preserving unrelated content and linked-worktree
sharing:

```text
/.synesis/local/
/.synesis/coordination/
/.codex/hooks.json
```

These exclusions change Git visibility only. They never establish file
ownership. Codex hook materialization classifies absent, canonical/stale
Synesis-owned, user/provider-owned, mixed, tracked, malformed, symlinked,
non-regular, concurrently changed, and ambiguously owned states. Only the
canonical hook handler shape and stable Synesis session-hook identifier prove
Synesis ownership. Unrelated provider entries are preserved where safe; an
ambiguous or tracked file fails before session authority with the stable
`PROVIDER_CONFIGURATION_CONFLICT` diagnostic. Writers are serialized and
revalidate file type and digest immediately before atomic replacement.

The invariant is: **Git exclusion never grants Synesis ownership of provider
configuration.**

### Completion proof

Completion reruns the configured validation in the lane before snapshot
preparation/publication. Integration applies the immutable snapshot in its
dedicated worktree, runs the same configured argv through the same executor, and
advances the control branch only after a zero exit. The real-Codex acceptance
requires an uncontaminated one-file snapshot, integrated task-tracker content,
the same structured validation evidence, and an empty final control `git status
--short`.

## Alternatives considered

- **Keep intent/adapters or add a compatibility route:** rejected because it
  leaks toolchain capability, makes server validation diverge, and broadens the
  ten-tool contract.
- **Terminate a process when output exceeds the limit:** rejected because
  ordinary evidence overflow should not change command semantics; collectors
  drain while retaining deterministic bounded evidence.
- **Treat the private exclusion as ownership or rewrite all `.codex` content:**
  rejected because Git visibility is not provider authority and would silently
  destroy user/provider configuration.
- **Add a validation MCP tool or toolchain adapters:** rejected because project
  validation is server-owned and remains a configured argv, not a new protocol
  capability.

## Consequences and verification

The prerelease command schema is intentionally breaking. Agents receive
explicitly complete-or-truncated output evidence and concrete process-start
diagnostics. Project metadata can opt into one validation command without
introducing a build-system abstraction. Hook conflicts stop authority-increasing
session setup rather than risking an overwrite. Focused tests cover stream
overflow, UTF-8 boundaries, cancellation/timeout, exact private exclusions,
hook ownership conflicts, direct MCP schema rejection, and a lane completion
that uses the same configured argv for direct execution, finish validation, and
integration validation.
