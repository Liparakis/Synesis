# SYN-062 Instant Project Registry Removal — 2026-09-12

## Result

SYN-062 is COMPLETE FOR CURRENT SCOPE. Confirming removal now hides the
project row immediately while the existing authenticated registry mutation
continues in the background. A successful mutation is reconciled with the
authoritative snapshot; a failed mutation restores the row and shows the
existing error as a temporary bottom toast.

## Implementation

- `ProjectsView` tracks pending removal IDs locally and excludes them from the
  rendered registry immediately after confirmation.
- The existing `remove-project` command remains the only durable mutation; no
  second registry, fake success, project-file deletion, or runtime change was
  introduced.
- The confirmation modal closes immediately without displaying a progress
  message.
- Errors remove the pending marker, restore the project row, and render the
  existing error message in an auto-dismissing bottom toast.

## Verification

- Red test confirmed the old behavior held the row and modal open until a slow
  removal promise resolved.
- `npm run test -- --run src/app/App.test.tsx`: PASS, 23 tests.
- The focused removal tests verify that no removal-progress message is shown
  and that a failed background mutation restores the project with an alert.
- `npm run typecheck`: PASS.
- `npm run lint`: PASS.
- `npm run build`: PASS.
- Focused Gradle registry tests were blocked before task execution by the
  known host failure `Unable to establish loopback connection`; the documented
  process-local temporary-directory workaround and a clean Gradle daemon stop
  did not resolve this workstation issue.
- No commit or push was performed.

## Boundaries

This is an optimistic UI update with truthful rollback, not a claim that the
durable command has already completed. Project folders, `.synesis` state,
repositories, runtimes, and Link identity remain untouched. The task does not
add a background queue service because the existing registry mutation is
already bounded; only the page no longer waits for its completion to update.
