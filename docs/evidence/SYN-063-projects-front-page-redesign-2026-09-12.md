# SYN-063 Projects Front-Page Redesign Evidence

Date: 2026-09-12

## Result

SYN-063 is complete for its current scope. The installed Projects front page
now uses a compact operator-dashboard hierarchy: a real-data overview strip,
clearly labeled discovery controls, responsive project cards, and a stronger
runtime-connections panel. Existing registry, routing, connection, and removal
semantics are unchanged.

## Authority and scope review

- The page derives its three overview values only from the current authoritative
  snapshot: known projects, live registry entries, and projected peers.
- Existing add, folder-selection, search, status-filter, open/details, registry
  removal, connection-detail, termination-confirmation, and network-navigation
  paths are preserved.
- No backend, route, protocol, registry, Link, durable-state, or authority change
  was made. No metric, analytic, action, or setting was invented.
- `docs/agent/DEFERRED.md` was reviewed during promotion. No deferred capability
  is activated by this presentation-only task, so no ADR is required.

## Functional verification

Run from `web-ui` using the installed Node/npm CLI directly because the shell's
global npm wrapper pointed to a missing roaming npm installation.

- `npm run typecheck`: PASS.
- `npm run lint`: PASS with zero warnings.
- `npm run test`: PASS, 2 files and 25 tests.
- `npm run build`: PASS, 1,583 modules transformed.
- `git diff --check`: PASS; only pre-existing line-ending warnings were emitted.

The browser exploration exercised a full search/filter reset cycle, producing
one `vector` search result, four inactive results, and all fourteen projects
after reset. A projected connection opened the existing detail dialog and the
close action returned to the registry.

## Rendered verification

The repository's existing `build/ui-art-direction/qa.cjs --mock` browser suite
passed at 2560x1440, 1920x1080, and 1280x800. It reported exact viewport/document
width parity on every screen, zero Projects accessibility violations at 1920,
no browser errors, successful inspector dismissal, and the expected filtered
empty state.

An additional interactive browser pass inspected the Projects page at:

- 2560x1440: 1480-pixel bounded page, two balanced project-card columns, fixed
  336-pixel connection rail, no horizontal overflow.
- 1920x1080: overview, controls, project cards, connection rail, and connection
  detail dialog visually inspected; no clipping or horizontal overflow.
- 1280x800: overview, filters, first project cards, and connections remain
  visible and aligned; no horizontal overflow.
- 375x812: stacked controls, three truthful overview values, and the first
  project card remain usable; no horizontal overflow.
- 1555x457: the compact overview, registry discovery controls, connection
  summary, and beginning of the project list remain above or at the fold; no
  horizontal overflow.

The final screenshots show no unintended glow, gradient, decorative telemetry,
clipped controls, overlapping status badges, nested scrollbars, or layout shift.

## Remaining boundary

This task does not rebuild or install a new native distribution and does not
commit or push. The already dirty working tree contains earlier SYN-059 through
SYN-062 work that remains preserved.
