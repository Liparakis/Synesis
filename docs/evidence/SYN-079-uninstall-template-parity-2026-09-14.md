# Current SYN-079 result-screen cleanup — 2026-09-14

Status: COMPLETE for the authorized follow-up, reusing SYN-079. This section supersedes earlier artifact identities and result-screen descriptions below.

## Result

- A clear outcome heading names the incomplete operation and the failed stage in plain language. One compact card groups the brief summary and installation folder.
- Show details is collapsed by default. Opening it reveals the bounded error and retained operation history in a dark, read-only, selectable native edit. Text wraps and requires only vertical scrolling. The error summary starts at the top whenever details open.
- Closing details removes its surface cleanly. The result has one fully visible Close button. Existing logo, gradients, icon rendering, smooth progress and actual backend semantics are preserved.

## Verification

- From bootstrap: go test ./... — PASS; go vet . — PASS; go test -c -o build/installer-clean-result.exe — PASS. Existing result/navigation/color tests plus failure visibility, diagnostic retention/wrapping, scroll-to-top and disclosure behavior pass.
- Native no-operation preview: collapsed failure card, expanded wrapped diagnostics, vertical-only scrollbar and collapse repaint visually verified. Final scroll-to-top addition passed the native control assertion; the final expanded screenshot was unavailable after user interaction/preview closure. No claim of other DPI/theme visual coverage.
- Gradle :cli:nativeMcpLauncher --rerun-tasks — PASS. Initial :cli:runnableInstaller replacement failed with AccessDeniedException because the canonical output is running as PID 54572; that process and artifact were preserved.
- Final build: gradlew.bat :cli:nativeMcpLauncher :cli:platformZip --rerun-tasks -x :cli:runtimeImage -x :cli:installDist -x :mcp:jar --dependency-verification=strict --no-configuration-cache --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g --console=plain — PASS, all three tasks executed using existing local toolchains/resources.
- Assembled alternate self-extractor with the exact existing Gradle format: native executable + platform ZIP + SYNESIS_SELF_EXTRACT_V1 newline + big-endian 64-bit ZIP length. Verified its native prefix, embedded ZIP, inner bin/synesis-installer.exe and footer against fresh inputs — PASS.
- git diff --check — PASS. Deferred register reviewed; no deferred capability promoted. Checkpoint CP-0966 records completion.

## Deliverable

- cli/build/distributions/synesis-windows-x64-clean.exe
- Size: 55,284,071 bytes
- SHA-256: cfd76fca51c60aa21f00ce3483aba34198ac893c50579b9a50c2a07fee1221a1
- Logs: build/installer-result-build.log, build/installer-result-package.log (canonical lock), build/installer-result-fresh-bundle.log, build/installer-result-artifact.txt.

No real installation/uninstallation, registry/PATH/metadata mutation, Synesis coordination action, new task, commit or push was performed. This presentation change does not claim to fix the underlying uninstall failure shown by the user.

---

# Historical evidence (superseded)

# Current SYN-079 uninstall layout, gradients and smooth rendering — 2026-09-14

Status: COMPLETE for the authorized follow-up. This section supersedes earlier artifact identities and presentation descriptions below. The existing SYN-079 task is reused.

## Result

- Installation folder label and frame now have separate bounds and a visible gap. The native path edit is inset on every side. Label typography is smaller than the option heading and path value.
- Metadata explanation uses two complete lines with measured room; the checkbox, helper, field and warning have consistent spacing. The amber warning triangle has antialiased edges and a centered mark.
- Shared setup background has a subtle navy gradient and soft light near the brand. Uninstall options and warning surfaces have their own gentle gradients and rounded borders, using the existing artwork and native controls.
- A cached parent surface and aligned native pattern brushes keep text backgrounds continuous with the gradients. Owner-drawn buttons, steps and the animated progress surface use the same backdrop, avoiding flat rectangular patches after navigation.
- Includes the immediately preceding requested polish: 500 ms eased progress transitions toward actual completed stages, a moving sheen, antialiased selection/status icons, reusable offscreen surfaces and timer cleanup. No estimate advances past observed progress. A successful operation can finish its verified fill animation before its summary appears.

## Verification

- `go test ./...` from bootstrap — PASS. Includes native label/frame separation, measured metadata-text fit, exact gradient background alignment, edit contrast, motion bounds/continuity, sheen clipping, premultiplied icon edges, navigation, backend callback boundaries and preservation fixtures.
- `go vet .` from bootstrap — PASS. The previously recorded unrelated whole-module unsafe.Pointer diagnostics remain outside this change.
- `go test -c -o build/installer-depth-final.exe` — PASS; its executor emits fixtures only and cannot call install/uninstall operations.
- Native visual preview — PASS on the current Windows desktop: uninstall label and complete frame, both metadata helper lines, inset readable path, smooth amber icon, gradient continuity behind labels and cards, and Back-to-main repaint. The first preview revealed a tight helper-text height; it was increased and rechecked successfully. Previous smooth-rendering preview showed antialiased selected/empty radio icons and a filled progress bar with sheen; a final completion capture in that prior turn was interrupted by the user's Escape.
- Gradle `:cli:nativeMcpLauncher --rerun-tasks` and forced `:cli:runnableInstaller` packaging — PASS with strict dependency verification, existing local Go/JDK toolchains, no configuration cache and no daemon. Packaging excluded unchanged runtimeImage/installDist/mcp:jar and reused the freshly built native launcher. All three packaging tasks executed.
- Package byte checks — PASS: outer prefix and inner archived installer equal the fresh native binary; embedded ZIP and big-endian footer length/magic agree.
- `git diff --check` — PASS. Deferred register reviewed; no deferred capability promoted.

