//go:build windows

package main

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

func TestInstallerPageLayoutDoesNotOverlapSharedControls(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	windowRect := func(id int) installerRect {
		var rect installerRect
		installerUser32.NewProc("GetWindowRect").Call(w.controls[id], uintptr(unsafe.Pointer(&rect)))
		return rect
	}
	for _, page := range []installerPage{installerPageAction, installerPageUninstall, installerPageInstall, installerPageProgress, installerPageResult, installerPageAction} {
		w.showPage(page)
		description := windowRect(controlDescription)
		if page == installerPageUninstall {
			copy := map[int]string{
				controlTitle:              "Uninstall",
				controlDescription:        "Remove the application from this computer.\r\nYour projects, repositories, and workspace data will be preserved.",
				controlUninstallGroup:     "Uninstall options",
				controlUninstallPathLabel: "Installation folder",
				controlUninstallPath:      "C:\\Example\\Synesis",
				controlMetadata:           "Remove metadata",
				controlUninstallWarning:   "Removes local identity and installer state.\r\nProject, repository, and workspace data stay intact.",
				controlUninstallImpact:    "Application files and the synesis:// handler will be removed.",
				controlBack:               "Back",
				controlNext:               "Uninstall",
				controlCancel:             "Cancel",
			}
			for id, want := range copy {
				if got := getInstallerText(w.controls[id]); got != want {
					t.Fatalf("uninstall control %d copy = %q, want %q", id, got, want)
				}
			}
			if description.bottom >= windowRect(controlUninstallGroup).top {
				t.Fatal("uninstall description overlaps options")
			}
			path := windowRect(controlUninstallPath)
			label := windowRect(controlUninstallPathLabel)
			clientOrigin := installerPoint{}
			installerClientToScreen.Call(w.hwnd, uintptr(unsafe.Pointer(&clientOrigin)))
			if label.bottom+w.scaled(10) > clientOrigin.y+w.scaled(436) {
				t.Fatal("installation label overlaps the painted field border")
			}
			if path.top < clientOrigin.y+w.scaled(446) || path.bottom > clientOrigin.y+w.scaled(504) {
				t.Fatal("path text is not inset inside its frame")
			}
			metadata := windowRect(controlMetadata)
			metadataDescription := windowRect(controlUninstallWarning)
			dc, _, _ := installerGetDC.Call(w.controls[controlUninstallWarning])
			previous, _, _ := installerSelectObject.Call(dc, w.detailFont)
			measured := installerRect{right: metadataDescription.right - metadataDescription.left}
			installerDrawText.Call(dc, uintptr(unsafe.Pointer(mustInstallerUTF16(getInstallerText(w.controls[controlUninstallWarning])))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&measured)), 0x400|0x10) // DT_CALCRECT | DT_WORDBREAK
			installerSelectObject.Call(dc, previous)
			installerReleaseDC.Call(w.controls[controlUninstallWarning], dc)
			if measured.bottom > metadataDescription.bottom-metadataDescription.top {
				t.Fatal("metadata explanation text is clipped")
			}
			impact := windowRect(controlUninstallImpact)
			if path.bottom >= metadata.top {
				t.Fatal("path overlaps metadata toggle")
			}
			if metadata.bottom > metadataDescription.top {
				t.Fatal("metadata toggle overlaps its helper text")
			}
			if metadataDescription.bottom >= impact.top {
				t.Fatal("metadata explanation overlaps removal warning")
			}
			back := windowRect(controlBack)
			next := windowRect(controlNext)
			cancel := windowRect(controlCancel)
			if impact.bottom >= back.top {
				t.Fatal("uninstall options overlap the footer")
			}
			if back.top != next.top || back.bottom != next.bottom || next.top != cancel.top || next.bottom != cancel.bottom {
				t.Fatal("uninstall footer buttons do not share the main-page row height")
			}
			if back.right-back.left != next.right-next.left {
				t.Fatal("Back and Uninstall buttons do not share the same width")
			}
			if cancel.right != windowRect(controlActionInstall).right {
				t.Fatal("uninstall footer does not share the main-page right edge")
			}
			icon, _, _ := installerSendMessage.Call(w.hwnd, 0x007f, iconSmall, 0)
			if icon == 0 || icon != w.windowIconSmall {
				t.Fatal("uninstall page does not retain the Synesis window icon")
			}
			continue
		}
		if page == installerPageAction {
			copy := map[int]string{
				controlTitle:             "Manage installation",
				controlDescription:       "Choose what you want to do with the current installation.",
				controlProductDetails:    "",
				controlCapabilities:      "Setup includes",
				controlCapabilityInstall: "Local runtime",
				controlCapabilityLink:    "synesis:// link handler",
				controlCapabilityData:    "Existing project and workspace data is kept",
				controlActionGroup:       "Choose an action",
				controlActionInstall:     "Install or update",
				controlActionUninstall:   "Uninstall",
				controlActionPath:        "Installed at C:\\Example\\Synesis",
				controlNext:              "Install",
				controlCancel:            "Cancel",
			}
			for id, want := range copy {
				if got := getInstallerText(w.controls[id]); got != want {
					t.Fatalf("control %d copy = %q, want %q", id, got, want)
				}
			}
			title := windowRect(controlTitle)
			if title.bottom > description.top {
				t.Fatal("main title overlaps the subtitle")
			}
			if title.bottom-title.top < w.scaled(80) {
				t.Fatal("main title control is too short for the display font")
			}
			if title.right > title.left+w.scaled(1040) {
				t.Fatal("header intrudes into logo area")
			}
			for _, id := range []int{controlCapabilityInstall, controlCapabilityLink, controlCapabilityData} {
				row := windowRect(id)
				if row.bottom-row.top < w.scaled(40) {
					t.Fatalf("setup detail control %d is too short for readable body text", id)
				}
			}
			cancelRight := windowRect(controlCancel).right
			installRight := windowRect(controlActionInstall).right
			uninstallRight := windowRect(controlActionUninstall).right
			if cancelRight != installRight || cancelRight != uninstallRight {
				t.Fatalf("main-page Cancel edge is not aligned with the action cards: cancel=%d install=%d uninstall=%d", cancelRight, installRight, uninstallRight)
			}
			actionHeading := windowRect(controlActionGroup)
			installCard := windowRect(controlActionInstall)
			uninstallCard := windowRect(controlActionUninstall)
			if actionHeading.bottom+w.scaled(16) > installCard.top {
				t.Fatal("action heading does not have a clear gap above the first card")
			}
			if installCard.bottom+w.scaled(12) > uninstallCard.top {
				t.Fatal("action cards do not have a clear gap")
			}
			path := windowRect(controlActionPath)
			next := windowRect(controlNext)
			if uninstallCard.bottom+w.scaled(48) > next.top {
				t.Fatal("footer crowds the action cards")
			}
			if next.top-uninstallCard.bottom > w.scaled(100) {
				t.Fatal("main page leaves an excessive gap between actions and footer")
			}
			pathCenter := (path.top + path.bottom) / 2
			nextCenter := (next.top + next.bottom) / 2
			if pathCenter < nextCenter-1 || pathCenter > nextCenter+1 {
				t.Fatal("current installation is not vertically aligned with the action buttons")
			}
			var client installerRect
			installerGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
			clientOrigin := installerPoint{}
			installerClientToScreen.Call(w.hwnd, uintptr(unsafe.Pointer(&clientOrigin)))
			for _, id := range []int{controlNext, controlCancel} {
				rect := windowRect(id)
				if rect.bottom > clientOrigin.y+client.bottom {
					t.Fatalf("main-page control %d extends past the client area", id)
				}
			}
		}
	}
	w.action = installerActionUninstall
	w.showPage(installerPageAction)
	w.goNext()
	if w.page != installerPageUninstall {
		t.Fatal("uninstall selection skipped options")
	}
	w.handleCommand(uint16(controlMetadata), bnClicked)
	w.goBack()
	w.goNext()
	if !w.metadata {
		t.Fatal("metadata selection not preserved after Back")
	}
	w.handleCommand(uint16(controlMetadata), bnClicked)
	if w.metadata {
		t.Fatal("metadata could not be deselected")
	}
	w.goBack()
	if w.page != installerPageAction {
		t.Fatal("Back did not restore main page")
	}
}

