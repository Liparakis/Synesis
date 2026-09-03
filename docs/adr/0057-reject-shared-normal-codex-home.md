# ADR-0057: Reject shared normal Codex home for managed worker isolation

Status: Accepted rejection for SYN-051 feasibility — 2026-09-03

## Context

SYN-051 tested whether multiple dedicated stock Codex App Server processes
could share the user's normal CODEX_HOME, provider authentication, and thread
store while Synesis kept workers separate with exact threads, process-private
attachment proofs, and attachment generations.

The accepted local threat boundary excludes a fully compromised same-user OS
account. The required guarantee was narrower and stronger in a different
direction: ordinary managed workers must not accidentally recover or exercise
one another's logical worker authority.

## Decision

Reject the shared-normal-home architecture for managed worker isolation in
SYN-051.

The bounded provider experiment showed that:

- concurrent dedicated A/B App Servers can use the normal provider auth and
  thread store;
- launch-local Codex configuration overrides can deliver a distinct selected
  MCP environment variable to each process;
- exact A/B threads, short authenticated turns, and process recreation are
  operationally viable;
- the provider does not bind process-private attachment proof to persisted
  thread ownership;
- after the original A process stopped, a fresh process using the same normal
  home could resume the original provider thread and reach the disposable MCP
  boundary.

A process-private proof is therefore not sufficient to turn a shared provider
thread store into a worker-ownership or authority-fencing boundary. This is a
normal lifecycle capability, not a malicious same-user filesystem attack, and
it fails the required safety property.

Keep the existing dedicated retained-home architecture as the conservative
SYN-051 design. Keep UNSAFE_FILE_AUTH unchanged: the shared-home result does
not establish a safe authentication policy for the current isolated-home
implementation, and it does not authorize credential copying or migration.

## Consequences

The normal provider auth can be used for a bounded operational probe, but it
cannot be treated as sufficient managed-continuity isolation. A future shared
mode would need a provider-enforced per-worker/thread ownership contract or an
equivalent state-partitioned/single-writer authority boundary, followed by a
real Synesis managed-attachment probe. That is outside the current task.

The current production code, provider policy, MCP catalog, authority resolver,
historical fixtures, and .synesis state remain unchanged.

The first disposable launch caused Codex to add two probe trust entries to the
normal global config. The exact entries were removed and the file was verified
byte-for-byte against its pre-probe SHA-256 at that cleanup point. A later read
found an unattributed length/hash change with no probe references; it was not
overwritten because the missing user configuration was not recoverable from a
hash. No concurrent per-worker global config rewrite was accepted as a design.

## Evidence

Detailed 49-field results, process/thread identifiers, proof digests, provider
observations, secret-hygiene results, validation limits, and reproduction
artifacts are recorded in
docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md.
