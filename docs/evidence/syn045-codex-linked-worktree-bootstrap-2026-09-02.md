# SYN-045 Codex linked-worktree and bootstrap evidence — 2026-09-02

## Source and installed-runtime verification

- The explicit MCP project-root correction is present in the working tree. A
  provider-reported ordinary linked Git worktree is accepted only when its
  Synesis project ID and canonical Git common directory match the explicit
  launcher project; mismatched, assigned, or invalid roots fail closed.
- Focused MCP root-selection coverage passed: 12 tests.
- Explicit MCP startup-argument coverage passed: 2 tests.
- The selected existing MCP root-authority coverage passed: 7 tests.
- The focused workspace provider test passed: 9 tests.
- The platform bundle completed successfully. The installed native MCP
  executable and the bundle executable had SHA-256
  `FEF3C1A867CC8570B1C8184DC0F7141AFA60DAE35F7475573A8322C94A0C4893`.
- Normal installer repair completed successfully and the Codex provider
  configuration was up to date. The installed runtime is
  `0.1.0-dev.local`.

## Fresh two-Codex acceptance

- Control project: `C:\Users\Liparakis\Desktop\ToPouliTouTzala`.
- The installed runtime admitted the bootstrap task and two independent Codex
  worker tasks. Both workers received distinct Synesis-assigned worktrees and
  disjoint domain/persistence versus service/reporting claims.
- The bootstrap task created the minimal Gradle skeleton and committed
  `8225b9f` (`Bootstrap Java task tracker fixture`) in its own Synesis lane.
- The workers were created before that lane's snapshot was published and
  integrated. Their worktrees therefore correctly started at control commit
  `36bc543` and did not contain the bootstrap files.
- Worker A and Worker B stopped without mutating files or guessing the missing
  architecture. No manual copy, cherry-pick, control-checkout edit, internal
  identifier creation, or Synesis-state edit was performed.
- A later parent continuation received `ready` from an exact
  `ensure_session({})`, but its new connection had no active intent; the next
  projection was `coordination_intent_required -> ensure_session`. Luna source
  review confirmed this is the expected safety boundary: a new connection
  must re-establish the original task claims or use an explicit continuation
  grant, and cannot inherit another participant's lane implicitly.

## Result boundary

The SYN-045 root-authority/provider-admission correction and the narrow fresh
pair admission target are verified. The broader task-tracker application
acceptance is incomplete because the bootstrap baseline was not published
before the workers were spawned. The existing fixture is preserved for review;
it must not be restarted or repaired by manual branch/file transfer as part of
this task.