func TestInstallerStartsDirectlyAndDisablesUnsafeNavigation(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	for _, action := range []installerAction{installerActionInstall, installerActionUninstall} {
		w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: filepath.Join(t.TempDir(), "Synesis"), bundle: filepath.Join(t.TempDir(), "bundle"), action: action, metadata: true}
		if err := w.createWindow(); err != nil {
			t.Fatal(err)
		}
		started, release := make(chan bool, 1), make(chan struct{})
		failure := errors.New("test operation stopped safely")
		w.executeOperation = func(root, bundle string, got installerAction, metadata bool, observer operationProgress) installerOperationResult {
			started <- got == action && root == w.installRoot && metadata
			observer("runtime", "working", "Fixture operation running")
			<-release
			return installerOperationResult{err: failure}
		}
		w.showPage(installerPageAction)
		w.updateActionLabel()
		want := "Install"
		if action == installerActionUninstall {
			want = "Uninstall"
		}
		if getInstallerText(w.controls[controlNext]) != want {
			t.Fatal("main action label mismatch")
		}
		w.goNext()
		w.goNext()
		if w.page != installerPageProgress || !w.busy {
			t.Fatal("options did not immediately start progress")
		}
		select {
		case valid := <-started:
			if !valid {
				t.Fatal("operation arguments changed")
			}
		case <-time.After(3 * time.Second):
			t.Fatal("worker not started")
		}
		for _, id := range []int{controlBack, controlNext, controlCancel} {
			enabled, _, _ := installerUser32.NewProc("IsWindowEnabled").Call(w.controls[id])
			if enabled != 0 {
				t.Fatalf("busy control %d still enabled", id)
			}
		}
		w.goBack()
		w.goNext()
		w.handleCommand(uint16(controlMetadata), bnClicked)
		if w.page != installerPageProgress || !w.metadata {
			t.Fatal("busy navigation changed operation state")
		}
		close(release)
		deadline := time.Now().Add(3 * time.Second)
		for {
			w.mu.Lock()
			done := w.result.err != nil
			w.mu.Unlock()
			if done {
				break
			}
			if time.Now().After(deadline) {
				t.Fatal("worker did not finish")
			}
			time.Sleep(time.Millisecond)
		}
		w.handleDone()
		if w.page != installerPageResult || w.busy || !strings.Contains(getInstallerText(w.controls[controlTitle]), "incomplete") {
			t.Fatal("failure shown as success")
		}
		visible, _, _ := installerUser32.NewProc("GetWindowLongW").Call(w.controls[controlCancel], uintptr(uint32(0xfffffff0)))
		if visible&wsVisible == 0 || getInstallerText(w.controls[controlCancel]) != "Close" {
			t.Fatal("result has no Close control")
		}
		installerDestroyWindow.Call(w.hwnd)
	}
}

