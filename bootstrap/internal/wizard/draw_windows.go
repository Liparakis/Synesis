//go:build windows

package wizard

import (
	"math"
	"time"
	"unsafe"

	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

// odsNoFocusRect is set until the keyboard is used, so a mouse user never sees
// a focus ring.
const odsNoFocusRect = 0x0200

// drawItem paints one owner-drawn control from its paint request.
func (w *installerWizard) drawItem(id int, item win32.DrawItem) {
	control := w.controls[id]
	if control == 0 || item.Device == 0 {
		return
	}
	focused := item.State&win32.OdsFocus != 0 && item.State&odsNoFocusRect == 0
	pressed := item.State&win32.OdsSelected != 0
	switch id {
	case controlProgressBar:
		w.drawProgressBar(item.Device)
	case controlProgressLog:
		w.drawProgressLog(item)
	case controlProgressSteps:
		w.compose(item.Device, control, w.drawProgressSteps)
	case controlMetadata:
		w.compose(item.Device, control, func(r *draw.Raster, rect win32.Rect) { w.drawCheckbox(r, rect, focused) })
	case controlActionInstall, controlActionUninstall:
		w.compose(item.Device, control, func(r *draw.Raster, rect win32.Rect) { w.drawActionCard(r, rect, id, focused) })
	case controlResultDetailsToggle:
		w.compose(item.Device, control, func(r *draw.Raster, rect win32.Rect) {
			color := draw.Muted
			if focused || pressed {
				color = draw.Copy
			}
			w.drawText(r.Device, w.detailFont, win32.GetText(control), rect, color, win32.DtSingleLine|win32.DtVCenter)
		})
	default:
		w.compose(item.Device, control, func(r *draw.Raster, rect win32.Rect) { w.drawButton(r, rect, id, focused, pressed) })
	}
}

// drawButton paints a footer or field button. The page's one forward action is
// filled; on the uninstall page that fill is red.
func (w *installerWizard) drawButton(r *draw.Raster, rect win32.Rect, id int, focused, pressed bool) {
	control := w.controls[id]
	fill, border, text := draw.Raised, draw.LineStrong, draw.Copy
	primary := id == controlNext || (id == controlCancel && w.page == installerPageResult)
	if primary {
		fill, border, text = draw.Copy, draw.Copy, draw.Window
		if w.page == installerPageUninstall {
			fill, border = draw.Red, draw.Red
		}
	}
	if pressed {
		fill = draw.Mix(fill, draw.Window, 0.2)
	}
	if enabled, _, _ := win32.IsWindowEnabled.Call(control); enabled == 0 {
		fill, border, text = draw.Field, draw.Line, draw.Faint
	} else if focused && !primary {
		border = draw.Copy
	}
	radius := float64(w.scaled(6))
	r.Panel(rect, fill, border, radius)
	if focused && primary {
		inset := w.scaled(2)
		r.Panel(win32.Rect{Left: inset, Top: inset, Right: rect.Right - inset, Bottom: rect.Bottom - inset}, fill, draw.Window, radius-float64(inset))
	}
	w.drawText(r.Device, w.bodyFont, win32.GetText(control), rect, text, win32.DtCenter|win32.DtVCenter|win32.DtSingleLine|win32.DtEndEllipsis)
}

// drawActionCard paints one of the two choices of the first page: a radio
// mark, the action's name and what it will do.
func (w *installerWizard) drawActionCard(r *draw.Raster, rect win32.Rect, id int, focused bool) {
	selected := (id == controlActionUninstall) == (w.action == installerActionUninstall)
	enabled := id != controlActionUninstall || w.installed
	fill, border, title, detail := draw.Field, draw.Line, draw.Copy, draw.Muted
	radio, radioColor := "radio-unselected", draw.Faint
	switch {
	case !enabled:
		fill, title, detail = draw.Sidebar, draw.Faint, draw.Faint
		radioColor = draw.LineStrong
	case selected:
		fill, border = draw.Raised, draw.Muted
		radio, radioColor = "radio-selected", draw.Copy
	}
	if focused {
		border = draw.Copy
	}
	r.Panel(rect, fill, border, float64(w.scaled(8)))
	w.drawSmoothIcon(r.Device, w.scaled(26), rect.Bottom/2, w.scaled(8), radio, radioColor)
	text := w.installActionDetail()
	if id == controlActionUninstall {
		text = w.uninstallActionDetail()
	}
	left, right := w.scaled(50), rect.Right-w.scaled(16)
	w.drawText(r.Device, w.strongFont, win32.GetText(w.controls[id]), win32.Rect{Left: left, Top: w.scaled(13), Right: right, Bottom: w.scaled(33)}, title, win32.DtSingleLine|win32.DtVCenter|win32.DtEndEllipsis)
	w.drawText(r.Device, w.detailFont, text, win32.Rect{Left: left, Top: w.scaled(35), Right: right, Bottom: rect.Bottom - w.scaled(4)}, detail, win32.DtWordBreak|win32.DtEndEllipsis)
}

func (w *installerWizard) drawCheckbox(r *draw.Raster, rect win32.Rect, focused bool) {
	size := w.scaled(18)
	top := (rect.Bottom - size) / 2
	box := win32.Rect{Left: w.scaled(2), Top: top, Right: w.scaled(2) + size, Bottom: top + size}
	fill, border := draw.Field, draw.LineStrong
	if w.metadata {
		fill, border = draw.Copy, draw.Copy
	}
	if focused {
		ring := w.scaled(2)
		r.Panel(win32.Rect{Left: box.Left - ring, Top: box.Top - ring, Right: box.Right + ring, Bottom: box.Bottom + ring}, draw.Window, draw.Muted, float64(w.scaled(6)))
	}
	r.Panel(box, fill, border, float64(w.scaled(4)))
	if w.metadata {
		w.drawSmoothIcon(r.Device, (box.Left+box.Right)/2, (box.Top+box.Bottom)/2, w.scaled(6), "check", draw.Window)
	}
	w.drawText(r.Device, w.bodyFont, win32.GetText(w.controls[controlMetadata]), win32.Rect{Left: w.scaled(30), Right: rect.Right, Bottom: rect.Bottom}, draw.Copy, win32.DtSingleLine|win32.DtVCenter|win32.DtEndEllipsis)
}

// drawProgressSteps lists each backend stage with its status mark and, once it
// has started, the latest thing it reported.
func (w *installerWizard) drawProgressSteps(r *draw.Raster, rect win32.Rect) {
	w.mu.Lock()
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	const line = win32.DtSingleLine | win32.DtVCenter | win32.DtEndEllipsis
	for i, step := range steps {
		top := w.scaled(int32(i) * installerStepRowHeight)
		bottom := top + w.scaled(installerStepRowHeight)
		font, color := w.bodyFont, draw.Copy
		switch step.status {
		case "working":
			font = w.strongFont
		case "pending":
			color = draw.Faint
		}
		w.drawSmoothIcon(r.Device, w.scaled(10), (top+bottom)/2, w.scaled(8), step.status, draw.StatusColor(step.status))
		w.drawText(r.Device, font, step.title, win32.Rect{Left: w.scaled(32), Top: top, Right: w.scaled(244), Bottom: bottom}, color, line)
		w.drawText(r.Device, w.smallFont, step.detail, win32.Rect{Left: w.scaled(252), Top: top, Right: rect.Right, Bottom: bottom}, draw.Faint, line|win32.DtRight)
	}
}

func (w *installerWizard) drawProgressBar(device uintptr) {
	var rect win32.Rect
	win32.GetClientRect.Call(w.controls[controlProgressBar], uintptr(unsafe.Pointer(&rect)))
	if w.progressRaster == nil || w.progressRaster.Width != rect.Right || w.progressRaster.Height != rect.Bottom {
		w.progressRaster.Dispose()
		w.progressRaster = draw.NewRaster(rect.Right, rect.Bottom)
	}
	if w.progressRaster == nil {
		return
	}
	now := time.Now()
	phase := math.Mod(now.Sub(w.progressEpoch).Seconds(), 2.2) / 2.2
	origin := w.controlOrigin(w.controls[controlProgressBar])
	draw.PaintProgressOnBackground(w.progressRaster.Pixels, rect.Right, rect.Bottom, w.progressMotion.Value(now), phase, w.backdrop, origin.X, origin.Y)
	w.progressRaster.Copy(device, 0, 0)
}

// drawProgressLog paints one log row: its status in the status color, then
// the message. The full text stays available under the result's details.
func (w *installerWizard) drawProgressLog(item win32.DrawItem) {
	w.mu.Lock()
	if int(item.ItemID) >= len(w.logEntries) {
		w.mu.Unlock()
		return
	}
	entry := w.logEntries[item.ItemID]
	w.mu.Unlock()
	win32.FillRect.Call(item.Device, uintptr(unsafe.Pointer(&item.Rect)), w.fieldBrush)
	if item.State&win32.OdsSelected != 0 { // keep keyboard and selection feedback visible
		win32.FrameRect.Call(item.Device, uintptr(unsafe.Pointer(&item.Rect)), w.lineBrush)
	}
	rect := item.Rect
	rect.Left += w.scaled(4)
	status := "[" + entry.status + "] "
	var size win32.Point
	previous, _, _ := win32.SelectObject.Call(item.Device, w.logFont)
	win32.GetTextExtentPoint.Call(item.Device, uintptr(unsafe.Pointer(win32.MustUTF16(status))), uintptr(len(status)), uintptr(unsafe.Pointer(&size)))
	win32.SelectObject.Call(item.Device, previous)
	const line = win32.DtSingleLine | win32.DtVCenter | win32.DtEndEllipsis
	w.drawText(item.Device, w.logFont, status, rect, draw.StatusColor(entry.status), line)
	rect.Left += size.X
	w.drawText(item.Device, w.logFont, entry.detail, rect, draw.Muted, line)
}
