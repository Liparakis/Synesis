//go:build windows

package wizard

import (
	"synesis/bootstrap/internal/wizard/win32"
)

func (w *installerWizard) disposeFonts() {
	for _, font := range []uintptr{w.titleFont, w.bodyFont, w.detailFont, w.productFont, w.buttonFont, w.cardTitleFont, w.cardDetailFont, w.stepDetailFont, w.iconFont, w.wordmarkFont, w.logFont} {
		if font != 0 {
			win32.DeleteObject.Call(font)
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
		win32.DeleteObject.Call(w.backdropBrush)
		w.backdropBrush = 0
	}
	w.backdrop.Dispose()
	w.backdrop = nil
	w.progressRaster.Dispose()
	w.progressRaster = nil
	for _, icon := range w.smoothIcons {
		icon.Dispose()
	}
	w.smoothIcons = nil
	for _, art := range w.art {
		win32.DeleteObject.Call(art.Bitmap)
	}
	w.art = nil
	if w.logoBitmap != 0 {
		win32.DeleteObject.Call(w.logoBitmap)
		w.logoBitmap = 0
	}
	if w.darkBrush != 0 {
		win32.DeleteObject.Call(w.darkBrush)
		w.darkBrush = 0
	}
	if w.surfaceBrush != 0 {
		win32.DeleteObject.Call(w.surfaceBrush)
		w.surfaceBrush = 0
	}
	if w.cardBrush != 0 {
		win32.DeleteObject.Call(w.cardBrush)
		w.cardBrush = 0
	}
	if w.warningBrush != 0 {
		win32.DeleteObject.Call(w.warningBrush)
		w.warningBrush = 0
	}
}

func (w *installerWizard) colorStatic(device, hwnd uintptr) uintptr {
	win32.SetBkMode.Call(device, win32.Transparent)
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
	win32.SetTextColor.Call(device, win32.RGB(red, green, blue))
	if w.backdropBrush != 0 {
		origin := w.controlOrigin(hwnd)
		win32.GDI32.NewProc("SetBrushOrgEx").Call(device, uintptr(-origin.X), uintptr(-origin.Y), 0)
		return w.backdropBrush
	}
	return w.darkBrush
}

func (w *installerWizard) colorEdit(device uintptr) uintptr {
	// The edit control uses the DC background for text cells and the returned
	// brush for unused space. Both must match, including while typing/focused.
	win32.SetBkMode.Call(device, win32.Opaque)
	win32.SetBkColor.Call(device, win32.RGB(27, 32, 43))
	win32.SetTextColor.Call(device, win32.RGB(241, 245, 249))
	return w.surfaceBrush
}
