# SYN-070 Windows uninstall page cleanup evidence

Date: 2026-09-14
Task: SYN-070
Scope: presentation-only cleanup of the existing native Windows Synesis Setup
uninstall page.

## Boundary

The operator capture identified four remaining presentation defects: stale
hidden install-page text bleeding above the uninstall surface, helper copy
wrapping awkwardly at the compact window size, a title-bar product-mark
fallback, and path/action controls that read too small. The implementation is
limited to `bootstrap/installer_menu_windows.go` and preserves the existing
Win32 controls, event routing, uninstall confirmation/execution, metadata
semantics, user project/workspace/repository preservation, and all other wizard
pages.

## Implemented cleanup

- Clear the hidden install-page heading before rendering another page, avoiding
  stale text above the uninstall options surface.
- Keep the uninstall page's title bar free of the class-level icon fallback
  while retaining page-specific icon behavior for the other wizard pages.
- Use the body font for the read-only installation path so the selected folder
  remains legible at the compact scale.
- Shorten the existing helper copy without changing its meaning, allowing it
  to fit as one deliberate line.
- Give Back, Uninstall, and Cancel a balanced compact action rhythm while
  retaining their existing command routing and priority.

## Verification

| Check | Result |
| --- | --- |
| `go test ./...` in `bootstrap` | PASS |
| `go vet .` in `bootstrap` | PASS |
| `GOOS=windows GOARCH=amd64 go test -c -o <temp> .` in `bootstrap` | PASS |
| `GOOS=windows GOARCH=amd64 go build -ldflags '-H=windowsgui' -o <temp> .` in `bootstrap` | PASS |
| Gradle native launcher and bundle packaging stages | PASS |
| `git diff --check` | PASS |
| Native screenshot capture | UNAVAILABLE; no pixel-level claim made |
| Installer operation or managed-state mutation | NOT RUN |

The final `:cli:runnableInstaller` replacement step could not overwrite the
canonical `cli/build/distributions/synesis-windows-x64.exe` because the
previous installer was still running from that exact path (PID 34644). The
process and its extracted child were left untouched. A separately named valid
self-extractor was assembled from the fresh Gradle native launcher and bundle
archive; the expected self-extractor footer and embedded archive length were
validated against the bootstrap reader.

## Preview artifacts

- Executable: `cli/build/distributions/synesis-windows-x64-uninstall-polished.exe`
- Executable size: `55,426,065` bytes
- Executable SHA-256:
  `682777653B5E56266950202AEDCC99DBA21E7F647A3326171B0A90715769FFCE`
- Fresh bundle archive: `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip`
- Bundle size: `47,954,417` bytes
- Bundle SHA-256:
  `04304A467120C5D7C5B4012A8529965789B57B7C9810F7B248CBB04DEA6967D7`
- Fresh native launcher: `cli/build/native-mcp/windows-x64/synesis-installer.exe`
- Native launcher size: `7,471,616` bytes
- Native launcher SHA-256:
  `AAC4AC1805C7AE58AE036BD2DE9AA6CFB2781E0F1F4F1238D7F160B0BBB46257`

No installer action was executed, and PATH, registry, managed runtime state,
project data, repository data, workspace data, and daemon state were not
changed.
