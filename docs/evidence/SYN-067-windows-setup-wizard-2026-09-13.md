# SYN-067 Windows setup wizard evidence — 2026-09-13

## Scope

This slice replaces the Windows no-argument bootstrap console menu with a
native Win32 setup wizard. The screenshot supplied by the operator was used as
visual reference only; its example words, paths, and values were not treated
as product instructions.

The wizard exposes Install/Repair and Uninstall choices, an editable install
root with the existing native folder picker, Back/Next/Cancel navigation, a
review page, a responsive worker/progress state, and an explicit checkbox for
removing installer-owned metadata. Install/repair uses the existing verified
bundle activation and then registers the owned per-user `synesis://` handler.
Uninstall removes payload and PATH state, and the metadata option removes only
installer-owned `admin` and `Link` records. User data and unrecognized files
under the installation root remain.

## Corrective follow-up — launch visibility and startup sequencing

The CP-0944 launch claim did not survive independent reproduction: the
single-file wrapper and the extracted native installer both created a live
`Synesis Setup` window with `WS_VISIBLE` absent. The first visibility-only
correction made the top-level style visible, but it exposed a startup freeze:
the window began painting before initialization had completed. The final fix
keeps the top-level window hidden during construction, queues a private show
message, and enters the message loop before showing and updating the window.

Independent tracing then found the actual deadlock: Go had migrated the UI
goroutine to another OS thread between creating the top-level window and its
child controls. Win32 child windows are thread-affine, so the child
`CreateWindowEx` call blocked. `runInstallerMenu` now locks the OS thread for
the full wizard lifetime. The regression test protects deferred top-level
visibility and the distinct private show message.

A fresh no-argument launch of the rebuilt single-file artifact created a
`Synesis Setup` HWND with `VISIBLE=True`, `HUNG=False`, and a successful
one-second `WM_NULL` delivery probe. The wrapper and extracted child were
closed without selecting an install action.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Windows bootstrap tests | PASS | `go test ./...` from `bootstrap` |
| Bootstrap vet | PASS | `go vet .` from `bootstrap` |
| Unix source compatibility | PASS | `GOOS=linux GOARCH=amd64 go test -c` and `go build ./...` |
| User-data cleanup boundary | PASS | `TestMetadataRemovalPreservesUserDataInsideInstallationRoot` |
| Existing protocol ownership policy | PASS | `protocol_registration_test.go` |
| Packaged installer build | PASS | `:cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon` |
| GUI subsystem | PASS | single-file and native installer PE subsystem `2` |
| Self-extracting launch | PASS | fresh no-argument launch of the rebuilt wrapper created a `Synesis Setup` HWND with `VISIBLE=True`, `HUNG=False`, and `WM_NULL_DELIVERED=True`; the test process was closed before any operation |
| Window startup sequencing | PASS | top-level visibility is deferred; `installerMessageShow` is posted before `GetMessage`, and `runtime.LockOSThread` keeps parent and child controls on one OS thread |
| Window visibility regression | PASS | `TestInstallerWindowVisibilityIsDeferredUntilMessageLoop`; top-level style omits `WS_VISIBLE` until the message-loop show handler |
| Selection-card action page | PASS | owner-drawn action cards replace the white native group box and stock radio rows; selected and disabled states derive from installer state |
| Embedded Synesis logo | PASS | `bootstrap/assets/synesis-logo.png` reuses the browser product mark; native conversion and blend-value tests cover bitmap creation and per-pixel alpha invocation |
| Mandatory dark theme | PASS | source applies dark client/control brushes and requests dark title, caption, border, and text attributes; no light-mode setting exists |
| Deferred register | PASS | `scripts/agent-validate-deferred.ps1` |
| Diff hygiene | PASS | `git diff --check` for the SYN-067 files |

The packaged build used the bounded workstation workaround
`TEMP/TMP=C:\t` and `GRADLE_OPTS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`
because the unqualified Gradle launch hit the host's known loopback bootstrap
failure before task execution.

## Artifact provenance

- Single-file installer: `cli/build/distributions/synesis-windows-x64.exe`
  - SHA-256: `CF27932D5B77352ED20B71390E679738F8333F6FE54A700ADACBE8C64168CE10`
- Windows bundle: `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip`
  - SHA-256: `487C5A3F4630A1F257F56576DD4323B2A4455BC6F9F9CCC3D15B757620056D21`
- Native installer inside the bundle: `cli/build/native-mcp/windows-x64/synesis-installer.exe`
  - SHA-256: `50F0F758A5DD9D48A76722A62F1FF1798E60761F045BDDD7FB19A325D60E94BC`

## Boundaries

No real installation was performed during this source acceptance run, so the
managed user installation, current PATH, registry, daemon, projects,
repositories, and workspaces were not changed. Pixel-level screenshot capture
and direct button-click QA are not claimed; the launch check above uses a
bounded native Win32 window/process probe (`EnumWindows`, visibility/hung
state, and `SendMessageTimeout(WM_NULL)`). This evidence also does not claim
cross-machine onboarding or universal browser/network reachability.
