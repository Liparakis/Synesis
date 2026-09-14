# SYN-069 Windows uninstall page visual parity

- Date: 2026-09-14
- Scope: presentation-only redesign of the existing native Windows uninstall page
- Source: `bootstrap/installer_menu_windows.go`
- Behavior boundary: unchanged Win32 navigation, confirmation, metadata value,
  uninstall execution, project/repository/workspace preservation, PATH,
  protocol, registry ownership, and other wizard pages

## Implemented

- Removed the global brand/ribbon/logo treatment from the uninstall page only.
- Removed the global footer warning banner from the uninstall page only.
- Kept the `Synesis Setup` title bar and `Uninstall Synesis` page title.
- Reworked the existing uninstall controls into a full-width dark bordered
  options surface with a clear installation-folder label and read-only path
  field.
- Replaced the stock metadata checkbox presentation with a native owner-drawn
  checkbox that keeps keyboard/mouse activation and visible focus feedback.
- Kept the existing metadata state wired to the same confirmation and uninstall
  operation path.
- Set the page action label to `Uninstall` while retaining the existing
  confirmation-page transition.

## Verification

| Command / check | Result |
| --- | --- |
| `go test ./...` from `bootstrap` | PASS |
| `go vet .` from `bootstrap` | PASS |
| `GOOS=windows GOARCH=amd64 go test -c -o <temp> .` | PASS |
| `GOOS=windows GOARCH=amd64 go build -ldflags '-H=windowsgui' -o <temp> .` | PASS |
| `git diff --check` for source and task records | PASS |
| Installer action | NOT RUN |
| Native screenshot capture | UNAVAILABLE; no pixel-level claim made |

The preview GUI binary was built in a temporary directory for release-shape
validation. It was not installed or used to mutate managed state.
