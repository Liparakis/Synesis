//go:build windows

package main

import (
	"math"
	"time"
	"unsafe"
)

const installerProgressEase = 500 * time.Millisecond

// installerAccentWidth is the design-space length of the accent mark that
// starts the rule under every page header.
const installerAccentWidth = 72

// Motion only interpolates toward an observed completed-stage count. Retarget
// from the current visual position so bursts of backend events never jump.
type installerProgressMotion struct {
	from, target float64
	changed      time.Time
}

func (m installerProgressMotion) value(now time.Time) float64 {
	t := min(1.0, max(0.0, float64(now.Sub(m.changed))/float64(installerProgressEase)))
	return m.from + (m.target-m.from)*(1-math.Pow(1-t, 3))
}

func (m *installerProgressMotion) setTarget(target float64, now time.Time) {
	target = min(1.0, max(0.0, target))
	if target == m.target {
		return
	}
	m.from, m.target, m.changed = m.value(now), target, now
}

// A reusable premultiplied BGRA surface is composed offscreen and presented in
// one AlphaBlend. Animated frames do not allocate GDI objects or erase first.
type installerRaster struct {
	bitmap, device, previous uintptr
	width, height            int32
	pixels                   []byte
}

func newInstallerRaster(width, height int32) *installerRaster {
	if width <= 0 || height <= 0 {
		return nil
	}
	r := &installerRaster{width: width, height: height}
	r.device, _, _ = installerCreateCompatibleDC.Call(0)
	if r.device == 0 {
		return nil
	}
	info := installerBitmapInfo{header: installerBitmapInfoHeader{size: 40, width: width, height: -height, planes: 1, bitCount: 32}}
	var pixels unsafe.Pointer
	r.bitmap, _, _ = installerCreateDIBSection.Call(r.device, uintptr(unsafe.Pointer(&info)), 0, uintptr(unsafe.Pointer(&pixels)), 0, 0)
	if r.bitmap == 0 || pixels == nil {
		r.dispose()
		return nil
	}
	r.previous, _, _ = installerSelectObject.Call(r.device, r.bitmap)
	r.pixels = unsafe.Slice((*byte)(pixels), int(width*height*4))
	return r
}

func (r *installerRaster) dispose() {
	if r == nil {
		return
	}
	if r.previous != 0 {
		installerSelectObject.Call(r.device, r.previous)
	}
	if r.bitmap != 0 {
		installerDeleteObject.Call(r.bitmap)
	}
	if r.device != 0 {
		installerDeleteDC.Call(r.device)
	}
	r.bitmap, r.device, r.previous, r.pixels = 0, 0, 0, nil
}

func (r *installerRaster) draw(device uintptr, x, y int32) {
	if r == nil {
		return
	}
	installerAlphaBlend.Call(device, uintptr(x), uintptr(y), uintptr(r.width), uintptr(r.height), r.device, 0, 0, uintptr(r.width), uintptr(r.height), installerBlendValue())
}

type installerPaintColor [3]float64

func installerPaintRGB(color uintptr) installerPaintColor {
	return installerPaintColor{float64(color & 255), float64((color >> 8) & 255), float64((color >> 16) & 255)}
}

func installerMix(a, b installerPaintColor, coverage float64) installerPaintColor {
	return installerPaintColor{a[0] + (b[0]-a[0])*coverage, a[1] + (b[1]-a[1])*coverage, a[2] + (b[2]-a[2])*coverage}
}

// Analytic edge coverage gives the pill a smooth subpixel boundary, including
// while its leading edge is between physical screen pixels.
func installerPillCoverage(x, y, left, top, right, bottom float64) float64 {
	if right <= left || bottom <= top {
		return 0
	}
	hw, hh := (right-left)/2, (bottom-top)/2
	r := min(hw, hh)
	dx := max(0.0, math.Abs(x-(left+right)/2)-(hw-r))
	dy := max(0.0, math.Abs(y-(top+bottom)/2)-(hh-r))
	return min(1.0, max(0.0, r-math.Hypot(dx, dy)+0.5))
}

func paintInstallerProgress(pixels []byte, width, height int32, fill, phase float64) {
	paintInstallerProgressOnBackground(pixels, width, height, fill, phase, nil, 0, 0)
}

