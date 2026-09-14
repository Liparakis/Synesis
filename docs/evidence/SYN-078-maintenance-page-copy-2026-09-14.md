# SYN-078 Main maintenance-page copy cleanup

Date: 2026-09-14. Scope: copy only on the existing main maintenance page.

## Copy contract

The page now uses the requested text:

- `Manage installation`
- `Choose what you want to do with the current installation.`
- `Version 0.1.0-dev.local`
- `Setup includes`
- `Local runtime`
- `synesis:// link handler`
- `Existing project and workspace data is kept`
- `Choose an action`
- `Install or update` — `Install this version and keep your existing projects and settings.`
- `Uninstall` — `Remove the application. Project data will be left untouched.`
- `Installed at` followed by the unchanged installation path
- `Continue`
- `Cancel`

The window title remains `Synesis Setup`. The product-name body line is empty so the logo and title bar establish identity without repetition. No layout, styling, controls, behavior, navigation, installer logic, or functionality changed.

## Verification

- `cd bootstrap; go test ./...`: PASS, including exact main-page copy assertions.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed.
- Settled native capture `C:/t/setup-copy-main-settled.png` reports 912x666 and was visually inspected.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `7475247D3300409A35C291800F1164392CB48C14BA2060B95CA687473B002D56`.
