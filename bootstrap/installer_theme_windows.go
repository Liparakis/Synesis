//go:build windows

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

func createInstallerFont(height int32, weight int32) uintptr {
	return createInstallerNamedFont(height, weight, "Segoe UI")
}

func createInstallerNamedFont(height int32, weight int32, name string) uintptr {
	face, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return 0
	}
	font, _, _ := installerCreateFont.Call(
		uintptr(uint32(height)),
		0,
		0,
		0,
		uintptr(uint32(weight)),
		0,
		0,
		0,
		uintptr(1),
		0,
		0,
		5, // CLEARTYPE_QUALITY
		0, // DEFAULT_PITCH | FF_DONTCARE
		uintptr(unsafe.Pointer(face)),
	)
	return font
}

func setInstallerFont(hwnd, font uintptr) {
	if hwnd != 0 && font != 0 {
		installerSendMessage.Call(hwnd, wmSetFont, font, 1)
	}
}

func installerRGB(red, green, blue byte) uintptr {
	return uintptr(red) | uintptr(green)<<8 | uintptr(blue)<<16
}

func (w *installerWizard) disposeFonts() {
	for _, font := range []uintptr{w.titleFont, w.bodyFont, w.detailFont, w.productFont, w.buttonFont, w.cardTitleFont, w.cardDetailFont, w.stepDetailFont, w.iconFont, w.wordmarkFont, w.logFont} {
		if font != 0 {
			installerDeleteObject.Call(font)
		}
	}
	w.titleFont = 0
	w.bodyFont = 0
	w.detailFont = 0
	w.productFont = 0
	w.buttonFont = 0
	w.cardTitleFont = 0
	w.cardDetailFont = 0
	w.stepDetailFont = 0
	w.iconFont = 0
	w.wordmarkFont = 0
}

func (w *installerWizard) disposeTheme() {
	if w.backdropBrush != 0 {
		installerDeleteObject.Call(w.backdropBrush)
		w.backdropBrush = 0
	}
	w.backdrop.dispose()
	w.backdrop = nil
	w.progressRaster.dispose()
	w.progressRaster = nil
	for _, icon := range w.smoothIcons {
		icon.dispose()
	}
	w.smoothIcons = nil
	for _, art := range w.art {
		installerDeleteObject.Call(art.bitmap)
	}
	w.art = nil
	if w.logoBitmap != 0 {
		installerDeleteObject.Call(w.logoBitmap)
		w.logoBitmap = 0
	}
	if w.darkBrush != 0 {
		installerDeleteObject.Call(w.darkBrush)
		w.darkBrush = 0
	}
	if w.surfaceBrush != 0 {
		installerDeleteObject.Call(w.surfaceBrush)
		w.surfaceBrush = 0
	}
	if w.cardBrush != 0 {
		installerDeleteObject.Call(w.cardBrush)
		w.cardBrush = 0
	}
	if w.warningBrush != 0 {
		installerDeleteObject.Call(w.warningBrush)
		w.warningBrush = 0
	}
}

func (w *installerWizard) colorStatic(device, hwnd uintptr) uintptr {
	installerSetBkMode.Call(device, transparent)
	red, green, blue := byte(226), byte(232), byte(240)
	id := w.installerControlID(hwnd)
	if id == controlUninstallPath || id == controlInstallEdit || id == controlResultDetails {
		return w.colorEdit(device)
	}
	switch id {
	case controlProductName:
		red, green, blue = 248, 250, 252
	case controlDescription:
		red, green, blue = 174, 186, 207
	case controlProductDetails, controlActionPath, controlInstallHint, controlUninstallWarning,
		controlCapabilityInstall, controlCapabilityLink, controlCapabilityData, controlResultNext:
		red, green, blue = 190, 201, 219
	case controlUninstallImpact:
		red, green, blue = 238, 241, 247
	case controlTitle:
		red, green, blue = 248, 250, 252
	}
	installerSetTextColor.Call(device, installerRGB(red, green, blue))
	if w.backdropBrush != 0 {
		origin := w.controlOrigin(hwnd)
		installerGDI32.NewProc("SetBrushOrgEx").Call(device, uintptr(-origin.x), uintptr(-origin.y), 0)
		return w.backdropBrush
	}
	return w.darkBrush
}

func (w *installerWizard) colorEdit(device uintptr) uintptr {
	// The edit control uses the DC background for text cells and the returned
	// brush for unused space. Both must match, including while typing/focused.
	installerSetBkMode.Call(device, opaque)
	installerSetBkColor.Call(device, installerRGB(27, 32, 43))
	installerSetTextColor.Call(device, installerRGB(241, 245, 249))
	return w.surfaceBrush
}

func installerStatusColor(status string) uintptr {
	switch status {
	case "done":
		return installerRGB(91, 212, 150)
	case "working":
		return installerRGB(81, 178, 250)
	case "warning":
		return installerRGB(239, 189, 93)
	case "failed":
		return installerRGB(245, 122, 122)
	default:
		return installerRGB(145, 160, 180)
	}
}
