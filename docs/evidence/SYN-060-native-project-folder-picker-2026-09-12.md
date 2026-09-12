# SYN-060 Native Project Folder Picker — 2026-09-12

## Result

SYN-060 is COMPLETE FOR CURRENT SCOPE. The installed Windows Projects page
now lets the operator choose a Synesis project through the Windows Explorer
folder picker. The selected folder name is displayed read-only and the exact
absolute path is submitted through the existing authenticated registry path.

## Implementation

- `ControlPlaneHttpHandler` exposes authenticated `choose-project`.
- Windows uses a bundled native helper backed by the shell `IFileDialog` API in
  folder-selection mode; cancel returns a side-effect-free `CANCELLED` result
  and headless/unavailable use fails closed.
- The response contains `state`, `path`, and `name`; `name` comes from the
  selected folder's final path component.
- `ProjectsView` renders a clickable folder tile with no
  editable project-name control.
- Registration still runs through SYN-059 project validation and mutation;
  the picker does not create files or bypass authority.

## Verification

- Red test confirmed the new UI contract before implementation.
- `npm run typecheck`: PASS.
- `npm run lint`: PASS.
- `npm run test -- --run`: PASS, 2 files / 22 tests.
- `npm run build`: PASS.
- Focused workspace registry/control-plane tests: PASS.
- `:cli:runnableInstaller --rerun-tasks`: PASS.
- Fresh Windows installer installation: PASS.
- Live installed UI: Projects page loaded with authenticated project state;
  Add project dialog visibly showed the new folder tile. A bounded native-helper
  smoke check confirmed the Windows shell picker process opens successfully.

## Boundaries

Windows is the current installed native-platform scope. Linux/macOS native
folder-picker integration and login/reboot autostart remain deferred. No
commit or push was performed.
