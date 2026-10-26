//go:build windows

package wizard

import (
	"errors"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
	"unsafe"

	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/wizard/win32"
)

// visibleControlRects returns the screen rectangle of every control the
// current page shows.
func visibleControlRects(w *installerWizard) map[int]win32.Rect {
	rects := make(map[int]win32.Rect)
	for id, control := range w.controls {
		style, _, _ := win32.User32.NewProc("GetWindowLongW").Call(control, uintptr(uint32(0xfffffff0)))
		if style&win32.WsVisible == 0 {
			continue
		}
		var rect win32.Rect
		win32.User32.NewProc("GetWindowRect").Call(control, uintptr(unsafe.Pointer(&rect)))
		rects[id] = rect
	}
	return rects
}

// requireClearLayout fails when two shown controls overlap, or when one leaves
// the page area right of the rail.
func requireClearLayout(t *testing.T, w *installerWizard, state string) {
	t.Helper()
	var client win32.Rect
	win32.GetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	origin := win32.Point{}
	win32.ClientToScreen.Call(w.hwnd, uintptr(unsafe.Pointer(&origin)))
	rects := visibleControlRects(w)
	if len(rects) < 3 {
		t.Fatalf("%s shows only %d controls", state, len(rects))
	}
	for id, rect := range rects {
		if rect.Right <= rect.Left || rect.Bottom <= rect.Top {
			t.Fatalf("%s: control %d has no size", state, id)
		}
		if rect.Left < origin.X+w.scaled(installerSidebarWidth) || rect.Top < origin.Y ||
			rect.Right > origin.X+client.Right || rect.Bottom > origin.Y+client.Bottom {
			t.Fatalf("%s: control %d leaves the page area: %+v", state, id, rect)
		}
		for other, otherRect := range rects {
			if other > id && rect.Left < otherRect.Right && otherRect.Left < rect.Right && rect.Top < otherRect.Bottom && otherRect.Top < rect.Bottom {
				t.Fatalf("%s: controls %d and %d overlap: %+v and %+v", state, id, other, rect, otherRect)
			}
		}
	}
}

func requireCopy(t *testing.T, w *installerWizard, state string, want map[int]string) {
	t.Helper()
	rects := visibleControlRects(w)
	for id, text := range want {
		if _, shown := rects[id]; !shown || win32.GetText(w.controls[id]) != text {
			t.Fatalf("%s: control %d = %q (shown %v), want %q", state, id, win32.GetText(w.controls[id]), shown, text)
		}
	}
}

