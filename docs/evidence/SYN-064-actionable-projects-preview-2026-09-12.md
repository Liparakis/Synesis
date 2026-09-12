# SYN-064 Actionable Projects Preview Evidence

## Result

SYN-064 is COMPLETE FOR CURRENT SCOPE. The development mock preview now uses a
process-local registry adapter, so it exposes and exercises the same Add project
and non-current Remove project flows as the authenticated Projects view without
touching the filesystem or durable registry. Project removal is now a labeled
`Remove` button instead of an ambiguous trash-only control.

The current project remains protected: its card does not expose removal.

## Verification

- Frontend typecheck: PASS.
- Frontend lint: PASS with zero warnings.
- Frontend tests: PASS, 26 tests across two files.
- Production build: PASS, 1,583 modules transformed.
- Repository browser QA: PASS at 2560x1440, 1920x1080, and 1280x800 with no
  document overflow, no page errors, and zero Projects-page accessibility
  violations.
- Interactive mock Add flow: PASS; count changed from 14 to 15 and the new
  project card appeared.
- Interactive mock Remove flow: PASS; confirmation described the non-deletion
  boundary and the count returned from 15 to 14.
- Current-project protection: PASS; the `Test` card exposes no Remove action.

## Boundaries

The adapter is reachable only through the development-only `?mock=1` branch.
It changes React fixture state in memory and performs no control-plane command,
filesystem mutation, project initialization, registry write, or Link action.
The real authenticated page continues to use `ControlPlaneClient`. No commit,
push, installation, or publication was performed.
