//go:build windows

// Package win32 holds the raw Win32 bindings, constants and structures the
// setup wizard draws and runs its window with.
package win32

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// Window styles, messages, drawing flags and metrics, named after their Win32 spellings.
const (
	WsChild        = 0x40000000
	WsVisible      = 0x10000000
	WsTabStop      = 0x00010000
	WsGroup        = 0x00020000
	WsCaption      = 0x00c00000
	WsSysMenu      = 0x00080000
	WsMinimizeBox  = 0x00020000
	WsClipChildren = 0x02000000
	WsExDlgModal   = 0x00000001
	SsLeft         = 0x00000000
	SsLeftNoWrap   = 0x0000000c
	SsOwnerDraw    = 0x0000000d
	BsOwnerDraw    = 0x0000000b
	EsAutoHScroll  = 0x00000080
	EsReadOnly     = 0x00000800

	WmPaint          = 0x000f
	WmEraseBkgnd     = 0x0014
	WmClose          = 0x0010
	WmDestroy        = 0x0002
	WmCommand        = 0x0111
	WmSetFont        = 0x0030
	WmCtlColorBtn    = 0x0135
	WmCtlColorEdit   = 0x0133
	WmCtlColorStatic = 0x0138
	WmDrawItem       = 0x002b
	WmSetIcon        = 0x0080
	WmUser           = 0x0400
	BnClicked        = 0
	SwHide           = 0
	SwShow           = 5
	RdwInvalidate    = 0x0001
	RdwErase         = 0x0004
	RdwAllChildren   = 0x0080
	RdwUpdateNow     = 0x0100
	RdwFrame         = 0x0400

	MbOK              = 0x00000000
	MbIconError       = 0x00000010
	MbIconInformation = 0x00000040

	Transparent = 1
	Opaque      = 2
	NullBrush   = 5
	IconSmall   = 0
	IconBig     = 1

	DtCenter      = 0x00000001
	DtVCenter     = 0x00000004
	DtSingleLine  = 0x00000020
	DtEndEllipsis = 0x00008000

	FontWeightNormal = 400
	FontWeightBold   = 700

	DefaultGuiFont             = 17
	IdiApplication             = 32512
	IddArrow                   = 32512
	CbSizeOfInitCommonControls = 8
	IccProgressClass           = 0x00000020
	SmCxWorkArea               = 16
	SmCyWorkArea               = 17
)