func TestInstallerPagesShowTheirCopyOnAClearLayout(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	const root = `C:\Example\Synesis`
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: root,
		bundleVersion: "1.2.0", installedVersion: "1.1.0", steps: installerOperationSteps(installerActionInstall, false)}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer win32.DestroyWindow.Call(w.hwnd)

	requireClearLayout(t, w, "first page")
	requireCopy(t, w, "first page", map[int]string{
		controlTitle:           "Manage Synesis",
		controlActionInstall:   "Update",
		controlActionUninstall: "Uninstall",
		controlActionPath:      "Installed at " + root,
		controlNext:            "Next",
		controlCancel:          "Cancel",
	})
	if _, shown := visibleControlRects(w)[controlBack]; shown {
		t.Fatal("the first page offers Back")
	}

	w.showPage(installerPageInstall)
	requireClearLayout(t, w, "install page")
	requireCopy(t, w, "install page", map[int]string{
		controlTitle:             "Choose a location",
		controlInstallEdit:       root,
		controlBrowse:            "Browse...",
		controlCapabilities:      "Setup will",
		controlCapabilityInstall: "Install Synesis 1.2.0 in this folder",
		controlCapabilityLink:    "Register the synesis:// link handler",
		controlCapabilityData:    "Add the Synesis bin folder to your user PATH",
		controlShortcut:          "Add a Synesis UI shortcut to the desktop",
		controlAllowLink:         "Let other computers connect to this one (asks for administrator approval)",
		controlBack:              "Back",
		controlNext:              "Install",
		controlCancel:            "Cancel",
	})

	if w.shortcut {
		t.Fatal("a wizard built without the option starts with the shortcut chosen")
	}
	w.handleCommand(uint16(controlShortcut), win32.BnClicked)
	if !w.shortcut {
		t.Fatal("the shortcut option could not be chosen")
	}
	w.handleCommand(uint16(controlShortcut), win32.BnClicked)
	if w.allowLink {
		t.Fatal("a wizard built without the option starts with the firewall rule chosen")
	}
	w.handleCommand(uint16(controlAllowLink), win32.BnClicked)
	if !w.allowLink {
		t.Fatal("the firewall rule option could not be chosen")
	}
	w.handleCommand(uint16(controlAllowLink), win32.BnClicked)

	w.showPage(installerPageUninstall)
	requireClearLayout(t, w, "uninstall page")
	requireCopy(t, w, "uninstall page", map[int]string{
		controlTitle:              "Uninstall Synesis",
		controlDescription:        "Remove the application from this computer.\r\nYour projects, repositories, and workspace data will be preserved.",
		controlUninstallPathLabel: "Installation folder",
		controlUninstallPath:      root,
		controlMetadata:           "Remove metadata",
		controlUninstallWarning:   "Removes local identity and installer state.\r\nProject, repository, and workspace data stay intact.",
		controlUninstallImpact:    "Application files and the synesis:// handler will be removed.",
		controlBack:               "Back",
		controlNext:               "Uninstall",
		controlCancel:             "Cancel",
	})
	// Wrapping text must fit the room its page gives it.
	for _, wrapped := range []struct {
		page installerPage
		id   int
		font uintptr
	}{
		{installerPageAction, controlDescription, w.bodyFont},
		{installerPageAction, controlInstallHint, w.smallFont},
		{installerPageInstall, controlDescription, w.bodyFont},
		{installerPageInstall, controlInstallHint, w.smallFont},
		{installerPageUninstall, controlDescription, w.bodyFont},
		{installerPageUninstall, controlUninstallWarning, w.detailFont},
	} {
		w.showPage(wrapped.page)
		control := w.controls[wrapped.id]
		var room win32.Rect
		win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&room)))
		dc, _, _ := win32.GetDC.Call(control)
		previous, _, _ := win32.SelectObject.Call(dc, wrapped.font)
		measured := win32.Rect{Right: room.Right}
		win32.DrawText.Call(dc, uintptr(unsafe.Pointer(win32.MustUTF16(win32.GetText(control)))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&measured)), 0x400|win32.DtWordBreak) // DT_CALCRECT
		win32.SelectObject.Call(dc, previous)
		win32.ReleaseDC.Call(control, dc)
		if measured.Bottom > room.Bottom {
			t.Fatalf("page %d clips the text of control %d: needs %d, has %d", wrapped.page, wrapped.id, measured.Bottom, room.Bottom)
		}
	}
	if icon, _, _ := win32.SendMessage.Call(w.hwnd, 0x007f, win32.IconSmall, 0); icon == 0 || icon != w.windowIconSmall {
		t.Fatal("the window lost the Synesis icon")
	}

	w.showPage(installerPageProgress)
	requireClearLayout(t, w, "progress page")
	requireCopy(t, w, "progress page", map[int]string{controlTitle: "Installing Synesis", controlProgressCount: "0 of 4"})

	w.reportProgress("verify", "done", "Local bundle verified")
	w.showPage(installerPageResult)
	requireClearLayout(t, w, "result page")
	requireCopy(t, w, "result page", map[int]string{
		controlTitle:               "Installed",
		controlDescription:         "Synesis 1.2.0 is ready to use.",
		controlUninstallPath:       root,
		controlResultNextTitle:     "Next steps",
		controlResultDetailsToggle: "Show details",
		controlCancel:              "Close",
	})
	for _, phrase := range []string{"new terminal", "synesis --version", "synesis init"} {
		if !strings.Contains(win32.GetText(w.controls[controlResultNext]), phrase) {
			t.Fatalf("next steps do not mention %q", phrase)
		}
	}
	w.handleCommand(uint16(controlResultDetailsToggle), win32.BnClicked)
	requireClearLayout(t, w, "result page with details")
	shown := visibleControlRects(w)
	if _, next := shown[controlResultNext]; next {
		t.Fatal("open details do not replace the next steps")
	}
	if _, details := shown[controlResultDetails]; !details {
		t.Fatal("details did not open")
	}
	w.handleCommand(uint16(controlResultDetailsToggle), win32.BnClicked)
	requireCopy(t, w, "result page after closing details", map[int]string{controlResultNextTitle: "Next steps"})
	w.result.err = errors.New("failed")
	w.showPage(installerPageResult)
	requireClearLayout(t, w, "failed result page")
	if _, next := visibleControlRects(w)[controlResultNext]; next {
		t.Fatal("a failed install still offers next steps")
	}
	w.result = installerOperationResult{}

	// A machine without Synesis is offered a plain install and nothing to remove.
	w.installed, w.installedVersion = false, ""
	w.showPage(installerPageAction)
	requireClearLayout(t, w, "first page without an installation")
	requireCopy(t, w, "first page without an installation", map[int]string{controlTitle: "Install Synesis", controlActionInstall: "Install"})
	if _, shown := visibleControlRects(w)[controlActionPath]; shown {
		t.Fatal("a machine without Synesis is shown an installed location")
	}
	w.handleCommand(uint16(controlActionUninstall), win32.BnClicked)
	if w.action != installerActionInstall {
		t.Fatal("uninstall could be chosen with nothing installed")
	}
	w.installed = true

	w.handleCommand(uint16(controlActionUninstall), win32.BnClicked)
	w.goNext()
	if w.page != installerPageUninstall {
		t.Fatal("uninstall selection skipped options")
	}
	if focused, _, _ := win32.GetFocus.Call(); focused == w.controls[controlNext] {
		t.Fatal("the uninstall page opens with the focus on its destructive button")
	}
	win32.SetFocus.Call(w.controls[controlMetadata])
	w.handleCommand(idOK, win32.BnClicked)
	if w.page != installerPageUninstall || w.busy {
		t.Fatal("Enter confirmed the uninstall")
	}
	// Enter on a focused button presses that button, never the forward action.
	for _, id := range []int{controlBack, controlNext, controlCancel, controlBrowse, controlResultDetailsToggle} {
		if w.enterCommand(id) != id {
			t.Fatalf("Enter on button %d does not press it", id)
		}
	}
	for _, id := range []int{0, controlInstallEdit, controlMetadata, controlActionInstall} {
		if w.enterCommand(id) != idOK {
			t.Fatalf("Enter on control %d is not the forward action", id)
		}
	}
	w.handleCommand(uint16(controlMetadata), win32.BnClicked)
	w.goBack()
	w.goNext()
	if !w.metadata {
		t.Fatal("metadata selection not preserved after Back")
	}
	w.handleCommand(uint16(controlMetadata), win32.BnClicked)
	if w.metadata {
		t.Fatal("metadata could not be deselected")
	}
	w.goBack()
	if w.page != installerPageAction {
		t.Fatal("Back did not restore main page")
	}
}

