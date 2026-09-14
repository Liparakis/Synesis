# SYN-072 Main-page footer clipping repair

Date: 2026-09-14. Scope: main-page footer only; window remains 912x666 overall / 896x627 client at scale 0.64.

## Repair

The warning text and navigation buttons used the compact client's final pixels, so the warning could be clipped in a real Windows capture. Only the main action page moves its warning text and Next/Cancel controls upward. Uninstall, installation, confirmation, behavior, and window dimensions remain unchanged.

The native layout regression now maps the parent client origin to screen coordinates and asserts that the warning, Next, and Cancel controls end within the actual client area. This avoids incorrectly treating the title bar as client space.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `GOOS=linux GOARCH=amd64 go test -c -o C:/t/syn072-linux.test`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed. Log: `C:/t/setup-syn072-build.log`.
- Packaged native capture: `C:/t/syn072-main-footer.png`; actual window size reported as 912x666 and visually inspected with the full warning, Next, and Cancel visible.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No install or uninstall operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `8B1AF692F95BFA13B10FF2B8DF988F180A2AC7352E06174C52213693C5E699BD`.
