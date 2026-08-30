# Zero-touch collaboration MVP

## Ordered tasks

1. **Init and migration.** Extend `ProjectApplicationService` and `InitCommand`
   for tracked config, ignored local state, provider installation, AGENTS.md,
   idempotent upgrades, and preservation of unrelated files. Gate: fresh,
   repeated, partial, malformed, and legacy-init tests.
2. **Session/runtime registry.** Add session/worktree records, hidden loopback
   runtime lifecycle, locks, cursor persistence, status/doctor, and orphan
   cleanup in `:coordination`. Gate: crash/restart, duplicate startup, dirty
   worktree, moved repository, and branch deletion tests.
3. **Provider adapters.** Extend `ProviderIntegration` with capability probes;
   add Codex and Claude bootstrap/transition/injection implementations only
   where real evidence supports them. Gate: trusted real-agent proof; otherwise
   explicit read-only status.
4. **Intent and ownership.** Add task-intent records, provisional claims,
   conflict resolution, stale-session fencing, cancellation, and reconnect.
   Gate: signed authorization and overlap/abandonment tests.
5. **Requester/owner automation.** Convert `REQUEST_OWNER` into automatic
   contract confirmation, inbox routing, safe-boundary delivery, and natural
   response mapping. Gate: no manual protocol commands in process tests.
6. **Speculation and integration.** Add the language adapter seam, evidence-
   backed publication, automatic integration, real validation, dependency
   invalidation, and cleanup. Gate: clean/conflict/mismatch/offline/failure tests.
7. **Acceptance and bundle.** Run the external-project two-direction harness
   after `synesis init` only; repeat with the installed bundle and record
   latency/resource/recovery measurements. Gate: every zero-touch metric passes.

## File-level map

Modify `workspace/application/ProjectApplicationService`, `cli/command/InitCommand`,
`workspace/provider/ProviderIntegration`, and existing provider integrations.
Add session/worktree/runtime/domain records and orchestration under
`coordination/src/main/java/org/synesis/coordination/session` and
`.../workspace`. Add unit/process/provider/Git tests beside existing module
tests; add `scripts/run-zero-touch-collaboration.ps1` only after provider gates
are real.

## Acceptance script

The operator creates a fresh Git project and runs only `synesis init`. Two real
supported harnesses are opened normally and receive Task A/B. Evidence must
prove distinct identities, sessions, worktrees, live owner delivery, prevented
owner-scope mutation, speculative continuation, unresolved integration gate,
owner commit provenance, real requester validation, automatic retirement, and
zero manual Synesis protocol commands. Run both requester/owner directions.

## Rollout and rollback

Ship behind an initialized-project format/version gate. Repeated init upgrades
atomically and preserves v1 readers. If any provider or runtime gate fails,
disable autonomous mutation for that provider and retain diagnostic commands;
remove only Synesis-managed hooks/local state during rollback.

## Non-goals

No remote enrollment, multi-machine coordination, PostgreSQL, broker, relay,
MCP surface, global agent, shared worktree, remote execution, or production
Claude support.