func TestInstallerNamesTheActionAndItsSteps(t *testing.T) {
	w := &installerWizard{}
	for _, test := range []struct {
		installed                bool
		bundle, existing, expect string
	}{
		{false, "1.2.0", "", "Install"},
		{true, "", "1.1.0", "Install or update"},
		{true, "1.2.0", "", "Install or update"},
		{true, "1.2.0", "1.2.0", "Repair"},
		{true, "1.2.0", "1.1.0", "Update"},
	} {
		w.installed, w.bundleVersion, w.installedVersion = test.installed, test.bundle, test.existing
		if got := w.installActionTitle(); got != test.expect {
			t.Fatalf("action for bundle %q over %q = %q, want %q", test.bundle, test.existing, got, test.expect)
		}
	}
	if installerRailSteps(installerActionInstall) == installerRailSteps(installerActionUninstall) {
		t.Fatal("the rail names the same steps for install and uninstall")
	}
	for step, page := range []installerPage{installerPageAction, installerPageInstall, installerPageProgress, installerPageResult} {
		if page.railStep() != step {
			t.Fatalf("page %d is rail step %d, want %d", page, page.railStep(), step)
		}
	}
	if installerPageUninstall.railStep() != installerPageInstall.railStep() {
		t.Fatal("the two option pages are different rail steps")
	}
}

