# SYN-051 shared-normal-home implementation slice — 2026-09-03

## Classification

**PARTIAL — durable authority controls implemented; managed runtime acceptance
remains blocked at the process-container and provider-private-carrier gates.**

This record covers the bounded production slice authorized after the PASS-B
security-model decision. It does not claim that shared normal-home Codex
continuity is generally production-ready.

## Implemented controls

- Added `ProviderThreadOwnershipRecord` and `ProviderThreadOwnershipStore`.
- Active ownership is keyed by `(provider, providerThreadId)` and persisted
  below `.synesis/local/runtime/provider-thread-ownership`.
- Acquire uses one project-wide file lock plus atomic replacement; a second
  binding receives `provider_thread_owned_by_another_binding`.
- Released ownership remains a durable tombstone and is not casually reusable.
- Added `ManagedCodexThreadBroker`, which accepts only active Codex ownership,
  exposes the pinned selector, and rejects a changed provider result.
- Managed attachment issuance and replacement can derive the thread only from
  the durable owner. Provider-thread ownership is separate from attachment
  generation.
- Added explicit `NORMAL_PROVIDER_HOME_MANAGED` runtime mode and an auth policy
  classification that does not inspect provider credential files in that mode.
- The exact managed MCP binding rejects proof-less startup instead of
  downgrading to ordinary `SESSION_BOUND`; managed session resolution also
  requires an active managed attachment record.
- `UNSAFE_FILE_AUTH` remains unchanged for isolated dedicated-home mode.
- Managed launcher default is fail-closed until an owned process-tree
  supervisor is injected. Ordinary `ProcessBuilder` is not treated as
  assignment-before-resume containment.
- No MCP tool, raw App Server passthrough, provider credential read/copy,
  provider source patch, second identity graph, or Review/Doctor change was
  added.

## Verification

Starting source HEAD was
`efafef9701500dc7953c6d612c0bd54c322ea236` on `master` with a clean tree.

The changed production sources and focused test sources compile with Java 25
using `javac -Xlint:all -Werror`. Direct runtime checks for durable ownership
and immutable broker pinning passed. `git diff --check` and the deferred
register validator passed. The MCP catalog still contains exactly 10 tool
descriptors.

The focused Gradle test command was attempted twice, including the approved
IPv4 preference environment workaround, but Gradle failed before configuration
with `java.io.IOException: Unable to establish loopback connection`. Therefore
the JUnit suite is **incomplete**, not passing.

## Deliberate blockers

The repository still lacks a production Windows Job Object implementation and
the lifecycle service's existing termination path is not wired to the new
supervisor seam. The available Codex App Server child-launch evidence also
does not provide a supported private proof carrier to the exact managed MCP
child under the shared normal home. No real A/B managed run, raw-thread probe,
B-to-A provider probe, forced restart, artifact provenance run, or full
task-tracker acceptance was started.

## Exact continuation

Implement and verify the Windows supervisor with assignment-before-resume,
kill-on-close, exact root identity, and an unambiguous empty/dead predicate;
then integrate it through `CodexAppServerLifecycleService` and prove a trusted
thread-scoped managed MCP proof carrier before attempting real managed
acceptance. Preserve `UNSAFE_FILE_AUTH` and ordinary `SESSION_BOUND` behavior
until those gates pass.
