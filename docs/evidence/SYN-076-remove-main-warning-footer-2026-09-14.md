# SYN-076 Remove main-page warning footer

Date: 2026-09-14. Scope: remove the main-page warning icon and adjacent text at unchanged 912x666 overall / 896x627 client.

## Repair

Removed the warning STATIC child, warning font, parent-painted warning triangle and exclamation mark, and the warning separator line. The existing page repaint remains in place so the main page cannot expose stale uninstall controls. Next/Cancel positions, navigation, window dimensions, operation semantics, and all other pages remain unchanged.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed.
- Native capture sequence: launch canonical package, select Uninstall, Next to options, Back to main, wait for repaint, capture. `C:/t/syn076-clean-return.png` reports 912x666 and visually shows no warning icon/text, no stale Back residue, and complete Next/Cancel controls.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `AB466DC66CE06A6E96BC526B69541379B0FBC956DAF1824F46C6FA4A1FBF96D4`.
