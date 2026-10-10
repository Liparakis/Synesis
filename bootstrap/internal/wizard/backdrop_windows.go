//go:build windows

package wizard

import (
	"math"
	"unsafe"

	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

// installerAccentWidth is the design-space length of the accent mark that
// starts the rule under every page header.
const installerAccentWidth = 72

// One cached surface supplies both the parent and native child backgrounds.
// Pattern-brush origins are aligned to the parent so labels cannot punch flat
// rectangles through gradients, panel outlines or the branded header.
func (w *installerWizard) rebuildBackdrop() {
	if w.backdropBrush != 0 {
		win32.DeleteObject.Call(w.backdropBrush)
		w.backdropBrush = 0
	}
	w.backdrop.Dispose()
	var client win32.Rect
	win32.GetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	w.backdrop = draw.NewRaster(client.Right, client.Bottom)
	if w.backdrop == nil {
		return
	}
	r := w.backdrop
	for y := int32(0); y < r.Height; y++ {
		for x := int32(0); x < r.Width; x++ {
			v := float64(y) / float64(r.Height)
			dx := (float64(x)/float64(r.Width) - 0.84) / 0.52
			dy := (v - 0.08) / 0.55
			glow := math.Exp(-dx*dx - dy*dy)
			r.PutColor(x, y, draw.PaintColor{11 + 5*(1-v) + 5*glow, 15 + 7*(1-v) + 9*glow, 22 + 10*(1-v) + 15*glow})
		}
	}
	panel := func(x, y, width, height int32, top, bottom, border draw.PaintColor, radius int32) {
		r.GradientPanel(win32.Rect{Left: w.scaled(x), Top: w.scaled(y), Right: w.scaled(x + width), Bottom: w.scaled(y + height)}, top, bottom, border, float64(w.scaled(radius)))
	}
	if w.page == installerPageUninstall {
		panel(60, 300, 1280, 378, draw.PaintColor{30, 39, 50}, draw.PaintColor{19, 25, 33}, draw.PaintColor{58, 72, 88}, 18)
		panel(88, 436, 1224, 78, draw.PaintColor{27, 32, 43}, draw.PaintColor{27, 32, 43}, draw.PaintColor{69, 84, 102}, 10)
		panel(60, 704, 1280, 74, draw.PaintColor{47, 37, 22}, draw.PaintColor{32, 27, 21}, draw.PaintColor{100, 76, 35}, 18)
	}
	if w.page == installerPageInstall {
		panel(60, 390, 1040, 82, draw.PaintColor{27, 32, 43}, draw.PaintColor{27, 32, 43}, draw.PaintColor{74, 143, 207}, 10)
	}
	if w.page == installerPageResult {
		panel(60, 300, 1280, 270, draw.PaintColor{27, 35, 45}, draw.PaintColor{19, 25, 33}, draw.PaintColor{49, 62, 77}, 18)
		panel(88, 480, 1224, 64, draw.PaintColor{27, 32, 43}, draw.PaintColor{27, 32, 43}, draw.PaintColor{53, 65, 80}, 10)
		if w.resultDetailsOpen {
			panel(60, 656, 1280, 208, draw.PaintColor{27, 32, 43}, draw.PaintColor{27, 32, 43}, draw.PaintColor{49, 62, 77}, 10)
		}
	}
	accentY := w.headerDividerY() - 3
	panel(60, accentY, installerAccentWidth, 6, draw.PaintColor{96, 190, 255}, draw.PaintColor{28, 124, 237}, draw.PaintColor{60, 160, 248}, 3)
	w.drawArt(r.Device, "ribbon", w.scaled(1100), 0, w.scaled(300), w.scaled(310))
	w.drawArt(r.Device, "brand", client.Right-w.scaled(250), w.scaled(40), w.scaled(190), w.scaled(190))
	win32.GDI32.NewProc("GdiFlush").Call()
	w.backdropBrush, _, _ = win32.GDI32.NewProc("CreatePatternBrush").Call(r.Bitmap)
}

func (w *installerWizard) controlOrigin(control uintptr) win32.Point {
	origin := win32.Point{}
	win32.User32.NewProc("MapWindowPoints").Call(control, w.hwnd, uintptr(unsafe.Pointer(&origin)), 1)
	return origin
}

func (w *installerWizard) paintControlBackground(device, control uintptr) {
	var rect win32.Rect
	win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	if w.backdrop == nil {
		win32.FillRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.darkBrush)
		return
	}
	origin := w.controlOrigin(control)
	win32.GDI32.NewProc("BitBlt").Call(device, 0, 0, uintptr(rect.Right), uintptr(rect.Bottom), w.backdrop.Device, uintptr(origin.X), uintptr(origin.Y), 0x00cc0020)
}

func (w *installerWizard) drawSmoothIcon(device uintptr, x, y, radius int32, kind string, color uintptr) {
	if w.smoothIcons == nil {
		w.smoothIcons = make(map[string]*draw.Raster)
	}
	icon := w.smoothIcons[kind]
	if icon == nil {
		icon = draw.NewRaster(2*radius+2, 2*radius+2)
		if icon == nil {
			return
		}
		draw.PaintIcon(icon.Pixels, icon.Width, kind, draw.PaintRGB(color))
		w.smoothIcons[kind] = icon
	}
	icon.Draw(device, x-icon.Width/2, y-icon.Height/2)
}
