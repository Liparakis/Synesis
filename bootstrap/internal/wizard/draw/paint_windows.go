//go:build windows

package draw

import (
	"math"
	"time"
	"unsafe"

	"synesis/bootstrap/internal/wizard/win32"
)

// ProgressEase is how long the progress fill takes to ease toward a new target.
const ProgressEase = 500 * time.Millisecond

// ProgressMotion only interpolates toward an observed completed-stage count. Retarget
// from the current visual position so bursts of backend events never jump.
type ProgressMotion struct {
	From, Target float64
	Changed      time.Time
}

// Value is the eased fill fraction at now.
func (m ProgressMotion) Value(now time.Time) float64 {
	t := min(1.0, max(0.0, float64(now.Sub(m.Changed))/float64(ProgressEase)))
	return m.From + (m.Target-m.From)*(1-math.Pow(1-t, 3))
}

// SetTarget retargets the fill from its current visual position.
func (m *ProgressMotion) SetTarget(target float64, now time.Time) {
	target = min(1.0, max(0.0, target))
	if target == m.Target {
		return
	}
	m.From, m.Target, m.Changed = m.Value(now), target, now
}

// Raster is a reusable premultiplied BGRA surface that is composed offscreen
// and presented in one AlphaBlend. Animated frames do not allocate GDI objects
// or erase first.
type Raster struct {
	Bitmap, Device uintptr
	previous       uintptr
	Width, Height  int32
	Pixels         []byte
}

// NewRaster creates a raster of the given size, or nil when it cannot.
func NewRaster(width, height int32) *Raster {
	if width <= 0 || height <= 0 {
		return nil
	}
	r := &Raster{Width: width, Height: height}
	r.Device, _, _ = win32.CreateCompatibleDC.Call(0)
	if r.Device == 0 {
		return nil
	}
	info := win32.BitmapInfo{Header: win32.BitmapInfoHeader{Size: 40, Width: width, Height: -height, Planes: 1, BitCount: 32}}
	var pixels unsafe.Pointer
	r.Bitmap, _, _ = win32.CreateDIBSection.Call(r.Device, uintptr(unsafe.Pointer(&info)), 0, uintptr(unsafe.Pointer(&pixels)), 0, 0)
	if r.Bitmap == 0 || pixels == nil {
		r.Dispose()
		return nil
	}
	r.previous, _, _ = win32.SelectObject.Call(r.Device, r.Bitmap)
	r.Pixels = unsafe.Slice((*byte)(pixels), int(width*height*4))
	return r
}

// Dispose releases the raster's GDI objects; a nil raster is ignored.
func (r *Raster) Dispose() {
	if r == nil {
		return
	}
	if r.previous != 0 {
		win32.SelectObject.Call(r.Device, r.previous)
	}
	if r.Bitmap != 0 {
		win32.DeleteObject.Call(r.Bitmap)
	}
	if r.Device != 0 {
		win32.DeleteDC.Call(r.Device)
	}
	r.Bitmap, r.Device, r.previous, r.Pixels = 0, 0, 0, nil
}

// Draw blends the raster onto device at x, y.
func (r *Raster) Draw(device uintptr, x, y int32) {
	if r == nil {
		return
	}
	win32.AlphaBlend.Call(device, uintptr(x), uintptr(y), uintptr(r.Width), uintptr(r.Height), r.Device, 0, 0, uintptr(r.Width), uintptr(r.Height), blendValue())
}

// PaintColor is an RGB color with floating point channels in 0..255.
type PaintColor [3]float64

// PaintRGB converts a COLORREF into a PaintColor.
func PaintRGB(color uintptr) PaintColor {
	return PaintColor{float64(color & 255), float64((color >> 8) & 255), float64((color >> 16) & 255)}
}

func mix(a, b PaintColor, coverage float64) PaintColor {
	return PaintColor{a[0] + (b[0]-a[0])*coverage, a[1] + (b[1]-a[1])*coverage, a[2] + (b[2]-a[2])*coverage}
}

