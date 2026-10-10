//go:build windows

package main

import (
	"math"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

func drawInstallerChrome(w *installerWizard) {
	paint := installerPaintStruct{}
	hdc, _, _ := installerBeginPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	if hdc == 0 {
		return
	}
	defer installerEndPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	client := installerRect{}
	installerGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	if w.backdrop != nil {
		w.backdrop.draw(hdc, 0, 0)
	} else {
		installerFillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), w.darkBrush)
	}
	brandRight := client.right - w.scaled(60)
	brandLeft := brandRight - w.scaled(190)
	brandTop := w.scaled(40)
	brandBottom := brandTop + w.scaled(190)
	if w.logoBitmap != 0 {
		drawInstallerLogo(hdc, w.logoBitmap, w.logoWidth, w.logoHeight,
			brandLeft+w.scaled(25), brandTop+w.scaled(22), w.scaled(140), w.scaled(140))
	} else {
		oldFont, _, _ := installerSelectObject.Call(hdc, w.iconFont)
		installerSetBkMode.Call(hdc, transparent)
		installerSetTextColor.Call(hdc, installerRGB(255, 255, 255))
		logo, _ := windows.UTF16PtrFromString("S")
		logoRect := installerRect{left: brandLeft, top: brandTop, right: brandRight, bottom: brandBottom}
		installerDrawText.Call(hdc, uintptr(unsafe.Pointer(logo)), 1, uintptr(unsafe.Pointer(&logoRect)), dtCenter|dtVCenter|dtSingleLine)
		installerSelectObject.Call(hdc, oldFont)
	}
	oldFont, _, _ := installerSelectObject.Call(hdc, w.wordmarkFont)
	installerSetBkMode.Call(hdc, transparent)
	installerSetTextColor.Call(hdc, installerRGB(170, 182, 201))
	wordmark, _ := windows.UTF16PtrFromString("S  Y  N  E  S  I  S")
	wordmarkRect := installerRect{left: brandLeft, top: brandBottom + w.scaled(20), right: brandRight, bottom: brandBottom + w.scaled(48)}
	installerDrawText.Call(hdc, uintptr(unsafe.Pointer(wordmark)), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&wordmarkRect)), dtCenter|dtVCenter|dtSingleLine)
	installerSelectObject.Call(hdc, oldFont)
	if w.page == installerPageUninstall {
		w.drawUninstallPanel(hdc)
	}

	linePen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(35, 43, 54))
	oldPen, _, _ := installerSelectObject.Call(hdc, linePen)
	headerDividerY := w.headerDividerY()
	// The rule continues from the accent mark painted into the backdrop.
	installerMoveToEx.Call(hdc, uintptr(w.scaled(60+installerAccentWidth+14)), uintptr(w.scaled(headerDividerY)), 0)
	installerLineTo.Call(hdc, uintptr(w.scaled(1030)), uintptr(w.scaled(headerDividerY)))
	if w.page != installerPageAction {
		footer := int32(800)
		if w.page == installerPageProgress || w.page == installerPageResult {
			footer = 878
		}
		installerMoveToEx.Call(hdc, uintptr(w.scaled(60)), uintptr(w.scaled(footer)), 0)
		installerLineTo.Call(hdc, uintptr(w.scaled(1340)), uintptr(w.scaled(footer)))
	}
	installerSelectObject.Call(hdc, oldPen)
	installerDeleteObject.Call(linePen)

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
			w.drawSmoothIcon(hdc, w.scaled(86), w.scaled(rowCenter), w.scaled(7), "dot", installerRGB(81, 178, 250))
		}
	}
	if w.page == installerPageResult {
		kind, color := w.resultBadge()
		w.drawSmoothIcon(hdc, w.scaled(98), w.scaled(118), w.scaled(34), kind, color)
	}
}

func (w *installerWizard) drawUninstallPanel(device uintptr) {
	w.drawSmoothIcon(device, w.scaled(112), w.scaled(741), w.scaled(22), "alert", installerRGB(255, 190, 65))
}

