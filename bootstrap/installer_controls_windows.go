//go:build windows

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

func (w *installerWizard) createControls() {
	child := uint32(wsChild | wsVisible)
	button := uint32(wsChild | wsVisible | wsTabStop)
	static := uint32(wsChild | wsVisible | ssLeft)

	w.controls[controlTitle] = w.addControl(controlTitle, "STATIC", "", static, 60, 48, 1280, 84, 0)
	w.controls[controlDescription] = w.addControl(controlDescription, "STATIC", "", static, 60, 132, 1280, 58, 0)
	w.controls[controlProductName] = w.addControl(controlProductName, "STATIC", "", static, 60, 218, 700, 46, 0)
	w.controls[controlProductDetails] = w.addControl(controlProductDetails, "STATIC", "", static, 60, 264, 820, 62, 0)
	w.controls[controlCapabilities] = w.addControl(controlCapabilities, "STATIC", "", static, 60, 220, 880, 38, 0)
	w.controls[controlCapabilityInstall] = w.addControl(controlCapabilityInstall, "STATIC", "", static, 120, 268, 950, 42, 0)
	w.controls[controlCapabilityLink] = w.addControl(controlCapabilityLink, "STATIC", "", static, 120, 312, 950, 42, 0)
	w.controls[controlCapabilityData] = w.addControl(controlCapabilityData, "STATIC", "", static, 120, 356, 950, 42, 0)

	w.controls[controlActionGroup] = w.addControl(controlActionGroup, "STATIC", "Choose an action", static, 60, 430, 1280, 40, 0)
	w.controls[controlActionInstall] = w.addControl(controlActionInstall, "BUTTON", "Install or update", button|bsOwnerDraw|wsGroup, 60, 490, 1280, 120, 0)
	w.controls[controlActionUninstall] = w.addControl(controlActionUninstall, "BUTTON", "Uninstall", button|bsOwnerDraw, 60, 626, 1280, 120, 0)
	w.controls[controlActionPath] = w.addControl(controlActionPath, "STATIC", "", static|ssLeftNoWrap, 60, 840, 900, 42, 0)

	w.controls[controlInstallGroup] = w.addControl(controlInstallGroup, "STATIC", "Destination folder", static, 60, 250, 1280, 40, 0)
	w.controls[controlInstallEdit] = w.addControl(controlInstallEdit, "EDIT", "", child|wsTabStop|esAutoHScroll, 80, 310, 1020, 46, 0)
	w.controls[controlBrowse] = w.addControl(controlBrowse, "BUTTON", "Browse...", button|bsOwnerDraw, 1120, 310, 220, 46, 0)
	w.controls[controlInstallHint] = w.addControl(controlInstallHint, "STATIC", "", static, 80, 382, 1180, 60, 0)

	// The options surface is painted by the parent so it cannot obscure sibling
	// controls when Windows repaints the page or switches focus.
	w.controls[controlUninstallGroup] = w.addControl(controlUninstallGroup, "STATIC", "Uninstall options", static, 88, 330, 1190, 40, 0)
	w.controls[controlUninstallPathLabel] = w.addControl(controlUninstallPathLabel, "STATIC", "Installation folder", static, 88, 380, 1190, 34, 0)
	w.controls[controlUninstallPath] = w.addControl(controlUninstallPath, "EDIT", "", child|esReadOnly|esAutoHScroll, 90, 418, 1220, 58, 0)
	w.controls[controlMetadata] = w.addControl(controlMetadata, "BUTTON", "Remove metadata", button|bsOwnerDraw, 88, 510, 1220, 54, 0)
	w.controls[controlUninstallWarning] = w.addControl(controlUninstallWarning, "STATIC", "", static, 136, 568, 1200, 60, 0)
	w.controls[controlUninstallImpact] = w.addControl(controlUninstallImpact, "STATIC", "", static, 165, 684, 1120, 52, 0)

	w.controls[controlProgressStatus] = w.addControl(controlProgressStatus, "STATIC", "", static, 60, 300, 1280, 70, 0)
	w.controls[controlProgressBar] = w.addControl(controlProgressBar, "STATIC", "Operation in progress", child|ssOwnerDraw, 60, 410, 1280, 34, 0)
	w.controls[controlProgressCount] = w.addControl(controlProgressCount, "STATIC", "", static, 1130, 344, 210, 40, 0)
	w.controls[controlProgressSteps] = w.addControl(controlProgressSteps, "STATIC", "", child|ssOwnerDraw, 60, 350, 1280, 300, 0)
	w.controls[controlProgressLogTitle] = w.addControl(controlProgressLogTitle, "STATIC", "Operation log", static, 60, 662, 1280, 38, 0)
	// Native owner-drawn list keeps rows accessible, selectable and scrollable.
	// LBS_OWNERDRAWFIXED | LBS_HASSTRINGS, vertical/horizontal scroll. Keep the
	// default integral row height so scrolling never exposes half a line.
	w.controls[controlProgressLog] = w.addControl(controlProgressLog, "LISTBOX", "Operation log", child|wsTabStop|0x20|0x40|0x00200000|0x00100000, 60, 706, 1280, 154, 0)
	// Keep native scrolling and keyboard behavior while matching the dark log.
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlProgressLog], uintptr(unsafe.Pointer(mustInstallerUTF16("DarkMode_Explorer"))), 0)
	w.controls[controlResult] = w.addControl(controlResult, "STATIC", "", static, 60, 270, 1280, 330, 0)
	w.controls[controlResultDetailsToggle] = w.addControl(controlResultDetailsToggle, "BUTTON", "Show details", button|bsOwnerDraw, 60, 596, 300, 52, 0)
	// Read-only, wrapping diagnostics: one vertical scrollbar, no horizontal
	// scrolling required to read an error or a long filesystem path.
	w.controls[controlResultDetails] = w.addControl(controlResultDetails, "EDIT", "", child|wsTabStop|esReadOnly|0x4|0x40|0x00200000, 74, 668, 1252, 182, 0)
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlResultDetails], uintptr(unsafe.Pointer(mustInstallerUTF16("DarkMode_Explorer"))), 0)
	installerSendMessage.Call(w.controls[controlResultDetails], 0x00c5, 256*1024, 0) // EM_SETLIMITTEXT
	w.controls[controlResultNextTitle] = w.addControl(controlResultNextTitle, "STATIC", "Next steps", static, 60, 598, 1280, 40, 0)
	w.controls[controlResultNext] = w.addControl(controlResultNext, "STATIC", "", static, 60, 644, 1280, 126, 0)
	// Give the compact action row an even rhythm and enough visual weight for
	// the destructive primary action without changing its command routing.
	w.controls[controlBack] = w.addControl(controlBack, "BUTTON", "Back", button|bsOwnerDraw, 780, 886, 185, 72, 0)
	w.controls[controlNext] = w.addControl(controlNext, "BUTTON", "Next  ›", button|bsOwnerDraw, 985, 886, 175, 72, 0)
	w.controls[controlCancel] = w.addControl(controlCancel, "BUTTON", "Cancel", button|bsOwnerDraw, 1175, 886, 165, 72, 0)

	font, _, _ := installerGetStockObject.Call(defaultGuiFont)
	for _, control := range w.controls {
		installerSendMessage.Call(control, wmSetFont, font, 1)
	}
	// Keep the compact window footprint while giving the maintenance copy the
	// readable hierarchy of the reference composition.
	w.titleFont = createInstallerFont(w.scaled(-64), fontWeightBold)
	w.bodyFont = createInstallerFont(w.scaled(-32), fontWeightNormal)
	w.detailFont = createInstallerFont(w.scaled(-28), fontWeightNormal)
	w.productFont = createInstallerFont(w.scaled(-38), fontWeightBold)
	w.buttonFont = createInstallerFont(w.scaled(-29), fontWeightNormal)
	w.cardTitleFont = createInstallerFont(w.scaled(-32), fontWeightBold)
	w.cardDetailFont = createInstallerFont(w.scaled(-27), fontWeightNormal)
	w.stepDetailFont = createInstallerFont(w.scaled(-24), fontWeightNormal)
	w.iconFont = createInstallerFont(w.scaled(-60), fontWeightBold)
	w.wordmarkFont = createInstallerFont(w.scaled(-16), fontWeightNormal)
	w.logFont = createInstallerNamedFont(w.scaled(-27), fontWeightNormal, "Consolas")
	setInstallerFont(w.controls[controlTitle], w.titleFont)
	setInstallerFont(w.controls[controlDescription], w.bodyFont)
	setInstallerFont(w.controls[controlProductName], w.productFont)
	setInstallerFont(w.controls[controlProductDetails], w.detailFont)
	setInstallerFont(w.controls[controlCapabilities], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityInstall], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityLink], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityData], w.bodyFont)
	setInstallerFont(w.controls[controlActionGroup], w.bodyFont)
	setInstallerFont(w.controls[controlActionPath], w.detailFont)
	setInstallerFont(w.controls[controlInstallGroup], w.detailFont)
	setInstallerFont(w.controls[controlUninstallGroup], w.cardTitleFont)
	setInstallerFont(w.controls[controlUninstallPathLabel], w.detailFont)
	setInstallerFont(w.controls[controlUninstallPath], w.bodyFont)
	setInstallerFont(w.controls[controlMetadata], w.bodyFont)
	setInstallerFont(w.controls[controlUninstallWarning], w.detailFont)
	setInstallerFont(w.controls[controlUninstallImpact], w.buttonFont)
	setInstallerFont(w.controls[controlInstallEdit], w.bodyFont)
	setInstallerFont(w.controls[controlInstallHint], w.detailFont)
	setInstallerFont(w.controls[controlBrowse], w.buttonFont)
	setInstallerFont(w.controls[controlProgressStatus], w.bodyFont)
	setInstallerFont(w.controls[controlProgressCount], w.detailFont)
	setInstallerFont(w.controls[controlProgressLogTitle], w.cardTitleFont)
	setInstallerFont(w.controls[controlProgressLog], w.logFont)
	setInstallerFont(w.controls[controlResult], w.bodyFont)
	setInstallerFont(w.controls[controlResultDetailsToggle], w.detailFont)
	setInstallerFont(w.controls[controlResultDetails], w.logFont)
	setInstallerFont(w.controls[controlResultNextTitle], w.cardTitleFont)
	setInstallerFont(w.controls[controlResultNext], w.detailFont)
	installerSendMessage.Call(w.controls[controlProgressLog], 0x01a0, 0, uintptr(w.scaled(34))) // LB_SETITEMHEIGHT
	setInstallerFont(w.controls[controlBack], w.buttonFont)
	setInstallerFont(w.controls[controlNext], w.buttonFont)
	setInstallerFont(w.controls[controlCancel], w.buttonFont)
	if !w.installed {
		setInstallerEnabled(w.controls[controlActionUninstall], false)
	}
}

func (w *installerWizard) addControl(id int, className, title string, style uint32, x, y, width, height int32, exStyle uint32) uintptr {
	return createInstallerWindow(className, title, exStyle, style,
		w.scaled(x), w.scaled(y), w.scaled(width), w.scaled(height), w.hwnd, uintptr(id), w.instance)
}
