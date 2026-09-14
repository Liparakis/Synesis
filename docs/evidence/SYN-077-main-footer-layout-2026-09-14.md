# SYN-077 Main-footer layout alignment

Date: 2026-09-14. Scope: main-page footer geometry at unchanged 912x666 overall / 896x627 client.

## Repair

Removed the two lower separator lines. Moved Current installation into the button row and constrained its slot before Next. Shifted the action buttons left so the Cancel right edge matches the right edge of both Install and Uninstall cards; the same shared alignment is used on the other navigation pages. Button commands and installer behavior are unchanged.

## Verification

- `cd bootstrap; go test ./...`: PASS, including action-card edge and path/button vertical-alignment assertions.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed.
- Settled native capture `C:/t/syn077-main-layout-settled.png` reports 912x666 and was visually inspected; lower bars are absent, Current installation shares the button row, and Cancel aligns with both action-card right edges.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `AB0D8D7A3F88118BC53D5130021D33A11430048C949F1224521F282A57354452`.
