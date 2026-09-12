# SYN-061 Automatic Project Initialization from Folder Picker — 2026-09-12

## Result

SYN-061 is COMPLETE FOR CURRENT SCOPE. The authenticated Add project flow
accepts any directory selected through the native folder picker. A valid
existing project continues through the existing registry authority. A
directory without Git is prepared with direct non-interactive `git init`, then
the normal Synesis initialization path creates the ordinary `.synesis` state
and registers the resulting project. The folder-derived display name remains
read-only.

Initialization errors are returned as bounded safe diagnostics and displayed
inside the folder tile with an accessible X dismiss button. No routing,
selection, project-name editing, shell command construction, or platform
registration changes were introduced.

## Implementation

- `ProjectApplicationService.initForRegistration` validates the selected
  directory, preserves existing Git metadata, and invokes the existing direct
  `GitProcessRunner` with `git init` only when `.git` is absent.
- `ControlPlaneReadModel.registerKnownProject` attempts ordinary registry
  validation first and invokes the preparation path only for `NOT_FOUND`.
- The existing authenticated `register-project` command and CSRF boundary are
  reused; the browser does not gain filesystem or initialization authority.
- The Add project dialog copy now allows arbitrary folders. The redundant
  `Project folder` section title is omitted; the selected name is
  presentation-only, and initialization failures stay in a dismissible panel
  above the folder picker.
- The explanatory copy was tightened to `Choose a folder to add. Existing
  state is preserved.` so it remains on one line in the installed dialog.

## Verification

- Focused workspace service/registry/read-model tests: PASS, 20 tests.
- `npm run typecheck`: PASS.
- `npm run lint`: PASS.
- `npm run test -- --run`: PASS, 2 files / 23 tests.
- `npm run build`: PASS.
- `:workspace:test`: 391 tests completed, 63 failed in the known unrelated
  aggregate coordination, capability, provider-session, and workspace test
  areas. No related initialization test failed and no unrelated repair was
  attempted.
- `:cli:installDist`: PASS.
- `:cli:runnableInstaller --rerun-tasks`: PASS.
- Fresh installed Windows bundle replacement: PASS.
- Live installed UI: authenticated Projects page loaded and the Add project
  dialog visibly showed arbitrary-folder copy, a read-only folder picker tile
  without the redundant section title, and no editable project-name field. The
  shortened explanatory sentence was visibly rendered on one line. Native
  picker behavior remains covered by the
  SYN-060 acceptance evidence and was not redesigned here.
- The focused browser test verifies the initialization error panel precedes the
  folder picker and keeps its dismiss button after the error message so CSS
  anchors the X to the right edge.
- `git diff --check`: PASS; Git only reported existing line-ending warnings.

## Boundaries

Windows remains the current native installed platform. Linux/macOS protocol or
folder-picker registration, login/reboot autostart, routing changes, and
project-name editing remain deferred. No commit or push was performed.
