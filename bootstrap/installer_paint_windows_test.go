//go:build windows

package main

import (
	"runtime"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

func TestInstallerProgressMotionIsContinuousAndBounded(t *testing.T) {
	now := time.Unix(100, 0)
	motion := installerProgressMotion{}
	motion.setTarget(0.25, now)
	previous := 0.0
	for elapsed := time.Duration(0); elapsed <= installerProgressEase; elapsed += 10 * time.Millisecond {
		value := motion.value(now.Add(elapsed))
		if value < previous || value > 0.25 {
			t.Fatalf("untruthful or reversed fill: %f", value)
		}
		previous = value
	}
	mid := now.Add(200 * time.Millisecond)
	before := motion.value(mid)
	motion.setTarget(0.75, mid)
	if got := motion.value(mid); got != before {
		t.Fatalf("retarget jumped from %f to %f", before, got)
	}
	if got := motion.value(mid.Add(time.Hour)); got != 0.75 {
		t.Fatalf("late frame overshot target: %f", got)
	}
}

func TestInstallerIconsHavePremultipliedAntialiasedEdges(t *testing.T) {
	for _, kind := range []string{"radio-selected", "radio-unselected", "pending", "working", "done", "warning", "failed", "alert"} {
		pixels := make([]byte, 20*20*4)
		paintInstallerIcon(pixels, 20, kind, installerPaintColor{115, 198, 255})
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

func TestInstallerLabelsUseAlignedGradientBackground(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	w.showPage(installerPageUninstall)
	if w.backdrop == nil || w.backdropBrush == 0 {
		t.Fatal("gradient background not created")
	}
	for _, id := range []int{controlTitle, controlUninstallPathLabel, controlUninstallWarning, controlUninstallImpact} {
		origin := w.controlOrigin(w.controls[id])
		probe := newInstallerRaster(20, 10)
		if probe == nil {
			t.Fatal("create paint probe")
		}
		rect := installerRect{right: 20, bottom: 10}
		brush := w.colorStatic(probe.device, w.controls[id])
		installerFillRect.Call(probe.device, uintptr(unsafe.Pointer(&rect)), brush)
		installerGDI32.NewProc("GdiFlush").Call()
		got, want := probe.colorAt(5, 5), w.backdrop.colorAt(origin.x+5, origin.y+5)
		probe.dispose()
		if got != want {
			t.Fatalf("control %d paints a mismatched background: %v vs %v", id, got, want)
		}
	}
}

func TestInstallerSheenMovesWithinCompletedFill(t *testing.T) {
	const width, height = 200, 18
	a, b := make([]byte, width*height*4), make([]byte, width*height*4)
	paintInstallerProgress(a, width, height, 0.5, 0.25)
	paintInstallerProgress(b, width, height, 0.5, 0.7)
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
	font := createInstallerFont(-27, fontWeightNormal)
	if font == 0 {
		t.Fatal("create installer font")
	}
	defer installerDeleteObject.Call(font)
	dc, _, _ := installerCreateCompatibleDC.Call(0)
	if dc == 0 {
		t.Fatal("create font inspection context")
	}
	defer installerDeleteDC.Call(dc)
	previous, _, _ := installerSelectObject.Call(dc, font)
	defer installerSelectObject.Call(dc, previous)
	var face [64]uint16
	count, _, _ := installerGDI32.NewProc("GetTextFaceW").Call(dc, uintptr(len(face)), uintptr(unsafe.Pointer(&face[0])))
	if count == 0 || windows.UTF16ToString(face[:]) != "Segoe UI" {
		t.Fatalf("installer selected %q instead of Segoe UI", windows.UTF16ToString(face[:]))
	}
}

func TestEmbeddedInstallerLogoCreatesBitmap(t *testing.T) {
	bitmap, width, height, err := createInstallerLogo(0)
	if err != nil {
		t.Fatalf("create embedded installer logo: %v", err)
	}
	if bitmap == 0 || width <= 0 || height <= 0 {
		t.Fatalf("invalid embedded installer logo: bitmap=%d width=%d height=%d", bitmap, width, height)
	}
	installerDeleteObject.Call(bitmap)
}

func TestInstallerBlendValueUsesPerPixelAlpha(t *testing.T) {
	const expected = uintptr(0x01ff0000)
	if value := installerBlendValue(); value != expected {
		t.Fatalf("AlphaBlend value = %#x, want %#x", value, expected)
	}
}
