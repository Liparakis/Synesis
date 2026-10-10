//go:build windows

package wizard

import (
	"math"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

func drawInstallerChrome(w *installerWizard) {
	paint := win32.PaintStruct{}
	hdc, _, _ := win32.BeginPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	if hdc == 0 {
		return
	}
	defer win32.EndPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	client := win32.Rect{}
	win32.GetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	if w.backdrop != nil {
		w.backdrop.Draw(hdc, 0, 0)
	} else {
		win32.FillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), w.darkBrush)
	}
	brandRight := client.Right - w.scaled(60)
	brandLeft := brandRight - w.scaled(190)
	brandTop := w.scaled(40)
	brandBottom := brandTop + w.scaled(190)
	if w.logoBitmap != 0 {
		draw.DrawLogo(hdc, w.logoBitmap, w.logoWidth, w.logoHeight,
			brandLeft+w.scaled(25), brandTop+w.scaled(22), w.scaled(140), w.scaled(140))
	} else {
		oldFont, _, _ := win32.SelectObject.Call(hdc, w.iconFont)
		win32.SetBkMode.Call(hdc, win32.Transparent)
		win32.SetTextColor.Call(hdc, win32.RGB(255, 255, 255))
		logo, _ := windows.UTF16PtrFromString("S")
		logoRect := win32.Rect{Left: brandLeft, Top: brandTop, Right: brandRight, Bottom: brandBottom}
		win32.DrawText.Call(hdc, uintptr(unsafe.Pointer(logo)), 1, uintptr(unsafe.Pointer(&logoRect)), win32.DtCenter|win32.DtVCenter|win32.DtSingleLine)
		win32.SelectObject.Call(hdc, oldFont)
	}
	oldFont, _, _ := win32.SelectObject.Call(hdc, w.wordmarkFont)
	win32.SetBkMode.Call(hdc, win32.Transparent)
	win32.SetTextColor.Call(hdc, win32.RGB(170, 182, 201))
	wordmark, _ := windows.UTF16PtrFromString("S  Y  N  E  S  I  S")
	wordmarkRect := win32.Rect{Left: brandLeft, Top: brandBottom + w.scaled(20), Right: brandRight, Bottom: brandBottom + w.scaled(48)}
	win32.DrawText.Call(hdc, uintptr(unsafe.Pointer(wordmark)), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&wordmarkRect)), win32.DtCenter|win32.DtVCenter|win32.DtSingleLine)
	win32.SelectObject.Call(hdc, oldFont)
	if w.page == installerPageUninstall {
		w.drawUninstallPanel(hdc)
	}

	linePen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(w.scale), win32.RGB(35, 43, 54))
	oldPen, _, _ := win32.SelectObject.Call(hdc, linePen)
	headerDividerY := w.headerDividerY()
	// The rule continues from the accent mark painted into the backdrop.
	win32.MoveToEx.Call(hdc, uintptr(w.scaled(60+installerAccentWidth+14)), uintptr(w.scaled(headerDividerY)), 0)
	win32.LineTo.Call(hdc, uintptr(w.scaled(1030)), uintptr(w.scaled(headerDividerY)))
	if w.page != installerPageAction {
		footer := int32(800)
		if w.page == installerPageProgress || w.page == installerPageResult {
			footer = 878
		}
		win32.MoveToEx.Call(hdc, uintptr(w.scaled(60)), uintptr(w.scaled(footer)), 0)
		win32.LineTo.Call(hdc, uintptr(w.scaled(1340)), uintptr(w.scaled(footer)))
	}
	win32.SelectObject.Call(hdc, oldPen)
	win32.DeleteObject.Call(linePen)

	for _, capability := range []struct {
		kind string
		y    int32
	}{
		{kind: "install", y: 289},
		{kind: "link", y: 333},
		{kind: "data", y: 377},
	} {
		drawInstallerCapabilityIcon(hdc, w, capability.kind, w.scaled(84), w.scaled(capability.y), w.scaled(28))
	}
	if w.page == installerPageInstall {
		for _, rowCenter := range []int32{631, 673, 715} {
			w.drawSmoothIcon(hdc, w.scaled(86), w.scaled(rowCenter), w.scaled(7), "dot", win32.RGB(81, 178, 250))
		}
	}
	if w.page == installerPageResult {
		kind, color := w.resultBadge()
		w.drawSmoothIcon(hdc, w.scaled(98), w.scaled(118), w.scaled(34), kind, color)
	}
}

