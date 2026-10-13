//go:build windows

package wizard

import (
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

// createControls creates every control once, hidden and unplaced. showPage
// places and reveals the ones a page uses.
func (w *installerWizard) createControls() {
	const (
		ssNoPrefix        = 0x0080 // a path may hold an ampersand
		esMultiline       = 0x0004
		esAutoVScroll     = 0x0040
		lbsOwnerDrawFixed = 0x0010
		lbsHasStrings     = 0x0040
		lbsNoIntegral     = 0x0100
		wsVScroll         = 0x00200000
	)
	label := uint32(win32.WsChild | win32.SsLeft | ssNoPrefix)
	line := label | win32.SsCenterImage | win32.SsEndEllipsis
	button := uint32(win32.WsChild | win32.WsTabStop | win32.BsOwnerDraw)
	edit := uint32(win32.WsChild | win32.EsAutoHScroll)
	w.createFonts()
	for _, control := range []struct {
		id    int
		class string
		text  string
		style uint32
		font  uintptr
	}{
		{controlTitle, "STATIC", "", label, w.titleFont},
		{controlDescription, "STATIC", "", label, w.bodyFont},

		{controlActionInstall, "BUTTON", "", button | win32.WsGroup, w.strongFont},
		{controlActionUninstall, "BUTTON", "Uninstall", button, w.strongFont},
		{controlActionPath, "STATIC", "", label | win32.SsCenterImage | win32.SsPathEllipsis, w.smallFont},

		{controlInstallGroup, "STATIC", "Install folder", label, w.detailFont},
		{controlInstallEdit, "EDIT", "", edit | win32.WsTabStop, w.bodyFont},
		{controlBrowse, "BUTTON", "Browse...", button, w.bodyFont},
		{controlInstallHint, "STATIC", "", label, w.smallFont},
		{controlCapabilities, "STATIC", "", label, w.strongFont},
		{controlCapabilityInstall, "STATIC", "", line, w.detailFont},
		{controlCapabilityLink, "STATIC", "", line, w.detailFont},
		{controlCapabilityData, "STATIC", "", line, w.detailFont},

		{controlShortcut, "BUTTON", "Add a Synesis UI shortcut to the desktop", button, w.bodyFont},

		{controlUninstallPathLabel, "STATIC", "Installation folder", label, w.detailFont},
		{controlUninstallPath, "EDIT", "", edit | win32.EsReadOnly, w.bodyFont},
		{controlMetadata, "BUTTON", "Remove metadata", button, w.bodyFont},
		{controlUninstallWarning, "STATIC", "", label, w.detailFont},
		{controlUninstallImpact, "STATIC", "", line, w.detailFont},

		{controlProgressBar, "STATIC", "", win32.WsChild | win32.SsOwnerDraw, 0},
		{controlProgressStatus, "STATIC", "", line, w.detailFont},
		{controlProgressCount, "STATIC", "", label | win32.SsRight | win32.SsCenterImage, w.detailFont},
		{controlProgressSteps, "STATIC", "", win32.WsChild | win32.SsOwnerDraw, 0},
		// A native list keeps the log rows accessible, selectable and scrollable.
		{controlProgressLog, "LISTBOX", "Operation log", win32.WsChild | win32.WsTabStop | lbsOwnerDrawFixed | lbsHasStrings | lbsNoIntegral | wsVScroll, w.logFont},

		{controlResult, "STATIC", "", label, w.bodyFont},
		{controlResultNextTitle, "STATIC", "Next steps", label, w.strongFont},
		{controlResultNext, "STATIC", "", label, w.detailFont},
		{controlResultDetailsToggle, "BUTTON", "Show details", button, w.detailFont},
		// Read-only, wrapping diagnostics: one vertical scrollbar, no horizontal
		// scrolling required to read an error or a long filesystem path.
		{controlResultDetails, "EDIT", "", win32.WsChild | win32.WsTabStop | win32.EsReadOnly | esMultiline | esAutoVScroll | wsVScroll, w.logFont},

		{controlBack, "BUTTON", "Back", button, w.bodyFont},
		{controlNext, "BUTTON", "Next", button, w.bodyFont},
		{controlCancel, "BUTTON", "Cancel", button, w.bodyFont},
	} {
		w.controls[control.id] = win32.CreateWindow(control.class, control.text, 0, control.style, 0, 0, 0, 0, w.hwnd, uintptr(control.id), w.instance)
		draw.SetFont(w.controls[control.id], control.font)
	}
	// Keep native scrolling and keyboard behavior while matching the dark theme.
	setWindowTheme := windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme")
	for _, id := range []int{controlProgressLog, controlResultDetails} {
		setWindowTheme.Call(w.controls[id], uintptr(unsafe.Pointer(win32.MustUTF16("DarkMode_Explorer"))), 0)
	}
	win32.SendMessage.Call(w.controls[controlResultDetails], 0x00c5, 256*1024, 0) // EM_SETLIMITTEXT
	for _, id := range []int{controlInstallEdit, controlUninstallPath} {
		// EM_SETMARGINS, both sides: the painted field supplies the inset, and the
		// text lines up with the label above it.
		win32.SendMessage.Call(w.controls[id], 0x00d3, 3, 0)
	}
	// WM_CHANGEUISTATE, UIS_SET of UISF_HIDEFOCUS: no focus ring until the
	// keyboard is used, as in a dialog.
	win32.SendMessage.Call(w.hwnd, 0x0127, 1|1<<16, 0)
	win32.SendMessage.Call(w.controls[controlProgressLog], lbSetItemHeight, 0, uintptr(w.scaled(installerLogRowHeight)))
	win32.SetEnabled(w.controls[controlActionUninstall], w.installed)
}