func TestInstallerProgressRetainsFailureAndBoundsLogs(t *testing.T) {
	w := &installerWizard{steps: installerOperationSteps(installerActionInstall, false)}
	w.reportProgress("verify", "done", "Verified")
	w.reportProgress("runtime", "working", "Activating")
	w.reportProgress("", "failed", "Access denied\n"+strings.Repeat("x", 900))
	if w.steps[0].status != "done" || w.steps[1].status != "failed" || w.steps[2].status != "pending" {
		t.Fatal("failure rewrote completed or pending steps")
	}
	if len([]rune(w.logEntries[2].detail)) > 512 || strings.Contains(w.logEntries[2].detail, "\n") {
		t.Fatal("unbounded diagnostic text")
	}
	for i := 0; i < 250; i++ {
		w.reportProgress("runtime", "working", "bounded event")
	}
	if len(w.logEntries) != 200 {
		t.Fatal("unbounded log retention")
	}
	for _, remove := range []bool{false, true} {
		w.action, w.metadata, w.result = installerActionUninstall, remove, installerOperationResult{}
		text := w.resultText()
		if strings.Contains(text, "use the installed") || !strings.Contains(text, "left untouched") {
			t.Fatal("incorrect uninstall summary")
		}
		want := "preserved"
		if remove {
			want = "removed"
		}
		if !strings.Contains(text, "metadata was "+want) {
			t.Fatal("metadata result mismatch")
		}
	}
}

// Opt-in visual harness: never invokes installation, protocol or PATH operations.
// Only the test binary includes these fixture screens; production has no preview mode.