func (w *installerWizard) drawUninstallPanel(device uintptr) {
	w.drawSmoothIcon(device, w.scaled(112), w.scaled(741), w.scaled(22), "alert", win32.RGB(255, 190, 65))
}

func drawInstallerSurface(device uintptr, rect win32.Rect, fill, border uintptr, radius int32, scale float64) {
	brush, _, _ := win32.CreateSolidBrush.Call(fill)
	pen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(scale), border)
	if brush == 0 || pen == 0 {
		if brush != 0 {
			win32.DeleteObject.Call(brush)
		}
		if pen != 0 {
			win32.DeleteObject.Call(pen)
		}
		return
	}
	oldBrush, _, _ := win32.SelectObject.Call(device, brush)
	oldPen, _, _ := win32.SelectObject.Call(device, pen)
	win32.RoundRect.Call(device, uintptr(rect.Left), uintptr(rect.Top), uintptr(rect.Right), uintptr(rect.Bottom), uintptr(radius), uintptr(radius))
	win32.SelectObject.Call(device, oldPen)
	win32.SelectObject.Call(device, oldBrush)
	win32.DeleteObject.Call(pen)
	win32.DeleteObject.Call(brush)
}

func (w *installerWizard) drawMetadataControl(device uintptr) {
	control := w.controls[controlMetadata]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := win32.Rect{}
	win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	w.paintControlBackground(device, control)

	checkSize := w.scaled(28)
	checkLeft := w.scaled(2)
	checkTop := (rect.Bottom - checkSize) / 2
	checkRect := win32.Rect{Left: checkLeft, Top: checkTop, Right: checkLeft + checkSize, Bottom: checkTop + checkSize}
	checkColor := win32.RGB(156, 171, 192)
	if w.metadata {
		checkColor = win32.RGB(71, 151, 235)
	}
	checkPen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(w.scale), checkColor)
	checkBrush, _, _ := win32.GetStockObject.Call(win32.NullBrush)
	if w.metadata {
		checkBrush, _, _ = win32.CreateSolidBrush.Call(win32.RGB(71, 151, 235))
	}
	if checkPen != 0 && checkBrush != 0 {
		oldPen, _, _ := win32.SelectObject.Call(device, checkPen)
		oldBrush, _, _ := win32.SelectObject.Call(device, checkBrush)
		win32.RoundRect.Call(device, uintptr(checkRect.Left), uintptr(checkRect.Top), uintptr(checkRect.Right), uintptr(checkRect.Bottom), uintptr(w.scaled(5)), uintptr(w.scaled(5)))
		win32.SelectObject.Call(device, oldBrush)
		win32.SelectObject.Call(device, oldPen)
	}
	if w.metadata {
		tickPen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(w.scale), win32.RGB(238, 247, 255))
		if tickPen != 0 {
			oldPen, _, _ := win32.SelectObject.Call(device, tickPen)
			win32.MoveToEx.Call(device, uintptr(checkLeft+w.scaled(7)), uintptr(checkTop+w.scaled(14)), 0)
			win32.LineTo.Call(device, uintptr(checkLeft+w.scaled(12)), uintptr(checkTop+w.scaled(19)))
			win32.LineTo.Call(device, uintptr(checkLeft+w.scaled(22)), uintptr(checkTop+w.scaled(8)))
			win32.SelectObject.Call(device, oldPen)
			win32.DeleteObject.Call(tickPen)
		}
	}
	if w.metadata && checkBrush != 0 {
		win32.DeleteObject.Call(checkBrush)
	}
	if checkPen != 0 {
		win32.DeleteObject.Call(checkPen)
	}

	if focused, _, _ := win32.GetFocus.Call(); focused == control {
		focusPen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(w.scale), win32.RGB(125, 211, 252))
		focusBrush, _, _ := win32.GetStockObject.Call(win32.NullBrush)
		if focusPen != 0 && focusBrush != 0 {
			oldPen, _, _ := win32.SelectObject.Call(device, focusPen)
			oldBrush, _, _ := win32.SelectObject.Call(device, focusBrush)
			inset := w.scaled(1)
			win32.RoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.Right-inset), uintptr(rect.Bottom-inset), uintptr(w.scaled(6)), uintptr(w.scaled(6)))
			win32.SelectObject.Call(device, oldBrush)
			win32.SelectObject.Call(device, oldPen)
		}
		if focusPen != 0 {
			win32.DeleteObject.Call(focusPen)
		}
	}

	textRect := win32.Rect{Left: w.scaled(48), Top: 0, Right: rect.Right - w.scaled(8), Bottom: rect.Bottom}
	oldFont, _, _ := win32.SelectObject.Call(device, w.bodyFont)
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, win32.RGB(244, 246, 252))
	text := win32.GetText(control)
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&textRect)), win32.DtVCenter|win32.DtSingleLine|win32.DtEndEllipsis)
	win32.SelectObject.Call(device, oldFont)
}