## Artifact

- cli/build/distributions/synesis-windows-x64.exe
- Size: 55,271,826 bytes
- SHA-256: `14ba053c56e5c382bb611715d0ec4c75872408a71d939c3ea25abd7690905b9a`
- Logs: build/installer-depth-build.log, build/installer-depth-package.log, build/installer-depth-artifact.txt.

No real installation, uninstallation, metadata removal, registry/PATH mutation, Synesis coordination action, new task, commit or push was performed. Other DPI configurations were not visually exercised. Screenshots are available in this task's tool outputs. Completion checkpoint: CP-0965.

---

# Historical evidence and artifacts (superseded)

# Current SYN-079 visual-defect fixes — 2026-09-14

Status: COMPLETE for the authorized installer scope. This section supersedes the prior artifact hashes, indeterminate-bar design and interrupted visual review below. No new task was created.

## Changes and root causes

- The native edit returned a dark brush but left the DC text background white. WM_CTLCOLOREDIT and read-only/disabled edit coloring now explicitly use matching dark backgrounds and light foregrounds.
- Page switching left Cancel visible while moving it and omitted the right footer from invalidation. All old controls are hidden before layout; the entire parent client is repainted, then its children. Installed and Uninstalled show one clean Close button.
- Progress uses the supplied rounded cyan/blue look, dark outline, linked green/completed, blue/current and gray/pending rings, bold current stage and muted readable details. Fill reflects completed backend stages and is labeled X / 4 steps; no byte/time percentage is fabricated.
- Log status prefixes are colored, message text is neutral/light and the heading is bold. Native selection, scrolling and keyboard behavior remain. The native dark theme removes white scrollbars. Partial rows are left blank rather than cutting text; full diagnostic text is horizontally reachable without a painted ellipsis.
- Existing direct main -> options -> progress -> result flow and all backend/data/metadata/registry/PATH semantics remain. Existing artwork and Go/Win32 code are reused.

## Verification

- `go test ./...` from bootstrap — PASS, including operation boundary fixtures, navigation/failure/metadata tests, edit DC color regression and truthful completed-stage counting.
- `go vet .` from bootstrap — PASS. Previously observed whole-module vet warnings in cmd/synesis-folder-picker/main_windows.go:121-122 remain unrelated and unchanged.
- `go test -c -o build/installer-no-clipping.exe` — PASS. Test-only preview executor emits fixture events and never calls installer operations; production binary has no preview mode.
- Computer Use native fixture inspection — PASS on this Windows desktop: focused path, Ctrl+A selection, typed longer path, Back/main/return preserving the draft, uninstall read-only path and metadata checkbox, Installing -> Installed and Uninstalling -> Uninstalled clean footer transitions, progress count/fill/rings, dark vertical log scrollbar, and scrolling to earlier rows without a cut bottom line. Screenshots are in this task's tool outputs, not separate durable image files.
- `gradlew.bat :cli:nativeMcpLauncher --rerun-tasks --dependency-verification=strict --no-configuration-cache --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g --console=plain` — PASS.
- `gradlew.bat :cli:runnableInstaller --rerun-tasks -x :cli:runtimeImage -x :cli:installDist -x :cli:nativeMcpLauncher -x :mcp:jar --dependency-verification=strict --no-configuration-cache --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g --console=plain` — PASS, three packaging tasks executed with fresh native binaries and existing Java/runtime resources.
- Byte comparison — PASS: outer prefix equals native installer, embedded archive equals current ZIP, inner installer equals native installer, magic and big-endian footer length match.
- SetWindowTheme API reference: https://learn.microsoft.com/en-us/windows/win32/api/uxtheme/nf-uxtheme-setwindowtheme ; actual dark scrollbar behavior was verified in the native preview.

## Current artifact

- Path: cli/build/distributions/synesis-windows-x64.exe
- Size: 55,242,503 bytes
- SHA-256: `ab19c79a0f2fa1a2c1cc2637e880a58fc90ae66d6afc5b8309d2285de42cac89`
- Local logs: build/installer-visual-fixes-build.log, build/installer-visual-fixes-package.log, build/installer-visual-fixes-artifact.txt.

## Limits and corrected attempts