// The single-file installer opens before its bundle is unpacked: install waits
// for the bundle and reports the wait, uninstall never does.
func TestInstallerWaitsForAnUnpackingBundleOnlyToInstall(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	root := filepath.Join(t.TempDir(), "Synesis")
	unpacked := filepath.Join(t.TempDir(), "bundle")
	for _, test := range []struct {
		name       string
		action     installerAction
		bundle     string
		unpackErr  error
		wantBundle string
		wantRun    bool
		wantFirst  string
	}{
		{"install", installerActionInstall, unpacked, nil, unpacked, true, "unpack"},
		{"failed unpacking", installerActionInstall, "", errors.New("archive is damaged"), "", false, "unpack"},
		{"bundle around the install folder", installerActionInstall, filepath.Dir(root), nil, "", false, "unpack"},
		{"uninstall", installerActionUninstall, unpacked, nil, "", true, "shell"},
	} {
		w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: root, action: test.action}
		if err := w.createWindow(); err != nil {
			t.Fatal(err)
		}
		waited := false
		w.awaitBundle = func() (string, error) {
			waited = true
			return test.bundle, test.unpackErr
		}
		ran := make(chan string, 1)
		w.executeOperation = func(_, bundle string, _ installerAction, _ bool, _ install.OperationProgress) installerOperationResult {
			ran <- bundle
			return installerOperationResult{}
		}
		w.startOperation()
		if w.steps[0].id != test.wantFirst {
			t.Fatalf("%s: first step = %q, want %q", test.name, w.steps[0].id, test.wantFirst)
		}
		deadline := time.Now().Add(3 * time.Second)
		for finished := false; !finished; time.Sleep(time.Millisecond) {
			var message win32.Message
			got, _, _ := win32.User32.NewProc("PeekMessageW").Call(uintptr(unsafe.Pointer(&message)), w.hwnd, installerMessageDone, installerMessageDone, 1)
			finished = got != 0
			if !finished && time.Now().After(deadline) {
				t.Fatalf("%s: operation did not finish", test.name)
			}
		}
		select {
		case bundle := <-ran:
			if !test.wantRun || bundle != test.wantBundle {
				t.Fatalf("%s: operation ran with bundle %q", test.name, bundle)
			}
		default:
			if test.wantRun {
				t.Fatalf("%s: operation did not run", test.name)
			}
		}
		w.mu.Lock()
		failed := w.result.err != nil
		w.mu.Unlock()
		if waited != (test.action == installerActionInstall) {
			t.Fatalf("%s: waited for the bundle = %v", test.name, waited)
		}
		if failed == test.wantRun {
			t.Fatalf("%s: failure = %v", test.name, failed)
		}
		if failed && w.resultFailureDescription() != "The installer could not be unpacked." {
			t.Fatalf("%s: failure is described as %q", test.name, w.resultFailureDescription())
		}
		win32.DestroyWindow.Call(w.hwnd)
	}
}

// No wizard test may change the real firewall or ask for elevation.
func init() {
	allowLinkSessions = func(string) error { return nil }
}

// The elevation prompt stays Windows's, answered by the person; the label above
// says so and the rule is added through the injectable allowLinkSessions only.
func TestTheFirewallRuleIsTickedByDefault(t *testing.T) {
	w, err := newInstallerWizard(filepath.Join(t.TempDir(), "bundle"), "1.2.0")
	if err != nil {
		t.Fatal(err)
	}
	if !w.allowLink {
		t.Fatal("a new wizard does not start with the firewall rule chosen")
	}
}

func TestTheFirewallRuleIsAddedOnlyForAChosenInstallAndItsFailureIsAWarning(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	root := filepath.Join(t.TempDir(), "Synesis")
	for _, test := range []struct {
		name        string
		action      installerAction
		chosen      bool
		failure     error
		wantAsked   bool
		wantWarning string
	}{
		{"chosen install", installerActionInstall, true, nil, true, ""},
		{"declined approval", installerActionInstall, true, errors.New("administrator approval was declined"), true, "administrator approval was declined"},
		{"not chosen", installerActionInstall, false, nil, false, ""},
		{"uninstall", installerActionUninstall, true, nil, false, ""},
	} {
		w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: root, action: test.action, allowLink: test.chosen}
		if err := w.createWindow(); err != nil {
			t.Fatal(err)
		}
		w.executeOperation = func(string, string, installerAction, bool, install.OperationProgress) installerOperationResult {
			return installerOperationResult{}
		}
		asked := ""
		previous := allowLinkSessions
		allowLinkSessions = func(installDir string) error {
			asked = installDir
			return test.failure
		}
		w.startOperation()
		deadline := time.Now().Add(3 * time.Second)
		for finished := false; !finished; time.Sleep(time.Millisecond) {
			var message win32.Message
			got, _, _ := win32.User32.NewProc("PeekMessageW").Call(uintptr(unsafe.Pointer(&message)), w.hwnd, installerMessageDone, installerMessageDone, 1)
			finished = got != 0
			if !finished && time.Now().After(deadline) {
				t.Fatalf("%s: operation did not finish", test.name)
			}
		}
		allowLinkSessions = previous
		if (asked == root) != test.wantAsked {
			t.Fatalf("%s: firewall rule requested for %q", test.name, asked)
		}
		w.mu.Lock()
		result := w.result
		w.mu.Unlock()
		if result.err != nil || !strings.Contains(result.warning, test.wantWarning) || (test.wantWarning == "") != (result.warning == "") {
			t.Fatalf("%s: result = %+v", test.name, result)
		}
		win32.DestroyWindow.Call(w.hwnd)
	}
}