func drawInstallerCapabilityIcon(device uintptr, w *installerWizard, kind string, centerX, centerY, size int32) {
	if w.page != installerPageAction {
		return
	}
	w.drawArt(device, kind, centerX-size/2, centerY-size/2, size, size)
}

func (w *installerWizard) drawArt(device uintptr, name string, x, y, width, height int32) {
	art := w.art[name]
	if art.Bitmap != 0 {
		draw.DrawLogo(device, art.Bitmap, art.Width, art.Height, x, y, width, height)
	}
}

func (w *installerWizard) drawButton(controlID int, device uintptr) {
	if controlID == controlActionInstall || controlID == controlActionUninstall {
		w.drawActionCard(controlID, device)
		return
	}
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := win32.Rect{}
	win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	primary := controlID == controlNext
	enabled, _, _ := win32.User32.NewProc("IsWindowEnabled").Call(control)
	w.paintControlBackground(device, control)
	if controlID == controlResultDetailsToggle {
		drawInstallerText(device, w.detailFont, win32.GetText(control), rect, win32.RGB(113, 190, 249))
		if focus, _, _ := win32.GetFocus.Call(); focus == control {
			win32.FrameRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.surfaceBrush)
		}
		return
	}
	art := "secondary"
	if primary {
		art = "primary"
	}
	w.drawArt(device, art, 0, 0, rect.Right, rect.Bottom)
	textRed, textGreen, textBlue := byte(248), byte(250), byte(255)
	if enabled == 0 {
		drawInstallerSurface(device, rect, win32.RGB(22, 28, 36), win32.RGB(42, 52, 64), w.scaled(10), w.scale)
		textRed, textGreen, textBlue = 95, 108, 123
	}

	oldFont, _, _ := win32.SelectObject.Call(device, w.buttonFont)
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, win32.RGB(textRed, textGreen, textBlue))
	text := win32.GetText(control)
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), win32.DtCenter|win32.DtVCenter|win32.DtSingleLine|win32.DtEndEllipsis)
	win32.SelectObject.Call(device, oldFont)
}