No real user installation, repair, uninstall, metadata deletion, registry/PATH mutation, new task, Synesis coordination action, commit or push was performed. Other display scales/Windows theme versions were not visually exercised. A sandboxed Gradle launch could not read local JDK security configuration; the same authorized build succeeded outside that sandbox. An initial artifact assertion placed the footer fields in the wrong order; it was corrected against cli/build.gradle.kts (magic followed by length), and the byte checks passed. Native integral-height styling alone did not prevent partial owner-drawn rows; visual inspection caught this and the final paint guard was verified.

---

# Historical implementation and artifacts (superseded)

# Current SYN-079 installer-flow continuation — 2026-09-14

This section supersedes the earlier artifact hash and narrower UI scope below. The task remains the existing SYN-079; no new task was created.

## Implementation

- Main action label follows Install/Uninstall selection. Location/options lead immediately to work; the confirmation enum, controls, copy and navigation are removed.
- Shared native progress view has four real operation stages, completed/current/pending/warning/failure states, an indeterminate blue activity indicator, and an owner-drawn scrollable monospaced log. No percentage, shortcut creation or daemon-stop operation is invented. Logs retain at most 200 entries of at most 512 Unicode characters.
- Backend callbacks report before/after existing verification, activation, shell integration and uninstall boundaries; previous function callers keep default behavior. Protocol registration remains the existing post-install operation with a warning on failure.
- Back/Next/Cancel are disabled while work runs. The backend has no safe cancellation contract, so no cancellation was added. Metadata selection and edited installation path persist through Back without changing the original uninstall target.
- Summaries distinguish Installed, Uninstalled, failure and registration warning; uninstall no longer suggests using an installed application. The result has a visible Close button.
- Existing artwork and Go/Win32 remain. Test-only preview code lives in the existing Windows test file, is explicitly labeled as validation, cannot perform installer operations and is absent from the production binary.

## Checks actually performed

Working directory for Go checks: bootstrap. Portable compiler: build/toolchain/go/bin/go.exe (Go 1.26.5). GOCACHE and GOMODCACHE point to bootstrap/build/go-cache and bootstrap/build/go-mod; build/go.mod isolates these build artifacts from ./... discovery.

- `go test ./...` — PASS, including real install/repair/uninstall fixture callbacks, failed PATH update and missing-bundle event sequences, preservation tests, direct native navigation, busy-state fencing and failure summaries.
- `go vet .` — PASS. `go vet ./...` reports two pre-existing possible unsafe.Pointer misuses in cmd/synesis-folder-picker/main_windows.go:121-122; that file is unchanged.
- `go test -c -o build/installer-preview-polished.exe` — PASS.
- `GOOS=linux GOARCH=amd64 go test -c -o build/bootstrap-linux.test` — PASS; cross-compilation only, not executed.
- Initial `go test ./...` accidentally traversed the newly downloaded compiler under the ignored build folder. A nested build/go.mod corrected test discovery; subsequent full suite passed. An outdated confirmation-navigation test was adapted to the new direct flow.
- Native install-location, main-page and initial progress screens inspected with Computer Use through non-operative test fixtures. Visual review found native green progress styling and an unnecessary log scrollbar; source was corrected to a blue owner-drawn activity indicator and measured log extent. The user stopped Computer Use with physical Escape before final visual inspection. No final pixel-parity claim is made.
- `gradlew.bat :cli:runnableInstaller --dependency-verification=strict --no-configuration-cache --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g --console=plain` — PASS. Uses portable Java 25.0.4.1 and existing pipeline, no Synesis application invocation.
- Incremental build rebuilt native binaries but reported platformBundle/platformZip/runnableInstaller up-to-date. Regenerated current package with `gradlew.bat :cli:runnableInstaller --rerun-tasks -x :cli:runtimeImage -x :cli:installDist -x :cli:nativeMcpLauncher -x :mcp:jar --dependency-verification=strict --no-configuration-cache --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g --console=plain` — PASS, three packaging tasks executed using current already-built inputs.
- Python byte comparison — PASS: package executable prefix equals current native installer, embedded archive equals current platform ZIP, inner bin/synesis-installer.exe equals current native installer, footer magic and big-endian archive length agree.
- `git diff --check` — PASS. No confirmation-page symbols remain in bootstrap.

## Artifact

- `cli/build/distributions/synesis-windows-x64.exe`
- Size: 55,229,364 bytes.
- SHA-256: `65ded8461c649365aada50611e88561c3ebff547d583cced7eb382802fa13be7`.
- Machine-local build logs: build/installer-build.log, build/installer-build-final.log, build/installer-package-verified.log, build/installer-artifact-verification.txt.
- Toolchain sources: https://go.dev/dl/ and https://learn.microsoft.com/en-us/java/openjdk/download . Portable downloads were checksum-verified; no global installation was performed.

## Limits

No real user installation, repair, uninstall, metadata removal or registry/PATH operation was executed. No commit, push, release, or new task was created. Final visual acceptance remains unverified after user interruption. Broader Java tests were outside this native installer change; Java compilation and package assembly passed.

---

## Historical narrower scope (superseded artifact)


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