func paintInstallerProgressOnBackground(pixels []byte, width, height int32, fill, phase float64, background *installerRaster, originX, originY int32) {
	w, h := float64(width), float64(height)
	fill = min(1.0, max(0.0, fill))
	end := 2 + (w-4)*fill
	band := max(h*2, (end-2)*0.18)
	sheen := -band + (end-2+2*band)*phase
	for y := 0; y < int(height); y++ {
		fy := float64(y) + 0.5
		base := installerPaintColor{15, 177 - 67*fy/h, 249 - 9*fy/h}
		for x := 0; x < int(width); x++ {
			fx := float64(x) + 0.5
			baseColor := installerPaintColor{15, 18, 24}
			if background != nil {
				baseColor = background.colorAt(int32(x)+originX, int32(y)+originY)
			}
			color := installerMix(baseColor, installerPaintColor{65, 83, 105}, installerPillCoverage(fx, fy, 0, 0, w, h))
			color = installerMix(color, installerPaintColor{25, 33, 43}, installerPillCoverage(fx, fy, 1, 1, w-1, h-1))
			if fill > 0 {
				outer := installerPillCoverage(fx, fy, 2, 2, end, h-2)
				color = installerMix(color, installerPaintColor{62, 194, 251}, outer)
				gloss := 0.38 * math.Exp(-4*math.Pow((fx-sheen)/band, 2))
				lit := installerMix(base, installerPaintColor{215, 246, 255}, gloss)
				color = installerMix(color, lit, installerPillCoverage(fx, fy, 3, 3, end-1, h-3))
			}
			i := (y*int(width) + x) * 4
			pixels[i], pixels[i+1], pixels[i+2], pixels[i+3] = byte(color[2]+0.5), byte(color[1]+0.5), byte(color[0]+0.5), 255
		}
	}
}