func TestInstallerVisualPreview(t *testing.T) {
	page := os.Getenv("SYNESIS_INSTALLER_VISUAL_PAGE")
	if page == "" {
		t.Skip("opt-in native visual inspection")
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`, bundle: `C:\Example\Bundle`, action: installerActionInstall,
		bundleVersion: "0.1.0-dev.lifecycle-fixes-20261006", installedVersion: "0.1.0-dev.capability-lock-20261006"}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	setInstallerText(w.hwnd, "Synesis Setup — UI validation (no operations)")
	// Interactive navigation exercises actual transitions with deterministic
	// fixture events. This function never calls an installer operation.
	w.executeOperation = func(root, bundle string, action installerAction, metadata bool, observer operationProgress) installerOperationResult {
		for _, step := range installerOperationSteps(action, metadata) {
			observer(step.id, "working", "Preview: "+step.title)
			time.Sleep(8 * time.Second)
			if page == "failure" && step.id == "runtime" {
				return installerOperationResult{err: errors.New("Preview: application files could not be updated; no operation was performed")}
			}
			observer(step.id, "done", "Preview: "+step.title+" completed")
		}
		return installerOperationResult{}
	}
	switch page {
	case "install", "failure":
		w.showPage(installerPageInstall)
	case "uninstall":
		w.action = installerActionUninstall
		w.showPage(installerPageUninstall)
	case "progress":
		w.busy = true
		w.steps = installerOperationSteps(w.action, false)
		w.showPage(installerPageProgress)
		w.reportProgress("verify", "done", "Fixture: local bundle verified")
		w.reportProgress("runtime", "working", "Fixture: preparing and verifying runtime files")
		w.reportProgress("runtime", "working", "Fixture: checking application payload")
		w.reportProgress("runtime", "working", "Fixture: verifying runtime contents")
		w.reportProgress("runtime", "working", "Fixture: preparing application files")
		w.refreshProgress()
	case "installed":
		w.showPage(installerPageResult)
	case "uninstalled":
		w.action = installerActionUninstall
		w.showPage(installerPageResult)
	case "uninstall-result":
		w.action = installerActionUninstall
		w.steps = installerOperationSteps(w.action, false)
		w.reportProgress("shell", "done", "Preview: owned shell integration removed")
		w.reportProgress("runtime", "working", "Preview: removing application files")
		w.result.err = errors.New("Preview: the application file could not be removed because it is in use by another process. Close the application before trying again. No operation was performed by this preview.")
		w.reportProgress("runtime", "failed", w.result.err.Error())
		w.showPage(installerPageResult)
	default:
		w.showPage(installerPageAction)
	}
	// Auto-close the test message loop without introducing a production cancel path.
	threadID := windows.GetCurrentThreadId()
	defer installerDestroyWindow.Call(w.hwnd)
	go func() {
		time.Sleep(5 * time.Minute)
		installerUser32.NewProc("PostThreadMessageW").Call(uintptr(threadID), 0x0012, 0, 0)
	}()
	// Consume an inherited SW_HIDE startup hint before the normal deferred show.
	showInstallerWindow(w.hwnd)
	installerPostMessage.Call(w.hwnd, installerMessageShow, 0, 0)
	if err := w.messageLoop(); err != nil {
		t.Fatal(err)
	}
}

func TestInstallerEditUsesReadableColorsInEveryState(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installRoot: `C:\Example\Synesis`}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	w.showPage(installerPageInstall)
	dc, _, _ := installerCreateCompatibleDC.Call(0)
	defer installerDeleteDC.Call(dc)
	for _, state := range []struct {
		message uintptr
		control int
	}{
		{wmCtlColorEdit, controlInstallEdit},
		{wmCtlColorStatic, controlInstallEdit},   // disabled edit
		{wmCtlColorStatic, controlUninstallPath}, // read-only edit
		{wmCtlColorStatic, controlResultDetails}, // wrapping diagnostics
	} {
		installerSetBkColor.Call(dc, installerRGB(255, 255, 255))
		installerSetTextColor.Call(dc, 0)
		brush, _, _ := installerSendMessage.Call(w.hwnd, state.message, dc, w.controls[state.control])
		background, _, _ := installerGDI32.NewProc("GetBkColor").Call(dc)
		foreground, _, _ := installerGDI32.NewProc("GetTextColor").Call(dc)
		if brush != w.surfaceBrush || background != installerRGB(27, 32, 43) || foreground != installerRGB(241, 245, 249) {
			t.Fatalf("edit state %#x colors: brush=%#x background=%#x text=%#x", state.message, brush, background, foreground)
		}
	}
}

func TestInstallerResultKeepsFailureClearAndDetailsCollapsible(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), action: installerActionUninstall, installRoot: `C:\Example\Synesis`, steps: installerOperationSteps(installerActionUninstall, false)}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	w.reportProgress("shell", "done", "Owned shell integration removed")
	w.reportProgress("runtime", "failed", "Access denied")
	w.result.err = errors.New("Access denied: " + strings.Repeat("long diagnostic path ", 25))
	w.showPage(installerPageResult)
	style := func(id int) uintptr {
		value, _, _ := installerUser32.NewProc("GetWindowLongW").Call(w.controls[id], uintptr(uint32(0xfffffff0)))
		return value
	}
	if getInstallerText(w.controls[controlTitle]) != "Uninstall incomplete" || getInstallerText(w.controls[controlDescription]) != "Application files could not be removed." {
		t.Fatal("failure is not clear without opening diagnostics")
	}
	if w.resultDetailsOpen || style(controlResultDetails)&wsVisible != 0 || style(controlProgressLog)&wsVisible != 0 {
		t.Fatal("raw logs clutter the initial result")
	}
	if !strings.Contains(getInstallerText(w.controls[controlResultDetails]), "Access denied") || !strings.Contains(getInstallerText(w.controls[controlResultDetails]), "Owned shell integration removed") {
		t.Fatal("diagnostics lost error or history")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), bnClicked)
	if !w.resultDetailsOpen || style(controlResultDetails)&wsVisible == 0 || getInstallerText(w.controls[controlResultDetailsToggle]) != "Hide details" {
		t.Fatal("details did not expand")
	}
	if style(controlResultDetails)&(0x00100000|esAutoHScroll) != 0 {
		t.Fatal("details require horizontal scrolling")
	}
	lineCount, _, _ := installerSendMessage.Call(w.controls[controlResultDetails], 0x00ba, 0, 0) // EM_GETLINECOUNT
	if lineCount <= 4 {
		t.Fatal("long diagnostics did not wrap")
	}
	firstLine, _, _ := installerSendMessage.Call(w.controls[controlResultDetails], 0x00ce, 0, 0) // EM_GETFIRSTVISIBLELINE
	if firstLine != 0 {
		t.Fatal("details did not start with the error summary")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), bnClicked)
	if w.resultDetailsOpen || style(controlResultDetails)&wsVisible != 0 {
		t.Fatal("details did not collapse")
	}
	if getInstallerText(w.controls[controlUninstallPath]) != w.installRoot {
		t.Fatal("result path was lost")
	}
}

func TestInstallerProgressCountsOnlyCompletedSteps(t *testing.T) {
	steps := installerOperationSteps(installerActionInstall, false)
	if got := completedInstallerSteps(steps); got != 0 {
		t.Fatalf("pending steps counted: %d", got)
	}
	steps[0].status, steps[1].status, steps[2].status, steps[3].status = "done", "working", "warning", "failed"
	if got := completedInstallerSteps(steps); got != 1 {
		t.Fatalf("incomplete or failed steps counted: %d", got)
	}
	for i := range steps {
		steps[i].status = "done"
	}
	if got := completedInstallerSteps(steps); got != len(steps) {
		t.Fatalf("completed steps = %d", got)
	}
}

func TestInstallerWindowMetricsUseCompactMaintenanceSize(t *testing.T) {
	width, height, scale := installerWindowMetrics()
	if scale <= 0 {
		t.Fatalf("installer scale = %v, want positive", scale)
	}
	if width < 720 || width > 780 || height < 480 || height > 540 {
		t.Fatalf("installer window = %dx%d at scale %.2f, want compact 720-780 by 480-540", width, height, scale)
	}
}

func TestInstallerWindowVisibilityIsDeferredUntilMessageLoop(t *testing.T) {
	if style := installerWindowStyle(); style&wsVisible != 0 {
		t.Fatalf("installerWindowStyle() = %#x, visibility must be deferred", style)
	}
	if installerMessageShow <= wmUser || installerMessageShow == installerMessageDone || installerMessageRefresh == installerMessageShow || installerMessageRefresh == installerMessageDone {
		t.Fatalf("installerMessageShow = %#x, want a distinct private window message", installerMessageShow)
	}
}

func TestInstallerDescribesVersionsAndOutcome(t *testing.T) {
	const kept = "Your identity, projects, and workspace data are kept."
	w := &installerWizard{installed: true}
	for _, test := range []struct {
		installed                bool
		bundle, existing, expect string
	}{
		{true, "", "1.1.0", "Keep your identity, projects, and workspace data."},
		{false, "1.2.0", "", "Install Synesis 1.2.0."},
		{true, "1.2.0", "", "Install Synesis 1.2.0. " + kept},
		{true, "1.2.0", "1.2.0", "Reinstall 1.2.0. " + kept},
		{true, "1.2.0", "1.1.0", "Update 1.1.0 to 1.2.0."},
	} {
		w.installed, w.bundleVersion, w.installedVersion = test.installed, test.bundle, test.existing
		if got := w.installActionDetail(); got != test.expect {
			t.Fatalf("install detail for bundle %q over %q = %q, want %q", test.bundle, test.existing, got, test.expect)
		}
	}

	bundle := t.TempDir()
	if installerBundleVersion(bundle) != "" {
		t.Fatal("a bundle without a VERSION file reported a version")
	}
	if err := os.WriteFile(filepath.Join(bundle, "VERSION"), []byte("1.2.0-dev.build_7\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := installerBundleVersion(bundle); got != "1.2.0-dev.build_7" {
		t.Fatalf("bundle version = %q", got)
	}
	// The value is painted into the window; anything that is not a plain version is dropped.
	if err := os.WriteFile(filepath.Join(bundle, "VERSION"), []byte("1.2.0\nsecond line"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := installerBundleVersion(bundle); got != "" {
		t.Fatalf("malformed bundle version was accepted: %q", got)
	}

	w = &installerWizard{page: installerPageResult, action: installerActionInstall}
	if kind, _ := w.resultBadge(); kind != "badge-done" || !w.showsNextSteps() {
		t.Fatal("a successful install does not show success and next steps")
	}
	w.resultDetailsOpen = true
	if w.showsNextSteps() {
		t.Fatal("next steps stay visible underneath open details")
	}
	w.resultDetailsOpen = false
	w.result.warning = "link handler not registered"
	if kind, _ := w.resultBadge(); kind != "badge-warning" || !w.showsNextSteps() {
		t.Fatal("a warning hides the next steps or is shown as plain success")
	}
	w.result.err = errors.New("failed")
	if kind, _ := w.resultBadge(); kind != "badge-failed" || w.showsNextSteps() {
		t.Fatal("a failed install is not marked failed or still offers next steps")
	}
	w.result = installerOperationResult{}
	w.action = installerActionUninstall
	if w.showsNextSteps() {
		t.Fatal("an uninstall offers install next steps")
	}
}

func TestInstallerInstallAndResultPagesKeepAddedContentClear(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`, action: installerActionInstall,
		bundleVersion: "1.2.0", installedVersion: "1.1.0", steps: installerOperationSteps(installerActionInstall, false)}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	windowRect := func(id int) installerRect {
		var rect installerRect
		installerUser32.NewProc("GetWindowRect").Call(w.controls[id], uintptr(unsafe.Pointer(&rect)))
		return rect
	}
	visible := func(id int) bool {
		value, _, _ := installerUser32.NewProc("GetWindowLongW").Call(w.controls[id], uintptr(uint32(0xfffffff0)))
		return value&wsVisible != 0
	}

	w.showPage(installerPageInstall)
	if getInstallerText(w.controls[controlTitle]) != "Choose install location" {
		t.Fatal("install page title is not sentence case like the other pages")
	}
	want := map[int]string{
		controlCapabilities:      "Setup will",
		controlCapabilityInstall: "Install Synesis 1.2.0 in this folder",
		controlCapabilityLink:    "Register the synesis:// link handler",
		controlCapabilityData:    "Add the Synesis bin folder to your user PATH",
	}
	for id, text := range want {
		if !visible(id) || getInstallerText(w.controls[id]) != text {
			t.Fatalf("install page control %d = %q (visible %v), want %q", id, getInstallerText(w.controls[id]), visible(id), text)
		}
	}
	if windowRect(controlInstallHint).bottom > windowRect(controlCapabilities).top {
		t.Fatal("install hint overlaps the list of changes")
	}
	previous := windowRect(controlCapabilities)
	for _, id := range []int{controlCapabilityInstall, controlCapabilityLink, controlCapabilityData} {
		row := windowRect(id)
		if previous.bottom > row.top {
			t.Fatalf("install page row %d overlaps the row above it", id)
		}
		previous = row
	}
	if previous.bottom+w.scaled(48) > windowRect(controlBack).top {
		t.Fatal("list of changes crowds the footer")
	}

	w.reportProgress("verify", "done", "Local bundle verified")
	w.showPage(installerPageResult)
	if getInstallerText(w.controls[controlDescription]) != "Synesis 1.2.0 is ready to use." {
		t.Fatalf("result description = %q", getInstallerText(w.controls[controlDescription]))
	}
	if !visible(controlResultNextTitle) || !visible(controlResultNext) || !visible(controlResultDetailsToggle) {
		t.Fatal("a successful install does not show next steps beside its details link")
	}
	for _, phrase := range []string{"new terminal", "synesis --version", "synesis init"} {
		if !strings.Contains(getInstallerText(w.controls[controlResultNext]), phrase) {
			t.Fatalf("next steps do not mention %q", phrase)
		}
	}
	if windowRect(controlTitle).left != windowRect(controlDescription).left || windowRect(controlTitle).left <= windowRect(controlResult).left {
		t.Fatal("result header is not one block clear of the status badge")
	}
	if windowRect(controlResultNextTitle).bottom > windowRect(controlResultNext).top ||
		windowRect(controlResultNext).bottom > windowRect(controlResultDetailsToggle).top ||
		windowRect(controlResultDetailsToggle).bottom > windowRect(controlCancel).top {
		t.Fatal("next steps, details link and footer overlap")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), bnClicked)
	if visible(controlResultNextTitle) || visible(controlResultNext) || !visible(controlResultDetails) {
		t.Fatal("open details do not replace the next steps")
	}
	if windowRect(controlResultDetailsToggle).bottom > windowRect(controlResultDetails).top {
		t.Fatal("details link overlaps the open details")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), bnClicked)
	if !visible(controlResultNext) || visible(controlResultDetails) {
		t.Fatal("closing details did not restore the next steps")
	}

	w.result.err = errors.New("failed")
	w.showPage(installerPageResult)
	if visible(controlResultNextTitle) || visible(controlResultNext) {
		t.Fatal("a failed install still offers next steps")
	}
}

