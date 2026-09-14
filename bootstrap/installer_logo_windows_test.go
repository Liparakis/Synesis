//go:build windows

package main

import (
	"runtime"
	"strings"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

func TestInstallerPageLayoutDoesNotOverlapSharedControls(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	w := &installerWizard{controls: make(map[int]uintptr), installed: true, installRoot: `C:\Example\Synesis`}
	if err := w.createWindow(); err != nil {
		t.Fatal(err)
	}
	defer installerDestroyWindow.Call(w.hwnd)
	windowRect := func(id int) installerRect {
		var rect installerRect
		installerUser32.NewProc("GetWindowRect").Call(w.controls[id], uintptr(unsafe.Pointer(&rect)))
		return rect
	}
	for _, page := range []installerPage{installerPageAction, installerPageUninstall, installerPageInstall, installerPageConfirm, installerPageAction} {
		w.showPage(page)
		description := windowRect(controlDescription)
		if page == installerPageUninstall {
			if description.bottom >= windowRect(controlUninstallGroup).top {
				t.Fatal("uninstall description overlaps options")
			}
			if windowRect(controlUninstallPath).bottom >= windowRect(controlMetadata).top {
				t.Fatal("path overlaps metadata toggle")
			}
			continue
		}
		if page == installerPageAction {
			title := windowRect(controlTitle)
			if title.right > title.left+w.scaled(1040) {
				t.Fatal("header intrudes into logo area")
			}
			cancelRight := windowRect(controlCancel).right
			installRight := windowRect(controlActionInstall).right
			uninstallRight := windowRect(controlActionUninstall).right
			if cancelRight != installRight || cancelRight != uninstallRight {
				t.Fatalf("main-page Cancel edge is not aligned with the action cards: cancel=%d install=%d uninstall=%d", cancelRight, installRight, uninstallRight)
			}
			path := windowRect(controlActionPath)
			next := windowRect(controlNext)
			pathCenter := (path.top + path.bottom) / 2
			nextCenter := (next.top + next.bottom) / 2
			if pathCenter < nextCenter-1 || pathCenter > nextCenter+1 {
				t.Fatal("current installation is not vertically aligned with the action buttons")
			}
			var client installerRect
			installerGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
			clientOrigin := installerPoint{}
			installerClientToScreen.Call(w.hwnd, uintptr(unsafe.Pointer(&clientOrigin)))
			for _, id := range []int{controlNext, controlCancel} {
				rect := windowRect(id)
				if rect.bottom > clientOrigin.y+client.bottom {
					t.Fatalf("main-page control %d extends past the client area", id)
				}
			}
		}
	}
	w.action = installerActionUninstall
	w.showPage(installerPageAction)
	w.goNext()
	if w.page != installerPageUninstall {
		t.Fatal("uninstall selection skipped options")
	}
	w.handleCommand(uint16(controlMetadata), bnClicked)
	w.goNext()
	if w.page != installerPageConfirm || !strings.Contains(w.confirmationText(), "Yes:") {
		t.Fatal("metadata selection not preserved into confirmation")
	}
	w.goBack()
	w.handleCommand(uint16(controlMetadata), bnClicked)
	if w.metadata {
		t.Fatal("metadata could not be deselected")
	}
	w.goBack()
	if w.page != installerPageAction {
		t.Fatal("Back did not restore main page")
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

func TestInstallerWindowVisibilityIsDeferredUntilMessageLoop(t *testing.T) {
	if style := installerWindowStyle(); style&wsVisible != 0 {
		t.Fatalf("installerWindowStyle() = %#x, visibility must be deferred", style)
	}
	if installerMessageShow <= wmUser || installerMessageShow == installerMessageDone {
		t.Fatalf("installerMessageShow = %#x, want a distinct private window message", installerMessageShow)
	}
}
