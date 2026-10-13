//go:build windows

package wizard

import (
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

func (w *installerWizard) createFonts() {
	const semibold = 600
	w.titleFont = draw.CreateFont(w.scaled(-26), semibold)
	w.wordmarkFont = draw.CreateFont(w.scaled(-17), semibold)
	w.strongFont = draw.CreateFont(w.scaled(-14), semibold)
	w.bodyFont = draw.CreateFont(w.scaled(-14), win32.FontWeightNormal)
	w.detailFont = draw.CreateFont(w.scaled(-13), win32.FontWeightNormal)
	w.smallFont = draw.CreateFont(w.scaled(-12), win32.FontWeightNormal)
	w.logFont = draw.CreateNamedFont(w.scaled(-12), win32.FontWeightNormal, "Consolas")
}

func (w *installerWizard) disposeTheme() {
	for _, object := range []*uintptr{&w.titleFont, &w.wordmarkFont, &w.strongFont, &w.bodyFont, &w.detailFont, &w.smallFont, &w.logFont,
		&w.backdropBrush, &w.windowBrush, &w.fieldBrush, &w.lineBrush} {
		if *object != 0 {
			win32.DeleteObject.Call(*object)
			*object = 0
		}
	}
	for _, raster := range []**draw.Raster{&w.backdrop, &w.progressRaster, &w.logo} {
		(*raster).Dispose()
		*raster = nil
	}
	for _, icon := range w.smoothIcons {
		icon.Dispose()
	}
	w.smoothIcons = nil
}

// colorStatic colors a label and hands back the backdrop as its background,
// aligned to the window, so a label never punches a flat rectangle through a
// panel it sits on.
func (w *installerWizard) colorStatic(device, hwnd uintptr) uintptr {
	id := w.installerControlID(hwnd)
	// Read-only and disabled edits ask for their colors as statics.
	if id == controlUninstallPath || id == controlInstallEdit || id == controlResultDetails {
		return w.colorEdit(device)
	}
	color := draw.Muted
	switch id {
	case controlTitle, controlResult, controlCapabilities, controlResultNextTitle, controlUninstallImpact:
		color = draw.Copy
	case controlActionPath, controlInstallGroup, controlInstallHint, controlUninstallPathLabel, controlProgressCount:
		color = draw.Faint
	}
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, color.Ref())
	if w.backdropBrush == 0 {
		return w.windowBrush
	}
	origin := w.controlOrigin(hwnd)
	win32.SetBrushOrgEx.Call(device, uintptr(-origin.X), uintptr(-origin.Y), 0)
	return w.backdropBrush
}

func (w *installerWizard) colorEdit(device uintptr) uintptr {
	// The edit control uses the DC background for text cells and the returned
	// brush for unused space. Both must match, including while typing/focused.
	win32.SetBkMode.Call(device, win32.Opaque)
	win32.SetBkColor.Call(device, draw.Field.Ref())
	win32.SetTextColor.Call(device, draw.Copy.Ref())
	return w.fieldBrush
}