func TestInstallerOutcomeBadgesAreTintedDiscsWithSolidGlyphs(t *testing.T) {
	const size = 40
	for _, kind := range []string{"badge-done", "badge-failed", "badge-warning", "dot"} {
		pixels := make([]byte, size*size*4)
		paintInstallerIcon(pixels, size, kind, installerPaintColor{91, 212, 150})
		alphaAt := func(x, y int) byte { return pixels[(y*size+x)*4+3] }
		if alphaAt(0, 0) != 0 {
			t.Fatalf("%s paints outside its disc", kind)
		}
		for i := 0; i < len(pixels); i += 4 {
			if pixels[i] > pixels[i+3] || pixels[i+1] > pixels[i+3] || pixels[i+2] > pixels[i+3] {
				t.Fatalf("%s is not premultiplied at byte %d", kind, i)
			}
		}
		if kind == "dot" {
			if alphaAt(size/2, size/2) != 255 {
				t.Fatal("dot is not solid at its center")
			}
			continue
		}
		// Left of the glyph and inside the ring only the tint shows.
		if tint := alphaAt(8, size/2-6); tint == 0 || tint > 96 {
			t.Fatalf("%s interior alpha = %d, want a light tint", kind, tint)
		}
		if alphaAt(size/2, 1) < 128 {
			t.Fatalf("%s has no solid ring", kind)
		}
		solid := 0
		for y := size / 4; y < 3*size/4; y++ {
			for x := size / 4; x < 3*size/4; x++ {
				if alphaAt(x, y) == 255 {
					solid++
				}
			}
		}
		if solid < 12 {
			t.Fatalf("%s has no solid glyph", kind)
		}
	}
}