func (w *installerWizard) drawActionCard(controlID int, device uintptr) {
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := win32.Rect{}
	win32.GetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	selected := (controlID == controlActionInstall && w.action == installerActionInstall) ||
		(controlID == controlActionUninstall && w.action == installerActionUninstall)
	enabled := controlID != controlActionUninstall || w.installed
	textRed, textGreen, textBlue := byte(244), byte(246), byte(252)
	detailRed, detailGreen, detailBlue := byte(171), byte(185), byte(207)
	art := "card"
	if selected {
		art = "card-selected"
	}
	if !enabled {
		art = "card-disabled"
		textRed, textGreen, textBlue = 100, 116, 139
		detailRed, detailGreen, detailBlue = 71, 85, 105
	}
	w.paintControlBackground(device, control)
	w.drawArt(device, art, 0, 0, rect.Right, rect.Bottom)
	focused, _, _ := win32.GetFocus.Call()
	if focused == control && !selected {
		focusPen, _, _ := win32.CreatePen.Call(0, scaledPenWidth(w.scale), win32.RGB(125, 211, 252))
		nullBrush, _, _ := win32.GetStockObject.Call(win32.NullBrush)
		oldFocusPen, _, _ := win32.SelectObject.Call(device, focusPen)
		oldFocusBrush, _, _ := win32.SelectObject.Call(device, nullBrush)
		inset := w.scaled(3)
		win32.RoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.Right-inset), uintptr(rect.Bottom-inset), uintptr(w.scaled(11)), uintptr(w.scaled(11)))
		win32.SelectObject.Call(device, oldFocusBrush)
		win32.SelectObject.Call(device, oldFocusPen)
		win32.DeleteObject.Call(focusPen)
	}

	// The mockup uses a radio indicator as the first visual anchor in each
	// card. Keep it inside the owner-drawn button so keyboard focus and mouse
	// activation continue to use the same native control.
	kind, color := "radio-unselected", win32.RGB(128, 145, 168)
	if selected {
		kind, color = "radio-selected", win32.RGB(115, 198, 255)
	}
	if !enabled {
		kind, color = "radio-disabled", win32.RGB(76, 90, 110)
	}
	w.drawSmoothIcon(device, w.scaled(48), rect.Bottom/2, w.scaled(18), kind, color)

	titleRect := win32.Rect{Left: w.scaled(94), Top: w.scaled(14), Right: rect.Right - w.scaled(28), Bottom: w.scaled(49)}
	detailRect := win32.Rect{Left: w.scaled(94), Top: w.scaled(50), Right: rect.Right - w.scaled(28), Bottom: rect.Bottom - w.scaled(12)}
	oldFont, _, _ := win32.SelectObject.Call(device, w.cardTitleFont)
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, win32.RGB(textRed, textGreen, textBlue))
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(win32.GetText(control)))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&titleRect)), win32.DtSingleLine|win32.DtEndEllipsis)
	win32.SelectObject.Call(device, oldFont)
	detail := w.installActionDetail()
	if controlID == controlActionUninstall {
		detail = "Remove the application; metadata stays unless you choose otherwise."
	}
	oldFont, _, _ = win32.SelectObject.Call(device, w.cardDetailFont)
	win32.SetTextColor.Call(device, win32.RGB(detailRed, detailGreen, detailBlue))
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(detail))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&detailRect)), win32.DtSingleLine|win32.DtEndEllipsis)
	win32.SelectObject.Call(device, oldFont)
}

