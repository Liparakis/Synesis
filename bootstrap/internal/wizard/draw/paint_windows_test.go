//go:build windows

package draw

import (
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/win32"
)

func TestInstallerProgressMotionIsContinuousAndBounded(t *testing.T) {
	now := time.Unix(100, 0)
	motion := ProgressMotion{}
	motion.SetTarget(0.25, now)
	previous := 0.0
	for elapsed := time.Duration(0); elapsed <= ProgressEase; elapsed += 10 * time.Millisecond {
		value := motion.Value(now.Add(elapsed))
		if value < previous || value > 0.25 {
			t.Fatalf("untruthful or reversed fill: %f", value)
		}
		previous = value
	}
	mid := now.Add(200 * time.Millisecond)
	before := motion.Value(mid)
	motion.SetTarget(0.75, mid)
	if got := motion.Value(mid); got != before {
		t.Fatalf("retarget jumped from %f to %f", before, got)
	}
	if got := motion.Value(mid.Add(time.Hour)); got != 0.75 {
		t.Fatalf("late frame overshot target: %f", got)
	}
}

func TestInstallerIconsHavePremultipliedAntialiasedEdges(t *testing.T) {
	for _, kind := range []string{"radio-selected", "radio-unselected", "pending", "working", "done", "warning", "failed", "alert", "check"} {
		pixels := make([]byte, 20*20*4)
		PaintIcon(pixels, 20, kind, PaintColor{115, 198, 255})
		partial, opaque := 0, 0
		for i := 0; i < len(pixels); i += 4 {
			a := pixels[i+3]
			if a > 0 && a < 255 {
				partial++
			}
			if a == 255 {
				opaque++
			}
			if pixels[i] > a || pixels[i+1] > a || pixels[i+2] > a {
				t.Fatalf("%s has non-premultiplied edge", kind)
			}
		}
		if partial < 16 || opaque == 0 || pixels[3] != 0 {
			t.Fatalf("%s lacks smooth edges or clear corners", kind)
		}
	}
}

func TestInstallerSheenMovesWithinCompletedFill(t *testing.T) {
	const width, height = 200, 18
	a, b := make([]byte, width*height*4), make([]byte, width*height*4)
	paintProgress(a, width, height, 0.5, 0.25)
	paintProgress(b, width, height, 0.5, 0.7)
	changed := 0
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			i := (y*width + x) * 4
			if a[i] != b[i] || a[i+1] != b[i+1] || a[i+2] != b[i+2] {
				changed++
				if x > 101 {
					t.Fatal("sheen painted beyond completed fill")
				}
			}
		}
	}
	if changed < 100 {
		t.Fatal("sheen did not move visibly")
	}
}

func TestInstallerSelectsRequestedTypeface(t *testing.T) {
	font := CreateFont(-27, win32.FontWeightNormal)
	if font == 0 {
		t.Fatal("create installer font")
	}
	defer win32.DeleteObject.Call(font)
	dc, _, _ := win32.CreateCompatibleDC.Call(0)
	if dc == 0 {
		t.Fatal("create font inspection context")
	}
	defer win32.DeleteDC.Call(dc)
	previous, _, _ := win32.SelectObject.Call(dc, font)
	defer win32.SelectObject.Call(dc, previous)
	var face [64]uint16
	count, _, _ := win32.GDI32.NewProc("GetTextFaceW").Call(dc, uintptr(len(face)), uintptr(unsafe.Pointer(&face[0])))
	if count == 0 || windows.UTF16ToString(face[:]) != "Segoe UI" {
		t.Fatalf("installer selected %q instead of Segoe UI", windows.UTF16ToString(face[:]))
	}
}

func TestEmbeddedInstallerLogoScalesToAPremultipliedMark(t *testing.T) {
	logo, err := NewLogo(48)
	if err != nil {
		t.Fatalf("create embedded installer logo: %v", err)
	}
	defer logo.Dispose()
	solid := 0
	for i := 0; i < len(logo.Pixels); i += 4 {
		alpha := logo.Pixels[i+3]
		if logo.Pixels[i] > alpha || logo.Pixels[i+1] > alpha || logo.Pixels[i+2] > alpha {
			t.Fatalf("logo is not premultiplied at byte %d", i)
		}
		if alpha == 255 {
			solid++
		}
	}
	if logo.Width != 48 || logo.Height != 48 || solid < 48 || logo.Pixels[3] != 0 {
		t.Fatalf("logo %dx%d has %d solid pixels and corner alpha %d", logo.Width, logo.Height, solid, logo.Pixels[3])
	}
}

func TestInstallerBlendValueUsesPerPixelAlpha(t *testing.T) {
	const expected = uintptr(0x01ff0000)
	if value := blendValue(); value != expected {
		t.Fatalf("AlphaBlend value = %#x, want %#x", value, expected)
	}
}