func drawInstallerSurface(device uintptr, rect installerRect, fill, border uintptr, radius int32, scale float64) {
	brush, _, _ := installerCreateSolidBrush.Call(fill)
	pen, _, _ := installerCreatePen.Call(0, scaledPenWidth(scale), border)
	if brush == 0 || pen == 0 {
		if brush != 0 {
			installerDeleteObject.Call(brush)
		}
		if pen != 0 {
			installerDeleteObject.Call(pen)
		}
		return
	}
	oldBrush, _, _ := installerSelectObject.Call(device, brush)
	oldPen, _, _ := installerSelectObject.Call(device, pen)
	installerRoundRect.Call(device, uintptr(rect.left), uintptr(rect.top), uintptr(rect.right), uintptr(rect.bottom), uintptr(radius), uintptr(radius))
	installerSelectObject.Call(device, oldPen)
	installerSelectObject.Call(device, oldBrush)
	installerDeleteObject.Call(pen)
	installerDeleteObject.Call(brush)
}

func (w *installerWizard) drawMetadataControl(device uintptr) {
	control := w.controls[controlMetadata]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	w.paintControlBackground(device, control)

	checkSize := w.scaled(28)
	checkLeft := w.scaled(2)
	checkTop := (rect.bottom - checkSize) / 2
	checkRect := installerRect{left: checkLeft, top: checkTop, right: checkLeft + checkSize, bottom: checkTop + checkSize}
	checkColor := installerRGB(156, 171, 192)
	if w.metadata {
		checkColor = installerRGB(71, 151, 235)
	}
	checkPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), checkColor)
	checkBrush, _, _ := installerGetStockObject.Call(nullBrush)
	if w.metadata {
		checkBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(71, 151, 235))
	}
	if checkPen != 0 && checkBrush != 0 {
		oldPen, _, _ := installerSelectObject.Call(device, checkPen)
		oldBrush, _, _ := installerSelectObject.Call(device, checkBrush)
		installerRoundRect.Call(device, uintptr(checkRect.left), uintptr(checkRect.top), uintptr(checkRect.right), uintptr(checkRect.bottom), uintptr(w.scaled(5)), uintptr(w.scaled(5)))
		installerSelectObject.Call(device, oldBrush)
		installerSelectObject.Call(device, oldPen)
	}
	if w.metadata {
		tickPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(238, 247, 255))
		if tickPen != 0 {
			oldPen, _, _ := installerSelectObject.Call(device, tickPen)
			installerMoveToEx.Call(device, uintptr(checkLeft+w.scaled(7)), uintptr(checkTop+w.scaled(14)), 0)
			installerLineTo.Call(device, uintptr(checkLeft+w.scaled(12)), uintptr(checkTop+w.scaled(19)))
			installerLineTo.Call(device, uintptr(checkLeft+w.scaled(22)), uintptr(checkTop+w.scaled(8)))
			installerSelectObject.Call(device, oldPen)
			installerDeleteObject.Call(tickPen)
		}
	}
	if w.metadata && checkBrush != 0 {
		installerDeleteObject.Call(checkBrush)
	}
	if checkPen != 0 {
		installerDeleteObject.Call(checkPen)
	}

	if focused, _, _ := installerGetFocus.Call(); focused == control {
		focusPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(125, 211, 252))
		focusBrush, _, _ := installerGetStockObject.Call(nullBrush)
		if focusPen != 0 && focusBrush != 0 {
			oldPen, _, _ := installerSelectObject.Call(device, focusPen)
			oldBrush, _, _ := installerSelectObject.Call(device, focusBrush)
			inset := w.scaled(1)
			installerRoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.right-inset), uintptr(rect.bottom-inset), uintptr(w.scaled(6)), uintptr(w.scaled(6)))
			installerSelectObject.Call(device, oldBrush)
			installerSelectObject.Call(device, oldPen)
		}
		if focusPen != 0 {
			installerDeleteObject.Call(focusPen)
		}
	}

	textRect := installerRect{left: w.scaled(48), top: 0, right: rect.right - w.scaled(8), bottom: rect.bottom}
	oldFont, _, _ := installerSelectObject.Call(device, w.bodyFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(244, 246, 252))
	text := getInstallerText(control)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&textRect)), dtVCenter|dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func drawInstallerCapabilityIcon(device uintptr, w *installerWizard, kind string, centerX, centerY, size int32) {
	if w.page != installerPageAction {
		return
	}
	w.drawArt(device, kind, centerX-size/2, centerY-size/2, size, size)
}