func drawInstallerText(device, font uintptr, text string, rect win32.Rect, color uintptr) {
	previous, _, _ := win32.SelectObject.Call(device, font)
	defer win32.SelectObject.Call(device, previous)
	win32.SetBkMode.Call(device, win32.Transparent)
	win32.SetTextColor.Call(device, color)
	win32.DrawText.Call(device, uintptr(unsafe.Pointer(win32.MustUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), win32.DtSingleLine|win32.DtVCenter|win32.DtEndEllipsis)
}

func (w *installerWizard) drawProgressSteps(device uintptr) {
	w.mu.Lock()
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	var rect win32.Rect
	win32.GetClientRect.Call(w.controls[controlProgressSteps], uintptr(unsafe.Pointer(&rect)))
	w.paintControlBackground(device, w.controls[controlProgressSteps])
	for i, step := range steps {
		y := w.scaled(int32(i) * 68)
		w.drawStepMarker(device, w.scaled(21), y+w.scaled(19), step.status, i < len(steps)-1)
		font, titleColor := w.bodyFont, win32.RGB(235, 240, 247)
		switch step.status {
		case "working":
			font = w.cardTitleFont
		case "pending":
			titleColor = win32.RGB(168, 180, 198)
		}
		drawInstallerText(device, font, step.title, win32.Rect{Left: w.scaled(68), Top: y, Right: rect.Right, Bottom: y + w.scaled(38)}, titleColor)
		detail, detailColor := step.detail, win32.RGB(150, 166, 188)
		if detail == "" {
			detail, detailColor = "Pending", win32.RGB(112, 126, 146)
		}
		drawInstallerText(device, w.stepDetailFont, detail, win32.Rect{Left: w.scaled(68), Top: y + w.scaled(36), Right: rect.Right, Bottom: y + w.scaled(64)}, detailColor)
	}
}

func (w *installerWizard) drawStepMarker(device uintptr, x, y int32, status string, connected bool) {
	r := w.scaled(16)
	if connected {
		pen, _, _ := win32.CreatePen.Call(0, 1, win32.RGB(49, 65, 84))
		previous, _, _ := win32.SelectObject.Call(device, pen)
		win32.MoveToEx.Call(device, uintptr(x), uintptr(y+r+w.scaled(4)), 0)
		win32.LineTo.Call(device, uintptr(x), uintptr(y+w.scaled(68)-r-w.scaled(4)))
		win32.SelectObject.Call(device, previous)
		win32.DeleteObject.Call(pen)
	}
	w.drawSmoothIcon(device, x, y, r, status, draw.StatusColor(status))
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
	w.progressRaster.Draw(device, 0, 0)
}

func (w *installerWizard) drawProgressLog(item win32.DrawItem) {
	w.mu.Lock()
	if int(item.ItemID) >= len(w.logEntries) {
		w.mu.Unlock()
		return
	}
	entry := w.logEntries[item.ItemID]
	w.mu.Unlock()
	win32.FillRect.Call(item.Device, uintptr(unsafe.Pointer(&item.Rect)), w.surfaceBrush)
	var client win32.Rect
	win32.GetClientRect.Call(item.Window, uintptr(unsafe.Pointer(&client)))
	// Owner-drawn lists can still request a partial final item after a resize
	// or scrollbar change. Keep that sliver blank instead of cutting a line.
	if item.Rect.Top < client.Top || item.Rect.Bottom > client.Bottom {
		return
	}
	if item.State&1 != 0 { // ODS_SELECTED: keep keyboard/selection feedback visible.
		win32.FrameRect.Call(item.Device, uintptr(unsafe.Pointer(&item.Rect)), w.cardBrush)
	}
	rect := item.Rect
	rect.Left += w.scaled(14)
	previous, _, _ := win32.SelectObject.Call(item.Device, w.logFont)
	defer win32.SelectObject.Call(item.Device, previous)
	win32.SetBkMode.Call(item.Device, win32.Transparent)
	// Let the native list clip/scroll the full text instead of baking an
	// ellipsis into a row whose remaining diagnostics should stay reachable.
	for i, text := range []string{"[" + entry.status + "] ", entry.detail} {
		color := win32.RGB(226, 232, 240)
		if i == 0 {
			color = draw.StatusColor(entry.status)
		}
		win32.SetTextColor.Call(item.Device, color)
		value, _ := windows.UTF16FromString(text)
		var size win32.Point
		win32.GDI32.NewProc("GetTextExtentPoint32W").Call(item.Device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		rect.Right = rect.Left + size.X
		win32.DrawText.Call(item.Device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&rect)), win32.DtSingleLine|win32.DtVCenter)
		rect.Left = rect.Right
	}
}
