//go:build windows

package draw

import (
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/win32"
)

// CreateFont creates a ClearType Segoe UI font of the given height and weight.
func CreateFont(height int32, weight int32) uintptr {
	return CreateNamedFont(height, weight, "Segoe UI")
}

// CreateNamedFont creates a ClearType font of the named face.
func CreateNamedFont(height int32, weight int32, name string) uintptr {
	face, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return 0
	}
	font, _, _ := win32.CreateFont.Call(
		uintptr(uint32(height)),
		0,
		0,
		0,
		uintptr(uint32(weight)),
		0,
		0,
		0,
		uintptr(1),
		0,
		0,
		5, // CLEARTYPE_QUALITY
		0, // DEFAULT_PITCH | FF_DONTCARE
		uintptr(unsafe.Pointer(face)),
	)
	return font
}

// SetFont applies font to a window; zero handles are ignored.
func SetFont(hwnd, font uintptr) {
	if hwnd != 0 && font != 0 {
		win32.SendMessage.Call(hwnd, win32.WmSetFont, font, 1)
	}
}

// StatusColor is the accent color of a progress step status.
func StatusColor(status string) uintptr {
	switch status {
	case "done":
		return win32.RGB(91, 212, 150)
	case "working":
		return win32.RGB(81, 178, 250)
	case "warning":
		return win32.RGB(239, 189, 93)
	case "failed":
		return win32.RGB(245, 122, 122)
	default:
		return win32.RGB(145, 160, 180)
	}
}