func (w *installerWizard) drawArt(device uintptr, name string, x, y, width, height int32) {
	art := w.art[name]
	if art.bitmap != 0 {
		drawInstallerLogo(device, art.bitmap, art.width, art.height, x, y, width, height)
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
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	primary := controlID == controlNext
	enabled, _, _ := installerUser32.NewProc("IsWindowEnabled").Call(control)
	w.paintControlBackground(device, control)
	if controlID == controlResultDetailsToggle {
		drawInstallerText(device, w.detailFont, getInstallerText(control), rect, installerRGB(113, 190, 249))
		if focus, _, _ := installerGetFocus.Call(); focus == control {
			installerFrameRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.surfaceBrush)
		}
		return
	}
	art := "secondary"
	if primary {
		art = "primary"
	}
	w.drawArt(device, art, 0, 0, rect.right, rect.bottom)
	textRed, textGreen, textBlue := byte(248), byte(250), byte(255)
	if enabled == 0 {
		drawInstallerSurface(device, rect, installerRGB(22, 28, 36), installerRGB(42, 52, 64), w.scaled(10), w.scale)
		textRed, textGreen, textBlue = 95, 108, 123
	}

	oldFont, _, _ := installerSelectObject.Call(device, w.buttonFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(textRed, textGreen, textBlue))
	text := getInstallerText(control)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), dtCenter|dtVCenter|dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func (w *installerWizard) drawActionCard(controlID int, device uintptr) {
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
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
	w.drawArt(device, art, 0, 0, rect.right, rect.bottom)
	focused, _, _ := installerGetFocus.Call()
	if focused == control && !selected {
		focusPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(125, 211, 252))
		nullBrush, _, _ := installerGetStockObject.Call(nullBrush)
		oldFocusPen, _, _ := installerSelectObject.Call(device, focusPen)
		oldFocusBrush, _, _ := installerSelectObject.Call(device, nullBrush)
		inset := w.scaled(3)
		installerRoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.right-inset), uintptr(rect.bottom-inset), uintptr(w.scaled(11)), uintptr(w.scaled(11)))
		installerSelectObject.Call(device, oldFocusBrush)
		installerSelectObject.Call(device, oldFocusPen)
		installerDeleteObject.Call(focusPen)
	}

	// The mockup uses a radio indicator as the first visual anchor in each
	// card. Keep it inside the owner-drawn button so keyboard focus and mouse
	// activation continue to use the same native control.
	kind, color := "radio-unselected", installerRGB(128, 145, 168)
	if selected {
		kind, color = "radio-selected", installerRGB(115, 198, 255)
	}
	if !enabled {
		kind, color = "radio-disabled", installerRGB(76, 90, 110)
	}
	w.drawSmoothIcon(device, w.scaled(48), rect.bottom/2, w.scaled(18), kind, color)

	titleRect := installerRect{left: w.scaled(94), top: w.scaled(14), right: rect.right - w.scaled(28), bottom: w.scaled(49)}
	detailRect := installerRect{left: w.scaled(94), top: w.scaled(50), right: rect.right - w.scaled(28), bottom: rect.bottom - w.scaled(12)}
	oldFont, _, _ := installerSelectObject.Call(device, w.cardTitleFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(textRed, textGreen, textBlue))
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(getInstallerText(control)))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&titleRect)), dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
	detail := w.installActionDetail()
	if controlID == controlActionUninstall {
		detail = "Remove the application; metadata stays unless you choose otherwise."
	}
	oldFont, _, _ = installerSelectObject.Call(device, w.cardDetailFont)
	installerSetTextColor.Call(device, installerRGB(detailRed, detailGreen, detailBlue))
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(detail))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&detailRect)), dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func drawInstallerText(device, font uintptr, text string, rect installerRect, color uintptr) {
	previous, _, _ := installerSelectObject.Call(device, font)
	defer installerSelectObject.Call(device, previous)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, color)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), dtSingleLine|dtVCenter|dtEndEllipsis)
}