func installerSegmentDistance(x, y, ax, ay, bx, by float64) float64 {
	dx, dy := bx-ax, by-ay
	t := min(1.0, max(0.0, ((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)))
	return math.Hypot(x-ax-t*dx, y-ay-t*dy)
}

// Small icons are sampled at 4x4 positions per destination pixel, then stored
// with premultiplied alpha. This smooths circles and diagonal checks against
// the actual card artwork rather than adding a dark rectangular background.
func paintInstallerIcon(pixels []byte, size int32, kind string, color installerPaintColor) {
	r := float64(size)/2 - 1
	for y := 0; y < int(size); y++ {
		for x := 0; x < int(size); x++ {
			var sum installerPaintColor
			alpha := 0.0
			for sy := 0; sy < 4; sy++ {
				for sx := 0; sx < 4; sx++ {
					fx := float64(x) + (float64(sx)+0.5)/4 - float64(size)/2
					fy := float64(y) + (float64(sy)+0.5)/4 - float64(size)/2
					d := math.Hypot(fx, fy)
					c, visible, weight := color, d <= r && d >= r-1.5, 1.0
					if kind == "alert" {
						visible = fy >= -r*0.95 && fy <= r*0.8 && math.Abs(fx) <= (fy+r*0.95)*0.55
						c = installerMix(installerPaintColor{255, 213, 105}, installerPaintColor{242, 170, 45}, (fy+r)/(2*r))
						if installerSegmentDistance(fx, fy, 0, -r*0.28, 0, r*0.18) < 0.9 || math.Hypot(fx, fy-r*0.48) < 1.0 {
							c = installerPaintColor{47, 35, 16}
						}
					} else if kind == "radio-selected" {
						visible = d <= r
						if d < r-1 {
							c = installerPaintColor{9, 104, 217}
						}
						if d < r/3 {
							c = installerPaintColor{225, 245, 255}
						}
					} else if kind == "working" {
						visible = visible || d < r*0.46
					} else if kind == "done" {
						visible = visible || min(installerSegmentDistance(fx, fy, -r*0.45, 0, -r*0.1, r*0.32), installerSegmentDistance(fx, fy, -r*0.1, r*0.32, r*0.5, -r*0.4)) < 0.85
					} else if kind == "failed" || kind == "warning" {
						visible = visible || installerSegmentDistance(fx, fy, 0, -r*0.45, 0, r*0.06) < 0.8 || math.Hypot(fx, fy-r*0.4) < 0.9
					} else if kind == "dot" {
						visible = d <= r
					} else if kind == "badge-done" || kind == "badge-failed" || kind == "badge-warning" {
						// A tinted disc with a solid ring and glyph: large enough to
						// read as the page's outcome, quiet enough to sit beside the title.
						stroke := max(1.1, r*0.085)
						glyph := installerSegmentDistance(fx, fy, 0, -r*0.46, 0, r*0.08) < stroke || math.Hypot(fx, fy-r*0.42) < stroke*1.15
						if kind == "badge-done" {
							glyph = min(installerSegmentDistance(fx, fy, -r*0.42, r*0.02, -r*0.12, r*0.32), installerSegmentDistance(fx, fy, -r*0.12, r*0.32, r*0.46, -r*0.30)) < stroke
						}
						visible = d <= r
						if d < r-stroke*1.3 && !glyph {
							weight = 0.16
						}
					}
					if visible {
						alpha += weight
						for i := range sum {
							sum[i] += c[i] * weight
						}
					}
				}
			}
			i := (y*int(size) + x) * 4
			pixels[i], pixels[i+1], pixels[i+2], pixels[i+3] = byte(sum[2]/16+0.5), byte(sum[1]/16+0.5), byte(sum[0]/16+0.5), byte(alpha*255/16+0.5)
		}
	}
}

func (r *installerRaster) colorAt(x, y int32) installerPaintColor {
	if x < 0 || y < 0 || x >= r.width || y >= r.height {
		return installerPaintColor{15, 18, 24}
	}
	i := (int(y)*int(r.width) + int(x)) * 4
	return installerPaintColor{float64(r.pixels[i+2]), float64(r.pixels[i+1]), float64(r.pixels[i])}
}

func (r *installerRaster) putColor(x, y int32, color installerPaintColor) {
	i := (int(y)*int(r.width) + int(x)) * 4
	r.pixels[i], r.pixels[i+1], r.pixels[i+2], r.pixels[i+3] = byte(color[2]+0.5), byte(color[1]+0.5), byte(color[0]+0.5), 255
}

func installerRoundedCoverage(x, y, left, top, right, bottom, radius float64) float64 {
	hw, hh := (right-left)/2, (bottom-top)/2
	r := min(radius, hw, hh)
	dx := max(0.0, math.Abs(x-(left+right)/2)-(hw-r))
	dy := max(0.0, math.Abs(y-(top+bottom)/2)-(hh-r))
	return min(1.0, max(0.0, r-math.Hypot(dx, dy)+0.5))
}

func (r *installerRaster) gradientPanel(rect installerRect, top, bottom, border installerPaintColor, radius float64) {
	for y := max(int32(0), rect.top); y < min(r.height, rect.bottom); y++ {
		fill := installerMix(top, bottom, float64(y-rect.top)/float64(rect.bottom-rect.top))
		for x := max(int32(0), rect.left); x < min(r.width, rect.right); x++ {
			fx, fy := float64(x)+0.5, float64(y)+0.5
			left, right, t, b := float64(rect.left), float64(rect.right), float64(rect.top), float64(rect.bottom)
			color := installerMix(r.colorAt(x, y), border, installerRoundedCoverage(fx, fy, left, t, right, b, radius))
			color = installerMix(color, fill, installerRoundedCoverage(fx, fy, left+1, t+1, right-1, b-1, max(1.0, radius-1)))
			r.putColor(x, y, color)
		}
	}
}

// One cached surface supplies both the parent and native child backgrounds.
// Pattern-brush origins are aligned to the parent so labels cannot punch flat
// rectangles through gradients, panel outlines or the branded header.
func (w *installerWizard) rebuildBackdrop() {
	if w.backdropBrush != 0 {
		installerDeleteObject.Call(w.backdropBrush)
		w.backdropBrush = 0
	}
	w.backdrop.dispose()
	var client installerRect
	installerGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	w.backdrop = newInstallerRaster(client.right, client.bottom)
	if w.backdrop == nil {
		return
	}
	r := w.backdrop
	for y := int32(0); y < r.height; y++ {
		for x := int32(0); x < r.width; x++ {
			v := float64(y) / float64(r.height)
			dx := (float64(x)/float64(r.width) - 0.84) / 0.52
			dy := (v - 0.08) / 0.55
			glow := math.Exp(-dx*dx - dy*dy)
			r.putColor(x, y, installerPaintColor{11 + 5*(1-v) + 5*glow, 15 + 7*(1-v) + 9*glow, 22 + 10*(1-v) + 15*glow})
		}
	}
	panel := func(x, y, width, height int32, top, bottom, border installerPaintColor, radius int32) {
		r.gradientPanel(installerRect{w.scaled(x), w.scaled(y), w.scaled(x + width), w.scaled(y + height)}, top, bottom, border, float64(w.scaled(radius)))
	}
	if w.page == installerPageUninstall {
		panel(60, 300, 1280, 378, installerPaintColor{30, 39, 50}, installerPaintColor{19, 25, 33}, installerPaintColor{58, 72, 88}, 18)
		panel(88, 436, 1224, 78, installerPaintColor{27, 32, 43}, installerPaintColor{27, 32, 43}, installerPaintColor{69, 84, 102}, 10)
		panel(60, 704, 1280, 74, installerPaintColor{47, 37, 22}, installerPaintColor{32, 27, 21}, installerPaintColor{100, 76, 35}, 18)
	}
	if w.page == installerPageInstall {
		panel(60, 390, 1040, 82, installerPaintColor{27, 32, 43}, installerPaintColor{27, 32, 43}, installerPaintColor{74, 143, 207}, 10)
	}
	if w.page == installerPageResult {
		panel(60, 300, 1280, 270, installerPaintColor{27, 35, 45}, installerPaintColor{19, 25, 33}, installerPaintColor{49, 62, 77}, 18)
		panel(88, 480, 1224, 64, installerPaintColor{27, 32, 43}, installerPaintColor{27, 32, 43}, installerPaintColor{53, 65, 80}, 10)
		if w.resultDetailsOpen {
			panel(60, 656, 1280, 208, installerPaintColor{27, 32, 43}, installerPaintColor{27, 32, 43}, installerPaintColor{49, 62, 77}, 10)
		}
	}
	accentY := w.headerDividerY() - 3
	panel(60, accentY, installerAccentWidth, 6, installerPaintColor{96, 190, 255}, installerPaintColor{28, 124, 237}, installerPaintColor{60, 160, 248}, 3)
	w.drawArt(r.device, "ribbon", w.scaled(1100), 0, w.scaled(300), w.scaled(310))
	w.drawArt(r.device, "brand", client.right-w.scaled(250), w.scaled(40), w.scaled(190), w.scaled(190))
	installerGDI32.NewProc("GdiFlush").Call()
	w.backdropBrush, _, _ = installerGDI32.NewProc("CreatePatternBrush").Call(r.bitmap)
}

func (w *installerWizard) controlOrigin(control uintptr) installerPoint {
	origin := installerPoint{}
	installerUser32.NewProc("MapWindowPoints").Call(control, w.hwnd, uintptr(unsafe.Pointer(&origin)), 1)
	return origin
}

func (w *installerWizard) paintControlBackground(device, control uintptr) {
	var rect installerRect
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	if w.backdrop == nil {
		installerFillRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.darkBrush)
		return
	}
	origin := w.controlOrigin(control)
	installerGDI32.NewProc("BitBlt").Call(device, 0, 0, uintptr(rect.right), uintptr(rect.bottom), w.backdrop.device, uintptr(origin.x), uintptr(origin.y), 0x00cc0020)
}

func (w *installerWizard) drawSmoothIcon(device uintptr, x, y, radius int32, kind string, color uintptr) {
	if w.smoothIcons == nil {
		w.smoothIcons = make(map[string]*installerRaster)
	}
	icon := w.smoothIcons[kind]
	if icon == nil {
		icon = newInstallerRaster(2*radius+2, 2*radius+2)
		if icon == nil {
			return
		}
		paintInstallerIcon(icon.pixels, icon.width, kind, installerPaintRGB(color))
		w.smoothIcons[kind] = icon
	}
	icon.draw(device, x-icon.width/2, y-icon.height/2)
}
