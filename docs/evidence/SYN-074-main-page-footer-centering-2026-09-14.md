# SYN-074 Main-page footer centering

Date: 2026-09-14. Scope: main-page safety-note alignment only at unchanged 912x666 overall / 896x627 client.

## Repair

The main-page safety note now uses centered static-text alignment within its existing footer slot between the warning divider and Next button. Copy, font, slot dimensions, warning mark, button positions, navigation, and all installer behavior remain unchanged.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed.
- Settled native packaged capture `C:/t/syn074-main-centered-repeat.png` reports 912x666 and was visually inspected; it shows the centered note, logo, capability icons, warning mark, and complete Next/Cancel controls.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `128EE4FBA259C8E4A69403CD9DB1757994FB48DD935AE4C2791C599C20B9662C`.
