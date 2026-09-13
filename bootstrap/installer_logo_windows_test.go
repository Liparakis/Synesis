//go:build windows

package main

import "testing"

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

func TestInstallerWindowVisibilityIsDeferredUntilMessageLoop(t *testing.T) {
	if style := installerWindowStyle(); style&wsVisible != 0 {
		t.Fatalf("installerWindowStyle() = %#x, visibility must be deferred", style)
	}
	if installerMessageShow <= wmUser || installerMessageShow == installerMessageDone {
		t.Fatalf("installerMessageShow = %#x, want a distinct private window message", installerMessageShow)
	}
}
