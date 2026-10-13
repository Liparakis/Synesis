//go:build windows

package draw

import (
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/wizard/win32"
)

// The wizard's colors: the neutral dark palette of the Synesis browser UI.
var (
	Window     = PaintColor{10, 10, 11}
	Sidebar    = PaintColor{16, 16, 18}
	Field      = PaintColor{21, 21, 24}
	Raised     = PaintColor{30, 30, 34}
	Line       = PaintColor{40, 40, 45}
	LineStrong = PaintColor{66, 66, 74}
	Copy       = PaintColor{237, 237, 237}
	Muted      = PaintColor{163, 163, 170}
	Faint      = PaintColor{116, 116, 124}
	Green      = PaintColor{131, 201, 155}
	Amber      = PaintColor{228, 181, 95}
	Red        = PaintColor{239, 112, 118}
)

// Ref converts the color to the COLORREF layout GDI expects.
func (c PaintColor) Ref() uintptr {
	return win32.RGB(byte(c[0]), byte(c[1]), byte(c[2]))
}

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
func StatusColor(status string) PaintColor {
	switch status {
	case "done":
		return Green
	case "working":
		return Copy
	case "warning":
		return Amber
	case "failed":
		return Red
	default:
		return Faint
	}
}
