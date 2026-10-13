//go:build windows

package wizard

import (
	"fmt"
	"unsafe"

	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

// One cached surface supplies both the parent and native child backgrounds.
// Pattern-brush origins are aligned to the parent so labels cannot punch flat
// rectangles through the panels they sit on.
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
	const left, width = installerContentLeft, installerContentWidth
	panel := func(x, y, width, height int32, fill, border draw.PaintColor) {
		r.Panel(w.scaledRect(x, y, width, height), fill, border, float64(w.scaled(6)))
	}
	// Pixels first: GDI draws the marks and the text over them afterwards.
	r.Fill(client, draw.Window)
	rail := w.scaled(installerSidebarWidth)
	r.Fill(win32.Rect{Right: rail, Bottom: client.Bottom}, draw.Sidebar)
	r.Fill(win32.Rect{Left: rail, Right: rail + 1, Bottom: client.Bottom}, draw.Line)
	footer := w.scaled(installerFooterTop)
	r.Fill(win32.Rect{Left: w.scaled(left), Top: footer, Right: w.scaled(left + width), Bottom: footer + 1}, draw.Line)
	switch w.page {
	case installerPageInstall:
		panel(left, 152, width-108, installerFieldHeight, draw.Field, draw.LineStrong)
	case installerPageUninstall:
		panel(left, 152, width, installerFieldHeight, draw.Field, draw.Line)
		panel(left, 304, width, 44, draw.Mix(draw.Window, draw.Amber, 0.10), draw.Mix(draw.Window, draw.Amber, 0.35))
	case installerPageProgress:
		panel(left, 364, width, installerLogRows*installerLogRowHeight+16, draw.Field, draw.Line)
	case installerPageResult:
		panel(left, 128, width, 116, draw.Field, draw.Line)
		if w.resultDetailsOpen {
			panel(left, 262, width, 180, draw.Field, draw.Line)
		}
	}

	w.logo.Draw(r.Device, w.scaled(16), w.scaled(18))
	w.drawText(r.Device, w.wordmarkFont, "Synesis", w.scaledRect(64, 22, 136, 24), draw.Copy, win32.DtSingleLine|win32.DtVCenter)
	w.drawText(r.Device, w.smallFont, "Setup", w.scaledRect(64, 44, 136, 16), draw.Faint, win32.DtSingleLine|win32.DtVCenter)
	current := w.page.railStep()
	if w.page == installerPageResult && w.result.err == nil {
		current++ // a finished operation leaves no step open
	}
	for i, name := range installerRailSteps(w.action) {
		y := 116 + int32(i)*34
		kind, radius, color, font := "pending", int32(4), draw.Faint, w.bodyFont
		switch {
		case i < current:
			kind, radius, color = "check", 6, draw.Muted
		case i == current:
			kind, color, font = "dot", draw.Copy, w.strongFont
		}
		w.drawSmoothIcon(r.Device, w.scaled(32), w.scaled(y+10), w.scaled(radius), kind, color)
		w.drawText(r.Device, font, name, w.scaledRect(52, y, 148, 20), color, win32.DtSingleLine|win32.DtVCenter)
	}
	if w.bundleVersion != "" {
		w.drawText(r.Device, w.smallFont, "Version "+w.bundleVersion, w.scaledRect(24, 484, 168, 16), draw.Faint, win32.DtSingleLine|win32.DtVCenter|win32.DtEndEllipsis)
	}
	switch w.page {
	case installerPageInstall:
		for _, y := range []int32{294, 318, 342} {
			w.drawSmoothIcon(r.Device, w.scaled(left+8), w.scaled(y), w.scaled(6), "check", draw.Green)
		}
	case installerPageUninstall:
		w.drawSmoothIcon(r.Device, w.scaled(left+22), w.scaled(326), w.scaled(9), "alert", draw.Amber)
	case installerPageResult:
		kind, color := w.resultBadge()
		w.drawSmoothIcon(r.Device, w.scaled(left+22), w.scaled(62), w.scaled(22), kind, color)
	}
	win32.GdiFlush.Call()
	w.backdropBrush, _, _ = win32.CreatePatternBrush.Call(r.Bitmap)
}

func (w *installerWizard) paintBackdrop() {
	paint := win32.PaintStruct{}
	device, _, _ := win32.BeginPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	if device == 0 {
		return
	}
	w.backdrop.Copy(device, 0, 0)
	win32.EndPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
}

func (w *installerWizard) controlOrigin(control uintptr) win32.Point {
	origin := win32.Point{}
	win32.MapWindowPoints.Call(control, w.hwnd, uintptr(unsafe.Pointer(&origin)), 1)
	return origin
}

// compose paints an owner-drawn control off screen, over the part of the
// backdrop it covers, and presents it in one copy. Rounded shapes blend into
// what is behind them and nothing flickers.
func (w *installerWizard) compose(device, control uintptr, paint func(r *draw.Raster, rect win32.Rect)) {
	var rect win32.Rect
	win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	r := draw.NewRaster(rect.Right, rect.Bottom)
	if r == nil {
		return
	}
	defer r.Dispose()
	if w.backdrop != nil {
		origin := w.controlOrigin(control)
		r.CopyFrom(w.backdrop, origin.X, origin.Y)
	} else {
		r.Fill(rect, draw.Window)
	}
	paint(r, rect)
	r.Copy(device, 0, 0)
}

func (w *installerWizard) drawText(device, font uintptr, text string, rect win32.Rect, color draw.PaintColor, flags uintptr) {
	previous, _, _ := win32.SelectObject.Call(device, font)
	defer win32.SelectObject.Call(device, previous)
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, color.Ref())
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), flags|dtNoPrefix)
}

// dtNoPrefix keeps an ampersand in a path or a message from becoming an underline.
const dtNoPrefix = 0x00000800

// drawSmoothIcon blends an antialiased mark centered on x, y. Marks are cached
// by shape, size and color.
func (w *installerWizard) drawSmoothIcon(device uintptr, x, y, radius int32, kind string, color draw.PaintColor) {
	if w.smoothIcons == nil {
		w.smoothIcons = make(map[string]*draw.Raster)
	}
	key := fmt.Sprint(kind, radius, color)
	icon := w.smoothIcons[key]
	if icon == nil {
		icon = draw.NewRaster(2*radius+2, 2*radius+2)
		if icon == nil {
			return
		}
		draw.PaintIcon(icon.Pixels, icon.Width, kind, color)
		w.smoothIcons[key] = icon
	}
	icon.Draw(device, x-icon.Width/2, y-icon.Height/2)
}
