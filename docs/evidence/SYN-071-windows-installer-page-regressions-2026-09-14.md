# SYN-071 Windows installer page regression repair

Date: 2026-09-14. Scope: main and uninstall presentation at unchanged 912x666 overall / 896x627 client, scale 0.64.

## Findings and changes

Shared header controls had been widened across the main logo and shared footer buttons moved over warning text. Each page now lays out those shared controls within its available space. The uninstall owner-drawn STATIC panel overlapped sibling controls; it now paints in the parent background behind the controls. Owner-drawn buttons and metadata checkbox use WM_DRAWITEM's device context instead of acquiring a separate window DC. The read-only path has a matching explicit background color. Removed an empty footer STATIC that erased the separator.

Existing title-bar icon suppression on uninstall, metadata semantics, confirmation, and operation execution are preserved. No install/uninstall action was executed; previews were closed after navigation checks. Unrelated dirty work was preserved.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `GOOS=linux GOARCH=amd64 go test -c -o C:/t/syn071-linux.test`: PASS (PowerShell environment variables set separately).
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed. Log: `C:/t/setup-syn071-build.log`.
- Native geometry/transition regression test covers action, uninstall, install, confirmation, return; actual control rectangles check header/logo and footer/button separation. Metadata selection/clearing and Back navigation pass without executing operations.
- Native packaged preview captures visually inspected: `C:/t/syn071-final-main.png`, `C:/t/syn071-final-uninstall.png`, `C:/t/syn071-final-return.png`. Each reports 912x666. Three additional Back/Next round trips rendered correctly. One immediate capture showed a partially painted selected card; repeating after paint settled rendered all text and radio controls correctly.
- Scoped `git diff --check`: PASS (only CRLF conversion warnings).

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `719A74243067FF8612EB26AC7D9FE32B53B3D71A57EC66C4338B5B3C4CF52DD8`.

This canonical rebuilt package supersedes the earlier alternate uninstall-polished preview. Evidence is local Windows presentation/navigation and packaging verification; no operational install/uninstall acceptance is claimed.