// Analytic edge coverage gives the pill a smooth subpixel boundary, including
// while its leading edge is between physical screen pixels.
func pillCoverage(x, y, left, top, right, bottom float64) float64 {
	if right <= left || bottom <= top {
		return 0
	}
	hw, hh := (right-left)/2, (bottom-top)/2
	r := min(hw, hh)
	dx := max(0.0, math.Abs(x-(left+right)/2)-(hw-r))
	dy := max(0.0, math.Abs(y-(top+bottom)/2)-(hh-r))
	return min(1.0, max(0.0, r-math.Hypot(dx, dy)+0.5))
}

func paintProgress(pixels []byte, width, height int32, fill, phase float64) {
	PaintProgressOnBackground(pixels, width, height, fill, phase, nil, 0, 0)
}

// PaintProgressOnBackground paints the progress pill over a region of background.
func PaintProgressOnBackground(pixels []byte, width, height int32, fill, phase float64, background *Raster, originX, originY int32) {
	w, h := float64(width), float64(height)
	fill = min(1.0, max(0.0, fill))
	end := 2 + (w-4)*fill
	band := max(h*2, (end-2)*0.18)
	sheen := -band + (end-2+2*band)*phase
	for y := 0; y < int(height); y++ {
		fy := float64(y) + 0.5
		base := PaintColor{15, 177 - 67*fy/h, 249 - 9*fy/h}
		for x := 0; x < int(width); x++ {
			fx := float64(x) + 0.5
			baseColor := PaintColor{15, 18, 24}
			if background != nil {
				baseColor = background.ColorAt(int32(x)+originX, int32(y)+originY)
			}
			color := mix(baseColor, PaintColor{65, 83, 105}, pillCoverage(fx, fy, 0, 0, w, h))
			color = mix(color, PaintColor{25, 33, 43}, pillCoverage(fx, fy, 1, 1, w-1, h-1))
			if fill > 0 {
				outer := pillCoverage(fx, fy, 2, 2, end, h-2)
				color = mix(color, PaintColor{62, 194, 251}, outer)
				gloss := 0.38 * math.Exp(-4*math.Pow((fx-sheen)/band, 2))
				lit := mix(base, PaintColor{215, 246, 255}, gloss)
				color = mix(color, lit, pillCoverage(fx, fy, 3, 3, end-1, h-3))
			}
			i := (y*int(width) + x) * 4
			pixels[i], pixels[i+1], pixels[i+2], pixels[i+3] = byte(color[2]+0.5), byte(color[1]+0.5), byte(color[0]+0.5), 255
		}
	}
}

