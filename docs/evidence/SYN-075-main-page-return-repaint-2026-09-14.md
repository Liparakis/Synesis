# SYN-075 Main-page return repaint and footer alignment

Date: 2026-09-14. Scope: main-page return repaint and footer alignment at unchanged 912x666 overall / 896x627 client.

## Repair

After returning from uninstall, the hidden owner-drawn Back child could leave its old pixels behind. Page changes now synchronously redraw the parent and all children. The warning copy is compact enough to remain one complete line, and Next/Cancel are moved to the footer's exact vertical midpoint. Installer commands, metadata behavior, page dimensions, and other layouts are unchanged.

## Verification

- `cd bootstrap; go test ./...`: PASS.
- `cd bootstrap; go vet .`: PASS.
- `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` then `./gradlew.bat :cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon`: BUILD SUCCESSFUL, 27 tasks executed.
- Native capture sequence: launch canonical package, select Uninstall, Next to options, Back to main, wait for repaint, capture. `C:/t/syn075-return-main.png` reports 912x666 and visually shows no Back residue, complete warning line, and vertically centered Next/Cancel.
- Scoped `git diff --check`: PASS, with only expected CRLF conversion warnings.

No installer operation was executed.

## Artifact

`cli/build/distributions/synesis-windows-x64.exe`

SHA256: `2FEEDE0D1CD0F59A2ECAB361B88A6860210B47AF32566AE9B118849AE528CA4E`.