// The system libraries and the procedures of them that the wizard calls.
var (
	User32   = windows.NewLazySystemDLL("user32.dll")
	Kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	GDI32    = windows.NewLazySystemDLL("gdi32.dll")
	Comctl32 = windows.NewLazySystemDLL("comctl32.dll")
	MSIMG32  = windows.NewLazySystemDLL("msimg32.dll")
	DWMAPI   = windows.NewLazySystemDLL("dwmapi.dll")

	CreateWindowEx     = User32.NewProc("CreateWindowExW")
	ClientToScreen     = User32.NewProc("ClientToScreen")
	MoveWindow         = User32.NewProc("MoveWindow")
	RedrawWindow       = User32.NewProc("RedrawWindow")
	SetBkColor         = GDI32.NewProc("SetBkColor")
	RegisterClassEx    = User32.NewProc("RegisterClassExW")
	AdjustWindowRectEx = User32.NewProc("AdjustWindowRectEx")
	DefWindowProc      = User32.NewProc("DefWindowProcW")
	DestroyWindow      = User32.NewProc("DestroyWindow")
	DispatchMessage    = User32.NewProc("DispatchMessageW")
	EnableWindow       = User32.NewProc("EnableWindow")
	GetMessage         = User32.NewProc("GetMessageW")
	GetModuleHandle    = Kernel32.NewProc("GetModuleHandleW")
	CopyMemory         = Kernel32.NewProc("RtlMoveMemory")
	GetStockObject     = GDI32.NewProc("GetStockObject")
	CreateFont         = GDI32.NewProc("CreateFontW")
	CreatePen          = GDI32.NewProc("CreatePen")
	CreateSolidBrush   = GDI32.NewProc("CreateSolidBrush")
	CreateDIBSection   = GDI32.NewProc("CreateDIBSection")
	CreateCompatibleDC = GDI32.NewProc("CreateCompatibleDC")
	DeleteObject       = GDI32.NewProc("DeleteObject")
	DeleteDC           = GDI32.NewProc("DeleteDC")
	GetDC              = User32.NewProc("GetDC")
	ReleaseDC          = User32.NewProc("ReleaseDC")
	GetFocus           = User32.NewProc("GetFocus")
	GetSystemMetrics   = User32.NewProc("GetSystemMetrics")
	BeginPaint         = User32.NewProc("BeginPaint")
	EndPaint           = User32.NewProc("EndPaint")
	FillRect           = User32.NewProc("FillRect")
	FrameRect          = User32.NewProc("FrameRect")
	GetClientRect      = User32.NewProc("GetClientRect")
	GetWindowText      = User32.NewProc("GetWindowTextW")
	GetWindowTextLen   = User32.NewProc("GetWindowTextLengthW")
	LoadCursor         = User32.NewProc("LoadCursorW")
	LoadIcon           = User32.NewProc("LoadIconW")
	LoadImage          = User32.NewProc("LoadImageW")
	MessageBox         = User32.NewProc("MessageBoxW")
	DrawText           = User32.NewProc("DrawTextW")
	InvalidateRect     = User32.NewProc("InvalidateRect")
	PostMessage        = User32.NewProc("PostMessageW")
	PostQuitMessage    = User32.NewProc("PostQuitMessage")
	SendMessage        = User32.NewProc("SendMessageW")
	SetBkMode          = GDI32.NewProc("SetBkMode")
	SetTextColor       = GDI32.NewProc("SetTextColor")
	SelectObject       = GDI32.NewProc("SelectObject")
	MoveToEx           = GDI32.NewProc("MoveToEx")
	LineTo             = GDI32.NewProc("LineTo")
	RoundRect          = GDI32.NewProc("RoundRect")
	SetFocus           = User32.NewProc("SetFocus")
	SetWindowText      = User32.NewProc("SetWindowTextW")
	ShowWindow         = User32.NewProc("ShowWindow")
	TranslateMessage   = User32.NewProc("TranslateMessage")
	UpdateWindow       = User32.NewProc("UpdateWindow")
	InitCommonControls = Comctl32.NewProc("InitCommonControlsEx")
	AlphaBlend         = MSIMG32.NewProc("AlphaBlend")
	DwmSetWindowAttr   = DWMAPI.NewProc("DwmSetWindowAttribute")
)

// Point is the Win32 POINT structure.
type Point struct {
	X int32
	Y int32
}

// Rect is the Win32 RECT structure.
type Rect struct {
	Left   int32
	Top    int32
	Right  int32
	Bottom int32
}

// DrawItem matches DRAWITEMSTRUCT, including pointer alignment on
// Windows. Owner-drawn controls must use this paint context, not a new GetDC.
type DrawItem struct {
	ControlType, ControlID, ItemID, Action, State uint32
	Window, Device                                uintptr
	Rect                                          Rect
	Data                                          uintptr
}

// BitmapInfoHeader is the Win32 BITMAPINFOHEADER structure.
type BitmapInfoHeader struct {
	Size            uint32
	Width           int32
	Height          int32
	Planes          uint16
	BitCount        uint16
	Compression     uint32
	ImageSize       uint32
	XPixelsPerM     int32
	YPixelsPerM     int32
	ColorsUsed      uint32
	ColorsImportant uint32
}

// BitmapInfo is the Win32 BITMAPINFO structure with room for one color entry.
type BitmapInfo struct {
	Header BitmapInfoHeader
	Color  uint32
}

