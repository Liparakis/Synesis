# SYN-073 Main-page footer composition polish

Date: 2026-09-14. Scope: only main-page safety-note typography/alignment at unchanged 912x666 overall / 896x627 client.

## Repair

The prior footer no longer clipped but was still too small and wrapped at the bottom divider. The main-page safety note is now:

`Only install trusted software. Setup may register the per-user synesis:// handler.`

It uses a larger normal face, one no-wrap line, and explicit space after the warning divider. The warning mark, Next, and Cancel remain inside the client area. Other pages, dimensions, button commands, metadata handling, confirmation, and install/uninstall behavior are unchanged.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: native staging, platform archive, and runtime image succeeded; final canonical output copy failed only with `AccessDeniedException` because the operator-open `cli/build/distributions/synesis-windows-x64.exe` was locked by PID 17088.
- The fresh native launcher and archive were assembled with the same self-extract footer format into `cli/build/distributions/synesis-windows-x64-footer-polished.exe` and launched successfully.
- Native capture `C:/t/syn073-main-footer.png` reports 912x666 and was visually inspected; the warning is a full readable line with complete Next and Cancel controls.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed. The review preview was closed; the operator-open canonical installer was not touched.

## Artifact

Fresh review artifact: `cli/build/distributions/synesis-windows-x64-footer-polished.exe`

SHA256: `CCF501092F248E662BC26D7751805544EF977A606755464CA4B457D5368B037E`.