func segmentDistance(x, y, ax, ay, bx, by float64) float64 {
	dx, dy := bx-ax, by-ay
	t := min(1.0, max(0.0, ((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)))
	return math.Hypot(x-ax-t*dx, y-ay-t*dy)
}

// Small icons are sampled at 4x4 positions per destination pixel, then stored
// with premultiplied alpha. This smooths circles and diagonal checks against
// the actual card artwork rather than adding a dark rectangular background.
// PaintIcon paints a premultiplied, antialiased status icon of the given kind.
func PaintIcon(pixels []byte, size int32, kind string, color PaintColor) {
	r := float64(size)/2 - 1
	for y := 0; y < int(size); y++ {
		for x := 0; x < int(size); x++ {
			var sum PaintColor
			alpha := 0.0
			for sy := 0; sy < 4; sy++ {
				for sx := 0; sx < 4; sx++ {
					fx := float64(x) + (float64(sx)+0.5)/4 - float64(size)/2
					fy := float64(y) + (float64(sy)+0.5)/4 - float64(size)/2
					d := math.Hypot(fx, fy)
					c, visible, weight := color, d <= r && d >= r-1.5, 1.0
					if kind == "alert" {
						visible = fy >= -r*0.95 && fy <= r*0.8 && math.Abs(fx) <= (fy+r*0.95)*0.55
						c = mix(PaintColor{255, 213, 105}, PaintColor{242, 170, 45}, (fy+r)/(2*r))
						if segmentDistance(fx, fy, 0, -r*0.28, 0, r*0.18) < 0.9 || math.Hypot(fx, fy-r*0.48) < 1.0 {
							c = PaintColor{47, 35, 16}
						}
					} else if kind == "radio-selected" {
						visible = d <= r
						if d < r-1 {
							c = PaintColor{9, 104, 217}
						}
						if d < r/3 {
							c = PaintColor{225, 245, 255}
						}
					} else if kind == "working" {
						visible = visible || d < r*0.46
					} else if kind == "done" {
						visible = visible || min(segmentDistance(fx, fy, -r*0.45, 0, -r*0.1, r*0.32), segmentDistance(fx, fy, -r*0.1, r*0.32, r*0.5, -r*0.4)) < 0.85
					} else if kind == "failed" || kind == "warning" {
						visible = visible || segmentDistance(fx, fy, 0, -r*0.45, 0, r*0.06) < 0.8 || math.Hypot(fx, fy-r*0.4) < 0.9
					} else if kind == "dot" {
						visible = d <= r
					} else if kind == "badge-done" || kind == "badge-failed" || kind == "badge-warning" {
						// A tinted disc with a solid ring and glyph: large enough to
						// read as the page's outcome, quiet enough to sit beside the title.
						stroke := max(1.1, r*0.085)
						glyph := segmentDistance(fx, fy, 0, -r*0.46, 0, r*0.08) < stroke || math.Hypot(fx, fy-r*0.42) < stroke*1.15
						if kind == "badge-done" {
							glyph = min(segmentDistance(fx, fy, -r*0.42, r*0.02, -r*0.12, r*0.32), segmentDistance(fx, fy, -r*0.12, r*0.32, r*0.46, -r*0.30)) < stroke
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

// ColorAt returns the color at x, y, or the page background outside the raster.
func (r *Raster) ColorAt(x, y int32) PaintColor {
	if x < 0 || y < 0 || x >= r.Width || y >= r.Height {
		return PaintColor{15, 18, 24}
	}
	i := (int(y)*int(r.Width) + int(x)) * 4
	return PaintColor{float64(r.Pixels[i+2]), float64(r.Pixels[i+1]), float64(r.Pixels[i])}
}

// PutColor stores an opaque color at x, y.
func (r *Raster) PutColor(x, y int32, color PaintColor) {
	i := (int(y)*int(r.Width) + int(x)) * 4
	r.Pixels[i], r.Pixels[i+1], r.Pixels[i+2], r.Pixels[i+3] = byte(color[2]+0.5), byte(color[1]+0.5), byte(color[0]+0.5), 255
}

func roundedCoverage(x, y, left, top, right, bottom, radius float64) float64 {
	hw, hh := (right-left)/2, (bottom-top)/2
	r := min(radius, hw, hh)
	dx := max(0.0, math.Abs(x-(left+right)/2)-(hw-r))
	dy := max(0.0, math.Abs(y-(top+bottom)/2)-(hh-r))
	return min(1.0, max(0.0, r-math.Hypot(dx, dy)+0.5))
}

// GradientPanel paints a rounded, bordered, vertically graded panel.
func (r *Raster) GradientPanel(rect win32.Rect, top, bottom, border PaintColor, radius float64) {
	for y := max(int32(0), rect.Top); y < min(r.Height, rect.Bottom); y++ {
		fill := mix(top, bottom, float64(y-rect.Top)/float64(rect.Bottom-rect.Top))
		for x := max(int32(0), rect.Left); x < min(r.Width, rect.Right); x++ {
			fx, fy := float64(x)+0.5, float64(y)+0.5
			left, right, t, b := float64(rect.Left), float64(rect.Right), float64(rect.Top), float64(rect.Bottom)
			color := mix(r.ColorAt(x, y), border, roundedCoverage(fx, fy, left, t, right, b, radius))
			color = mix(color, fill, roundedCoverage(fx, fy, left+1, t+1, right-1, b-1, max(1.0, radius-1)))
			r.PutColor(x, y, color)
		}
	}
}
