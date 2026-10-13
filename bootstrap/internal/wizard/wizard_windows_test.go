//go:build windows

package wizard

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
	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

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
		w.executeOperation = func(root, bundle string, got installerAction, metadata bool, observer install.OperationProgress) installerOperationResult {
			started <- got == action && root == w.installRoot && metadata
			observer("runtime", "working", "Fixture operation running")
			<-release
			return installerOperationResult{err: failure}
		}
		w.showPage(installerPageAction)
		w.goNext()
		want := "Install"
		if action == installerActionUninstall {
			want = "Uninstall"
		}
		if win32.GetText(w.controls[controlNext]) != want {
			t.Fatal("the button that starts the operation does not name it")
		}
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
			enabled, _, _ := win32.User32.NewProc("IsWindowEnabled").Call(w.controls[id])
			if enabled != 0 {
				t.Fatalf("busy control %d still enabled", id)
			}
		}
		w.goBack()
		w.goNext()
		w.handleCommand(uint16(controlMetadata), win32.BnClicked)
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
		if w.page != installerPageResult || w.busy || !strings.Contains(win32.GetText(w.controls[controlTitle]), "incomplete") {
			t.Fatal("failure shown as success")
		}
		visible, _, _ := win32.User32.NewProc("GetWindowLongW").Call(w.controls[controlCancel], uintptr(uint32(0xfffffff0)))
		if visible&win32.WsVisible == 0 || win32.GetText(w.controls[controlCancel]) != "Close" {
			t.Fatal("result has no Close control")
		}
		win32.DestroyWindow.Call(w.hwnd)
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
	// Preview at the display's own scale, as the installed wizard runs.
	win32.User32.NewProc("SetProcessDPIAware").Call()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`, bundle: `C:\Example\Bundle`, action: installerActionInstall,
		bundleVersion: "0.1.0-dev.lifecycle-fixes-20261006", installedVersion: "0.1.0-dev.capability-lock-20261006"}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	win32.SetText(w.hwnd, "Synesis Setup — UI validation (no operations)")
	// Interactive navigation exercises actual transitions with deterministic
	// fixture events. This function never calls an installer operation.
	w.executeOperation = func(root, bundle string, action installerAction, metadata bool, observer install.OperationProgress) installerOperationResult {
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
	defer win32.DestroyWindow.Call(w.hwnd)
	go func() {
		time.Sleep(5 * time.Minute)
		win32.User32.NewProc("PostThreadMessageW").Call(uintptr(threadID), 0x0012, 0, 0)
	}()
	// Consume an inherited SW_HIDE startup hint before the normal deferred show.
	win32.Show(w.hwnd)
	win32.PostMessage.Call(w.hwnd, installerMessageShow, 0, 0)
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
	defer win32.DestroyWindow.Call(w.hwnd)
	w.showPage(installerPageInstall)
	dc, _, _ := win32.CreateCompatibleDC.Call(0)
	defer win32.DeleteDC.Call(dc)
	for _, state := range []struct {
		message uintptr
		control int
	}{
		{win32.WmCtlColorEdit, controlInstallEdit},
		{win32.WmCtlColorStatic, controlInstallEdit},   // disabled edit
		{win32.WmCtlColorStatic, controlUninstallPath}, // read-only edit
		{win32.WmCtlColorStatic, controlResultDetails}, // wrapping diagnostics
	} {
		win32.SetBkColor.Call(dc, win32.RGB(255, 255, 255))
		win32.SetTextColor.Call(dc, 0)
		brush, _, _ := win32.SendMessage.Call(w.hwnd, state.message, dc, w.controls[state.control])
		background, _, _ := win32.GDI32.NewProc("GetBkColor").Call(dc)
		foreground, _, _ := win32.GDI32.NewProc("GetTextColor").Call(dc)
		if brush != w.fieldBrush || background != draw.Field.Ref() || foreground != draw.Copy.Ref() {
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
	defer win32.DestroyWindow.Call(w.hwnd)
	w.reportProgress("shell", "done", "Owned shell integration removed")
	w.reportProgress("runtime", "failed", "Access denied")
	w.result.err = errors.New("Access denied: " + strings.Repeat("long diagnostic path ", 25))
	w.showPage(installerPageResult)
	style := func(id int) uintptr {
		value, _, _ := win32.User32.NewProc("GetWindowLongW").Call(w.controls[id], uintptr(uint32(0xfffffff0)))
		return value
	}
	if win32.GetText(w.controls[controlTitle]) != "Uninstall incomplete" || win32.GetText(w.controls[controlDescription]) != "Application files could not be removed." {
		t.Fatal("failure is not clear without opening diagnostics")
	}
	if w.resultDetailsOpen || style(controlResultDetails)&win32.WsVisible != 0 || style(controlProgressLog)&win32.WsVisible != 0 {
		t.Fatal("raw logs clutter the initial result")
	}
	if !strings.Contains(win32.GetText(w.controls[controlResultDetails]), "Access denied") || !strings.Contains(win32.GetText(w.controls[controlResultDetails]), "Owned shell integration removed") {
		t.Fatal("diagnostics lost error or history")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), win32.BnClicked)
	if !w.resultDetailsOpen || style(controlResultDetails)&win32.WsVisible == 0 || win32.GetText(w.controls[controlResultDetailsToggle]) != "Hide details" {
		t.Fatal("details did not expand")
	}
	if style(controlResultDetails)&(0x00100000|win32.EsAutoHScroll) != 0 {
		t.Fatal("details require horizontal scrolling")
	}
	lineCount, _, _ := win32.SendMessage.Call(w.controls[controlResultDetails], 0x00ba, 0, 0) // EM_GETLINECOUNT
	if lineCount <= 4 {
		t.Fatal("long diagnostics did not wrap")
	}
	firstLine, _, _ := win32.SendMessage.Call(w.controls[controlResultDetails], 0x00ce, 0, 0) // EM_GETFIRSTVISIBLELINE
	if firstLine != 0 {
		t.Fatal("details did not start with the error summary")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), win32.BnClicked)
	if w.resultDetailsOpen || style(controlResultDetails)&win32.WsVisible != 0 {
		t.Fatal("details did not collapse")
	}
	if win32.GetText(w.controls[controlUninstallPath]) != w.installRoot {
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

func TestInstallerWindowKeepsItsProportionsAtTheDisplayScale(t *testing.T) {
	width, height, scale := installerWindowMetrics()
	if scale <= 0 {
		t.Fatalf("installer scale = %v, want positive", scale)
	}
	if width != int32(installerWindowWidth*scale+0.5) || height != int32(installerWindowHeight*scale+0.5) {
		t.Fatalf("installer window = %dx%d at scale %.2f", width, height, scale)
	}
	workWidth, _, _ := win32.GetSystemMetrics.Call(win32.SmCxWorkArea)
	workHeight, _, _ := win32.GetSystemMetrics.Call(win32.SmCyWorkArea)
	if width > int32(workWidth) || height > int32(workHeight) {
		t.Fatalf("installer window %dx%d does not fit the %dx%d work area", width, height, workWidth, workHeight)
	}
}

func TestInstallerWindowVisibilityIsDeferredUntilMessageLoop(t *testing.T) {
	if style := installerWindowStyle(); style&win32.WsVisible != 0 {
		t.Fatalf("installerWindowStyle() = %#x, visibility must be deferred", style)
	}
	if installerMessageShow <= win32.WmUser || installerMessageShow == installerMessageDone || installerMessageRefresh == installerMessageShow || installerMessageRefresh == installerMessageDone {
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

func TestInstallerOutcomeBadgesAreTintedDiscsWithSolidGlyphs(t *testing.T) {
	const size = 40
	for _, kind := range []string{"badge-done", "badge-failed", "badge-warning", "dot"} {
		pixels := make([]byte, size*size*4)
		draw.PaintIcon(pixels, size, kind, draw.PaintColor{91, 212, 150})
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

func TestInstallerLabelsUseAlignedGradientBackground(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer win32.DestroyWindow.Call(w.hwnd)
	w.showPage(installerPageUninstall)
	if w.backdrop == nil || w.backdropBrush == 0 {
		t.Fatal("gradient background not created")
	}
	for _, id := range []int{controlTitle, controlUninstallPathLabel, controlUninstallWarning, controlUninstallImpact} {
		origin := w.controlOrigin(w.controls[id])
		probe := draw.NewRaster(20, 10)
		if probe == nil {
			t.Fatal("create paint probe")
		}
		rect := win32.Rect{Right: 20, Bottom: 10}
		brush := w.colorStatic(probe.Device, w.controls[id])
		win32.FillRect.Call(probe.Device, uintptr(unsafe.Pointer(&rect)), brush)
		win32.GDI32.NewProc("GdiFlush").Call()
		got, want := probe.ColorAt(5, 5), w.backdrop.ColorAt(origin.X+5, origin.Y+5)
		probe.Dispose()
		if got != want {
			t.Fatalf("control %d paints a mismatched background: %v vs %v", id, got, want)
		}
	}
}
