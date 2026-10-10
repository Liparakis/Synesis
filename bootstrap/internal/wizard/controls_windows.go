//go:build windows

package wizard

import (
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

func (w *installerWizard) createControls() {
	child := uint32(win32.WsChild | win32.WsVisible)
	button := uint32(win32.WsChild | win32.WsVisible | win32.WsTabStop)
	static := uint32(win32.WsChild | win32.WsVisible | win32.SsLeft)

	w.controls[controlTitle] = w.addControl(controlTitle, "STATIC", "", static, 60, 48, 1280, 84, 0)
	w.controls[controlDescription] = w.addControl(controlDescription, "STATIC", "", static, 60, 132, 1280, 58, 0)
	w.controls[controlProductName] = w.addControl(controlProductName, "STATIC", "", static, 60, 218, 700, 46, 0)
	w.controls[controlProductDetails] = w.addControl(controlProductDetails, "STATIC", "", static, 60, 264, 820, 62, 0)
	w.controls[controlCapabilities] = w.addControl(controlCapabilities, "STATIC", "", static, 60, 220, 880, 38, 0)
	w.controls[controlCapabilityInstall] = w.addControl(controlCapabilityInstall, "STATIC", "", static, 120, 268, 950, 42, 0)
	w.controls[controlCapabilityLink] = w.addControl(controlCapabilityLink, "STATIC", "", static, 120, 312, 950, 42, 0)
	w.controls[controlCapabilityData] = w.addControl(controlCapabilityData, "STATIC", "", static, 120, 356, 950, 42, 0)

	w.controls[controlActionGroup] = w.addControl(controlActionGroup, "STATIC", "Choose an action", static, 60, 430, 1280, 40, 0)
	w.controls[controlActionInstall] = w.addControl(controlActionInstall, "BUTTON", "Install or update", button|win32.BsOwnerDraw|win32.WsGroup, 60, 490, 1280, 120, 0)
	w.controls[controlActionUninstall] = w.addControl(controlActionUninstall, "BUTTON", "Uninstall", button|win32.BsOwnerDraw, 60, 626, 1280, 120, 0)
	w.controls[controlActionPath] = w.addControl(controlActionPath, "STATIC", "", static|win32.SsLeftNoWrap, 60, 840, 900, 42, 0)

	w.controls[controlInstallGroup] = w.addControl(controlInstallGroup, "STATIC", "Destination folder", static, 60, 250, 1280, 40, 0)
	w.controls[controlInstallEdit] = w.addControl(controlInstallEdit, "EDIT", "", child|win32.WsTabStop|win32.EsAutoHScroll, 80, 310, 1020, 46, 0)
	w.controls[controlBrowse] = w.addControl(controlBrowse, "BUTTON", "Browse...", button|win32.BsOwnerDraw, 1120, 310, 220, 46, 0)
	w.controls[controlInstallHint] = w.addControl(controlInstallHint, "STATIC", "", static, 80, 382, 1180, 60, 0)

	// The options surface is painted by the parent so it cannot obscure sibling
	// controls when Windows repaints the page or switches focus.
	w.controls[controlUninstallGroup] = w.addControl(controlUninstallGroup, "STATIC", "Uninstall options", static, 88, 330, 1190, 40, 0)
	w.controls[controlUninstallPathLabel] = w.addControl(controlUninstallPathLabel, "STATIC", "Installation folder", static, 88, 380, 1190, 34, 0)
	w.controls[controlUninstallPath] = w.addControl(controlUninstallPath, "EDIT", "", child|win32.EsReadOnly|win32.EsAutoHScroll, 90, 418, 1220, 58, 0)
	w.controls[controlMetadata] = w.addControl(controlMetadata, "BUTTON", "Remove metadata", button|win32.BsOwnerDraw, 88, 510, 1220, 54, 0)
	w.controls[controlUninstallWarning] = w.addControl(controlUninstallWarning, "STATIC", "", static, 136, 568, 1200, 60, 0)
	w.controls[controlUninstallImpact] = w.addControl(controlUninstallImpact, "STATIC", "", static, 165, 684, 1120, 52, 0)

	w.controls[controlProgressStatus] = w.addControl(controlProgressStatus, "STATIC", "", static, 60, 300, 1280, 70, 0)
	w.controls[controlProgressBar] = w.addControl(controlProgressBar, "STATIC", "Operation in progress", child|win32.SsOwnerDraw, 60, 410, 1280, 34, 0)
	w.controls[controlProgressCount] = w.addControl(controlProgressCount, "STATIC", "", static, 1130, 344, 210, 40, 0)
	w.controls[controlProgressSteps] = w.addControl(controlProgressSteps, "STATIC", "", child|win32.SsOwnerDraw, 60, 350, 1280, 300, 0)
	w.controls[controlProgressLogTitle] = w.addControl(controlProgressLogTitle, "STATIC", "Operation log", static, 60, 662, 1280, 38, 0)
	// Native owner-drawn list keeps rows accessible, selectable and scrollable.
	// LBS_OWNERDRAWFIXED | LBS_HASSTRINGS, vertical/horizontal scroll. Keep the
	// default integral row height so scrolling never exposes half a line.
	w.controls[controlProgressLog] = w.addControl(controlProgressLog, "LISTBOX", "Operation log", child|win32.WsTabStop|0x20|0x40|0x00200000|0x00100000, 60, 706, 1280, 154, 0)
	// Keep native scrolling and keyboard behavior while matching the dark log.
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlProgressLog], uintptr(unsafe.Pointer(win32.MustUTF16("DarkMode_Explorer"))), 0)
	w.controls[controlResult] = w.addControl(controlResult, "STATIC", "", static, 60, 270, 1280, 330, 0)
	w.controls[controlResultDetailsToggle] = w.addControl(controlResultDetailsToggle, "BUTTON", "Show details", button|win32.BsOwnerDraw, 60, 596, 300, 52, 0)
	// Read-only, wrapping diagnostics: one vertical scrollbar, no horizontal
	// scrolling required to read an error or a long filesystem path.
	w.controls[controlResultDetails] = w.addControl(controlResultDetails, "EDIT", "", child|win32.WsTabStop|win32.EsReadOnly|0x4|0x40|0x00200000, 74, 668, 1252, 182, 0)
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlResultDetails], uintptr(unsafe.Pointer(win32.MustUTF16("DarkMode_Explorer"))), 0)
	win32.SendMessage.Call(w.controls[controlResultDetails], 0x00c5, 256*1024, 0) // EM_SETLIMITTEXT
	w.controls[controlResultNextTitle] = w.addControl(controlResultNextTitle, "STATIC", "Next steps", static, 60, 598, 1280, 40, 0)
	w.controls[controlResultNext] = w.addControl(controlResultNext, "STATIC", "", static, 60, 644, 1280, 126, 0)
	// Give the compact action row an even rhythm and enough visual weight for
	// the destructive primary action without changing its command routing.
	w.controls[controlBack] = w.addControl(controlBack, "BUTTON", "Back", button|win32.BsOwnerDraw, 780, 886, 185, 72, 0)
	w.controls[controlNext] = w.addControl(controlNext, "BUTTON", "Next  ›", button|win32.BsOwnerDraw, 985, 886, 175, 72, 0)
	w.controls[controlCancel] = w.addControl(controlCancel, "BUTTON", "Cancel", button|win32.BsOwnerDraw, 1175, 886, 165, 72, 0)

	font, _, _ := win32.GetStockObject.Call(win32.DefaultGuiFont)
	for _, control := range w.controls {
		win32.SendMessage.Call(control, win32.WmSetFont, font, 1)
	}
	// Keep the compact window footprint while giving the maintenance copy the
	// readable hierarchy of the reference composition.
	w.titleFont = draw.CreateFont(w.scaled(-64), win32.FontWeightBold)
	w.bodyFont = draw.CreateFont(w.scaled(-32), win32.FontWeightNormal)
	w.detailFont = draw.CreateFont(w.scaled(-28), win32.FontWeightNormal)
	w.productFont = draw.CreateFont(w.scaled(-38), win32.FontWeightBold)
	w.buttonFont = draw.CreateFont(w.scaled(-29), win32.FontWeightNormal)
	w.cardTitleFont = draw.CreateFont(w.scaled(-32), win32.FontWeightBold)
	w.cardDetailFont = draw.CreateFont(w.scaled(-27), win32.FontWeightNormal)
	w.stepDetailFont = draw.CreateFont(w.scaled(-24), win32.FontWeightNormal)
	w.iconFont = draw.CreateFont(w.scaled(-60), win32.FontWeightBold)
	w.wordmarkFont = draw.CreateFont(w.scaled(-16), win32.FontWeightNormal)
	w.logFont = draw.CreateNamedFont(w.scaled(-27), win32.FontWeightNormal, "Consolas")
	draw.SetFont(w.controls[controlTitle], w.titleFont)
	draw.SetFont(w.controls[controlDescription], w.bodyFont)
	draw.SetFont(w.controls[controlProductName], w.productFont)
	draw.SetFont(w.controls[controlProductDetails], w.detailFont)
	draw.SetFont(w.controls[controlCapabilities], w.bodyFont)
	draw.SetFont(w.controls[controlCapabilityInstall], w.bodyFont)
	draw.SetFont(w.controls[controlCapabilityLink], w.bodyFont)
	draw.SetFont(w.controls[controlCapabilityData], w.bodyFont)
	draw.SetFont(w.controls[controlActionGroup], w.bodyFont)
	draw.SetFont(w.controls[controlActionPath], w.detailFont)
	draw.SetFont(w.controls[controlInstallGroup], w.detailFont)
	draw.SetFont(w.controls[controlUninstallGroup], w.cardTitleFont)
	draw.SetFont(w.controls[controlUninstallPathLabel], w.detailFont)
	draw.SetFont(w.controls[controlUninstallPath], w.bodyFont)
	draw.SetFont(w.controls[controlMetadata], w.bodyFont)
	draw.SetFont(w.controls[controlUninstallWarning], w.detailFont)
	draw.SetFont(w.controls[controlUninstallImpact], w.buttonFont)
	draw.SetFont(w.controls[controlInstallEdit], w.bodyFont)
	draw.SetFont(w.controls[controlInstallHint], w.detailFont)
	draw.SetFont(w.controls[controlBrowse], w.buttonFont)
	draw.SetFont(w.controls[controlProgressStatus], w.bodyFont)
	draw.SetFont(w.controls[controlProgressCount], w.detailFont)
	draw.SetFont(w.controls[controlProgressLogTitle], w.cardTitleFont)
	draw.SetFont(w.controls[controlProgressLog], w.logFont)
	draw.SetFont(w.controls[controlResult], w.bodyFont)
	draw.SetFont(w.controls[controlResultDetailsToggle], w.detailFont)
	draw.SetFont(w.controls[controlResultDetails], w.logFont)
	draw.SetFont(w.controls[controlResultNextTitle], w.cardTitleFont)
	draw.SetFont(w.controls[controlResultNext], w.detailFont)
	win32.SendMessage.Call(w.controls[controlProgressLog], 0x01a0, 0, uintptr(w.scaled(34))) // LB_SETITEMHEIGHT
	draw.SetFont(w.controls[controlBack], w.buttonFont)
	draw.SetFont(w.controls[controlNext], w.buttonFont)
	draw.SetFont(w.controls[controlCancel], w.buttonFont)
	if !w.installed {
		win32.SetEnabled(w.controls[controlActionUninstall], false)
	}
}

func (w *installerWizard) addControl(id int, className, title string, style uint32, x, y, width, height int32, exStyle uint32) uintptr {
	return win32.CreateWindow(className, title, exStyle, style,
		w.scaled(x), w.scaled(y), w.scaled(width), w.scaled(height), w.hwnd, uintptr(id), w.instance)
}