// PaintStruct is the Win32 PAINTSTRUCT structure.
type PaintStruct struct {
	Device    uintptr
	Erase     int32
	PaintRect Rect
	Restore   int32
	IncUpdate int32
	Reserved  [32]byte
}

// Message is the Win32 MSG structure.
type Message struct {
	Hwnd    uintptr
	Message uint32
	Padding uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Point   Point
	Private uint32
}

// WndClassEx is the Win32 WNDCLASSEXW structure.
type WndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     uintptr
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       uintptr
}

// CreateWindow creates a window of the given class with CreateWindowExW.
func CreateWindow(className, title string, exStyle, style uint32, x, y, width, height int32, parent, menu, instance uintptr) uintptr {
	classPtr, _ := windows.UTF16PtrFromString(className)
	titlePtr, _ := windows.UTF16PtrFromString(title)
	window, _, _ := CreateWindowEx.Call(
		uintptr(exStyle),
		uintptr(unsafe.Pointer(classPtr)),
		uintptr(unsafe.Pointer(titlePtr)),
		uintptr(style),
		uintptr(uint32(x)),
		uintptr(uint32(y)),
		uintptr(uint32(width)),
		uintptr(uint32(height)),
		parent,
		menu,
		instance,
		0,
	)
	return window
}

// ShowMessage shows a modal message box.
func ShowMessage(parent uintptr, title, message string, flags uintptr) {
	titlePtr, _ := windows.UTF16PtrFromString(title)
	messagePtr, _ := windows.UTF16PtrFromString(message)
	MessageBox.Call(parent, uintptr(unsafe.Pointer(messagePtr)), uintptr(unsafe.Pointer(titlePtr)), MbOK|flags)
}

// SetVisible shows or hides a window; a zero handle is ignored.
func SetVisible(hwnd uintptr, visible bool) {
	if hwnd == 0 {
		return
	}
	state := SwHide
	if visible {
		state = SwShow
	}
	ShowWindow.Call(hwnd, uintptr(state))
}

// Show shows a window and updates it at once; a zero handle is ignored.
func Show(hwnd uintptr) {
	if hwnd == 0 {
		return
	}
	ShowWindow.Call(hwnd, SwShow)
	UpdateWindow.Call(hwnd)
}

// SetEnabled enables or disables a window; a zero handle is ignored.
func SetEnabled(hwnd uintptr, enabled bool) {
	if hwnd != 0 {
		value := uintptr(0)
		if enabled {
			value = 1
		}
		EnableWindow.Call(hwnd, value)
	}
}

// SetText sets the text of a window; a zero handle is ignored.
func SetText(hwnd uintptr, text string) {
	if hwnd == 0 {
		return
	}
	value, err := windows.UTF16PtrFromString(text)
	if err == nil {
		SetWindowText.Call(hwnd, uintptr(unsafe.Pointer(value)))
	}
}

// GetText returns the text of a window, or "" for a zero handle.
func GetText(hwnd uintptr) string {
	if hwnd == 0 {
		return ""
	}
	length, _, _ := GetWindowTextLen.Call(hwnd)
	buffer := make([]uint16, int(length)+1)
	if len(buffer) == 0 {
		return ""
	}
	GetWindowText.Call(hwnd, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)))
	return windows.UTF16ToString(buffer)
}

// DefaultWindowProc forwards a message to DefWindowProcW.
func DefaultWindowProc(hwnd, message, wParam, lParam uintptr) uintptr {
	result, _, _ := DefWindowProc.Call(hwnd, message, wParam, lParam)
	return result
}

// MustUTF16 converts a string to UTF-16, or returns nil when it holds a NUL.
func MustUTF16(value string) *uint16 {
	text, err := windows.UTF16PtrFromString(value)
	if err != nil {
		return nil
	}
	return text
}

// RGB packs a color into the COLORREF layout GDI expects.
func RGB(red, green, blue byte) uintptr {
	return uintptr(red) | uintptr(green)<<8 | uintptr(blue)<<16
}