func (w *installerWizard) drawProgressSteps(device uintptr) {
	w.mu.Lock()
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	var rect installerRect
	installerGetClientRect.Call(w.controls[controlProgressSteps], uintptr(unsafe.Pointer(&rect)))
	w.paintControlBackground(device, w.controls[controlProgressSteps])
	for i, step := range steps {
		y := w.scaled(int32(i) * 68)
		w.drawStepMarker(device, w.scaled(21), y+w.scaled(19), step.status, i < len(steps)-1)
		font, titleColor := w.bodyFont, installerRGB(235, 240, 247)
		switch step.status {
		case "working":
			font = w.cardTitleFont
		case "pending":
			titleColor = installerRGB(168, 180, 198)
		}
		drawInstallerText(device, font, step.title, installerRect{left: w.scaled(68), top: y, right: rect.right, bottom: y + w.scaled(38)}, titleColor)
		detail, detailColor := step.detail, installerRGB(150, 166, 188)
		if detail == "" {
			detail, detailColor = "Pending", installerRGB(112, 126, 146)
		}
		drawInstallerText(device, w.stepDetailFont, detail, installerRect{left: w.scaled(68), top: y + w.scaled(36), right: rect.right, bottom: y + w.scaled(64)}, detailColor)
	}
}

func (w *installerWizard) drawStepMarker(device uintptr, x, y int32, status string, connected bool) {
	r := w.scaled(16)
	if connected {
		pen, _, _ := installerCreatePen.Call(0, 1, installerRGB(49, 65, 84))
		previous, _, _ := installerSelectObject.Call(device, pen)
		installerMoveToEx.Call(device, uintptr(x), uintptr(y+r+w.scaled(4)), 0)
		installerLineTo.Call(device, uintptr(x), uintptr(y+w.scaled(68)-r-w.scaled(4)))
		installerSelectObject.Call(device, previous)
		installerDeleteObject.Call(pen)
	}
	w.drawSmoothIcon(device, x, y, r, status, installerStatusColor(status))
}

func (w *installerWizard) drawProgressBar(device uintptr) {
	var rect installerRect
	installerGetClientRect.Call(w.controls[controlProgressBar], uintptr(unsafe.Pointer(&rect)))
	if w.progressRaster == nil || w.progressRaster.width != rect.right || w.progressRaster.height != rect.bottom {
		w.progressRaster.dispose()
		w.progressRaster = newInstallerRaster(rect.right, rect.bottom)
	}
	if w.progressRaster == nil {
		return
	}
	now := time.Now()
	phase := math.Mod(now.Sub(w.progressEpoch).Seconds(), 2.2) / 2.2
	origin := w.controlOrigin(w.controls[controlProgressBar])
	paintInstallerProgressOnBackground(w.progressRaster.pixels, rect.right, rect.bottom, w.progressMotion.value(now), phase, w.backdrop, origin.x, origin.y)
	w.progressRaster.draw(device, 0, 0)
}

func (w *installerWizard) drawProgressLog(item installerDrawItem) {
	w.mu.Lock()
	if int(item.itemID) >= len(w.logEntries) {
		w.mu.Unlock()
		return
	}
	entry := w.logEntries[item.itemID]
	w.mu.Unlock()
	installerFillRect.Call(item.device, uintptr(unsafe.Pointer(&item.rect)), w.surfaceBrush)
	var client installerRect
	installerGetClientRect.Call(item.window, uintptr(unsafe.Pointer(&client)))
	// Owner-drawn lists can still request a partial final item after a resize
	// or scrollbar change. Keep that sliver blank instead of cutting a line.
	if item.rect.top < client.top || item.rect.bottom > client.bottom {
		return
	}
	if item.state&1 != 0 { // ODS_SELECTED: keep keyboard/selection feedback visible.
		installerFrameRect.Call(item.device, uintptr(unsafe.Pointer(&item.rect)), w.cardBrush)
	}
	rect := item.rect
	rect.left += w.scaled(14)
	previous, _, _ := installerSelectObject.Call(item.device, w.logFont)
	defer installerSelectObject.Call(item.device, previous)
	installerSetBkMode.Call(item.device, transparent)
	// Let the native list clip/scroll the full text instead of baking an
	// ellipsis into a row whose remaining diagnostics should stay reachable.
	for i, text := range []string{"[" + entry.status + "] ", entry.detail} {
		color := installerRGB(226, 232, 240)
		if i == 0 {
			color = installerStatusColor(entry.status)
		}
		installerSetTextColor.Call(item.device, color)
		value, _ := windows.UTF16FromString(text)
		var size installerPoint
		installerGDI32.NewProc("GetTextExtentPoint32W").Call(item.device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		rect.right = rect.left + size.x
		installerDrawText.Call(item.device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&rect)), dtSingleLine|dtVCenter)
		rect.left = rect.right
	}
}
