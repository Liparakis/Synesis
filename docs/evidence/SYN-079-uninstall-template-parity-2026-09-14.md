# SYN-079 uninstall template parity evidence

- Scope: presentation and repaint ordering for the existing uninstall options page; uninstall execution and metadata semantics are unchanged.
- Main-page commit: `3017c6d3` (`fix: refine Windows installer maintenance page`).
- Uninstall capture: `C:/t/syn079-uninstall-onscreen-final.png`.
- Back-to-main capture: `C:/t/syn079-main-after-back-final-onscreen.png`.
- Observed window: `744x549`.
- Shared template: Synesis title-bar icon, right-side logo/wordmark, header divider, content edges, footer baseline, 72-source-unit button height, equal Back/Uninstall widths, compact Cancel width, and common right edge.
- Content: `Uninstall`; `Remove the application from this computer.`; `Your projects, repositories, and workspace data will be preserved.`; `Uninstall options`; `Installation folder`; `Remove metadata`; `Removes local identity and installer state. Project, repository, and workspace data stay intact.`; and `Application files and the synesis:// handler will be removed.`
- Repaint check: the uninstall capture retains the shared title-bar icon, logo, wordmark, options card, amber removal notice, and aligned footer controls. Installer operation buttons were not invoked.
- Verification: `bootstrap` `go test ./...` PASS; `go vet .` PASS; Gradle `:cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon` PASS.
- Canonical installer: `cli/build/distributions/synesis-windows-x64.exe`; SHA-256 `D91FE11FD91704BA3F29A968C24DC26AD324E2195B7B6FB199CEE118431B3D3E`.
- No install, update, repair, uninstall, metadata removal, PATH change, or registry operation was executed.
