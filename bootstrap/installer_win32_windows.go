//go:build windows

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	installerWindowClass = "SynesisInstallerWizard"
	// The reference composition is authored at this generous desktop size. The
	// wizard scales the client layout down only when the current work area is
	// smaller, so the presentation remains usable on compact laptops.
	installerWindowWidth  = 1400
	installerWindowHeight = 980
	// The source layout is authored at 1400x980. A compact desktop default
	// leaves the wizard closer to the supplied reference while the existing
	// work-area calculation still protects smaller displays.
	installerDesktopScale = 0.52

	installerActionInstall installerAction = iota
	installerActionUninstall

	installerPageAction installerPage = iota
	installerPageInstall
	installerPageUninstall
	installerPageProgress
	installerPageResult

	installerMessageDone     = 0x8001
	installerMessageShow     = 0x8002
	installerMessageRefresh  = 0x8003
	installerMessageProgress = 0x8004

	wsChild        = 0x40000000
	wsVisible      = 0x10000000
	wsTabStop      = 0x00010000
	wsGroup        = 0x00020000
	wsCaption      = 0x00c00000
	wsSysMenu      = 0x00080000
	wsMinimizeBox  = 0x00020000
	wsClipChildren = 0x02000000
	wsExDlgModal   = 0x00000001
	ssLeft         = 0x00000000
	ssLeftNoWrap   = 0x0000000c
	ssOwnerDraw    = 0x0000000d
	bsOwnerDraw    = 0x0000000b
	esAutoHScroll  = 0x00000080
	esReadOnly     = 0x00000800

	wmPaint          = 0x000f
	wmEraseBkgnd     = 0x0014
	wmClose          = 0x0010
	wmDestroy        = 0x0002
	wmCommand        = 0x0111
	wmSetFont        = 0x0030
	wmCtlColorBtn    = 0x0135
	wmCtlColorEdit   = 0x0133
	wmCtlColorStatic = 0x0138
	wmDrawItem       = 0x002b
	wmSetIcon        = 0x0080
	wmUser           = 0x0400
	bnClicked        = 0
	swHide           = 0
	swShow           = 5
	rdwInvalidate    = 0x0001
	rdwErase         = 0x0004
	rdwAllChildren   = 0x0080
	rdwUpdateNow     = 0x0100
	rdwFrame         = 0x0400

	mbOK              = 0x00000000
	mbIconError       = 0x00000010
	mbIconInformation = 0x00000040

	transparent = 1
	opaque      = 2
	nullBrush   = 5
	iconSmall   = 0
	iconBig     = 1

	dtCenter      = 0x00000001
	dtVCenter     = 0x00000004
	dtSingleLine  = 0x00000020
	dtEndEllipsis = 0x00008000

	fontWeightNormal = 400
	fontWeightBold   = 700

	defaultGuiFont             = 17
	idiApplication             = 32512
	iddArrow                   = 32512
	cbSizeOfInitCommonControls = 8
	iccProgressClass           = 0x00000020
	smCxWorkArea               = 16
	smCyWorkArea               = 17

	controlTitle = 1001 + iota
	controlDescription
	controlProductName
	controlProductDetails
	controlCapabilities
	controlCapabilityInstall
	controlCapabilityLink
	controlCapabilityData
	controlActionGroup
	controlActionInstall
	controlActionUninstall
	controlActionPath
	controlInstallGroup
	controlInstallEdit
	controlBrowse
	controlInstallHint
	controlUninstallGroup
	controlUninstallPathLabel
	controlUninstallPath
	controlMetadata
	controlUninstallWarning
	controlUninstallImpact
	controlProgressStatus
	controlProgressBar
	controlProgressCount
	controlProgressSteps
	controlProgressLogTitle
	controlProgressLog
	controlResult
	controlResultDetailsToggle
	controlResultDetails
	controlResultNextTitle
	controlResultNext
	controlBack
	controlNext
	controlCancel
)

var (
	installerUser32   = windows.NewLazySystemDLL("user32.dll")
	installerKernel32 = windows.NewLazySystemDLL("kernel32.dll")
	installerGDI32    = windows.NewLazySystemDLL("gdi32.dll")
	installerComctl32 = windows.NewLazySystemDLL("comctl32.dll")
	installerMSIMG32  = windows.NewLazySystemDLL("msimg32.dll")
	installerDWMAPI   = windows.NewLazySystemDLL("dwmapi.dll")

	installerCreateWindowEx     = installerUser32.NewProc("CreateWindowExW")
	installerClientToScreen     = installerUser32.NewProc("ClientToScreen")
	installerMoveWindow         = installerUser32.NewProc("MoveWindow")
	installerRedrawWindow       = installerUser32.NewProc("RedrawWindow")
	installerSetBkColor         = installerGDI32.NewProc("SetBkColor")
	installerRegisterClassEx    = installerUser32.NewProc("RegisterClassExW")
	installerAdjustWindowRectEx = installerUser32.NewProc("AdjustWindowRectEx")
	installerDefWindowProc      = installerUser32.NewProc("DefWindowProcW")
	installerDestroyWindow      = installerUser32.NewProc("DestroyWindow")
	installerDispatchMessage    = installerUser32.NewProc("DispatchMessageW")
	installerEnableWindow       = installerUser32.NewProc("EnableWindow")
	installerGetMessage         = installerUser32.NewProc("GetMessageW")
	installerGetModuleHandle    = installerKernel32.NewProc("GetModuleHandleW")
	installerCopyMemory         = installerKernel32.NewProc("RtlMoveMemory")
	installerGetStockObject     = installerGDI32.NewProc("GetStockObject")
	installerCreateFont         = installerGDI32.NewProc("CreateFontW")
	installerCreatePen          = installerGDI32.NewProc("CreatePen")
	installerCreateSolidBrush   = installerGDI32.NewProc("CreateSolidBrush")
	installerCreateDIBSection   = installerGDI32.NewProc("CreateDIBSection")
	installerCreateCompatibleDC = installerGDI32.NewProc("CreateCompatibleDC")
	installerDeleteObject       = installerGDI32.NewProc("DeleteObject")
	installerDeleteDC           = installerGDI32.NewProc("DeleteDC")
	installerGetDC              = installerUser32.NewProc("GetDC")
	installerReleaseDC          = installerUser32.NewProc("ReleaseDC")
	installerGetFocus           = installerUser32.NewProc("GetFocus")
	installerGetSystemMetrics   = installerUser32.NewProc("GetSystemMetrics")
	installerBeginPaint         = installerUser32.NewProc("BeginPaint")
	installerEndPaint           = installerUser32.NewProc("EndPaint")
	installerFillRect           = installerUser32.NewProc("FillRect")
	installerFrameRect          = installerUser32.NewProc("FrameRect")
	installerGetClientRect      = installerUser32.NewProc("GetClientRect")
	installerGetWindowText      = installerUser32.NewProc("GetWindowTextW")
	installerGetWindowTextLen   = installerUser32.NewProc("GetWindowTextLengthW")
	installerLoadCursor         = installerUser32.NewProc("LoadCursorW")
	installerLoadIcon           = installerUser32.NewProc("LoadIconW")
	installerLoadImage          = installerUser32.NewProc("LoadImageW")
	installerMessageBox         = installerUser32.NewProc("MessageBoxW")
	installerDrawText           = installerUser32.NewProc("DrawTextW")
	installerInvalidateRect     = installerUser32.NewProc("InvalidateRect")
	installerPostMessage        = installerUser32.NewProc("PostMessageW")
	installerPostQuitMessage    = installerUser32.NewProc("PostQuitMessage")
	installerSendMessage        = installerUser32.NewProc("SendMessageW")
	installerSetBkMode          = installerGDI32.NewProc("SetBkMode")
	installerSetTextColor       = installerGDI32.NewProc("SetTextColor")
	installerSelectObject       = installerGDI32.NewProc("SelectObject")
	installerMoveToEx           = installerGDI32.NewProc("MoveToEx")
	installerLineTo             = installerGDI32.NewProc("LineTo")
	installerRoundRect          = installerGDI32.NewProc("RoundRect")
	installerSetFocus           = installerUser32.NewProc("SetFocus")
	installerSetWindowText      = installerUser32.NewProc("SetWindowTextW")
	installerShowWindow         = installerUser32.NewProc("ShowWindow")
	installerTranslateMessage   = installerUser32.NewProc("TranslateMessage")
	installerUpdateWindow       = installerUser32.NewProc("UpdateWindow")
	installerInitCommonControls = installerComctl32.NewProc("InitCommonControlsEx")
	installerAlphaBlend         = installerMSIMG32.NewProc("AlphaBlend")
	installerDwmSetWindowAttr   = installerDWMAPI.NewProc("DwmSetWindowAttribute")
)

type installerPoint struct {
	x int32
	y int32
}

type installerRect struct {
	left   int32
	top    int32
	right  int32
	bottom int32
}

// installerDrawItem matches DRAWITEMSTRUCT, including pointer alignment on
// Windows. Owner-drawn controls must use this paint context, not a new GetDC.
type installerDrawItem struct {
	controlType, controlID, itemID, action, state uint32
	window, device                                uintptr
	rect                                          installerRect
	data                                          uintptr
}

type installerBitmapInfoHeader struct {
	size            uint32
	width           int32
	height          int32
	planes          uint16
	bitCount        uint16
	compression     uint32
	imageSize       uint32
	xPixelsPerM     int32
	yPixelsPerM     int32
	colorsUsed      uint32
	colorsImportant uint32
}

type installerBitmapInfo struct {
	header installerBitmapInfoHeader
	color  uint32
}

type installerPaintStruct struct {
	device    uintptr
	erase     int32
	paintRect installerRect
	restore   int32
	incUpdate int32
	reserved  [32]byte
}

type installerMessage struct {
	hwnd    uintptr
	message uint32
	padding uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	point   installerPoint
	private uint32
}

type installerWndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

func createInstallerWindow(className, title string, exStyle, style uint32, x, y, width, height int32, parent, menu, instance uintptr) uintptr {
	classPtr, _ := windows.UTF16PtrFromString(className)
	titlePtr, _ := windows.UTF16PtrFromString(title)
	window, _, _ := installerCreateWindowEx.Call(
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

func showInstallerMessage(parent uintptr, title, message string, flags uintptr) {
	titlePtr, _ := windows.UTF16PtrFromString(title)
	messagePtr, _ := windows.UTF16PtrFromString(message)
	installerMessageBox.Call(parent, uintptr(unsafe.Pointer(messagePtr)), uintptr(unsafe.Pointer(titlePtr)), mbOK|flags)
}

func setInstallerVisible(hwnd uintptr, visible bool) {
	if hwnd == 0 {
		return
	}
	state := swHide
	if visible {
		state = swShow
	}
	installerShowWindow.Call(hwnd, uintptr(state))
}

func showInstallerWindow(hwnd uintptr) {
	if hwnd == 0 {
		return
	}
	installerShowWindow.Call(hwnd, swShow)
	installerUpdateWindow.Call(hwnd)
}

func setInstallerEnabled(hwnd uintptr, enabled bool) {
	if hwnd != 0 {
		value := uintptr(0)
		if enabled {
			value = 1
		}
		installerEnableWindow.Call(hwnd, value)
	}
}

func setInstallerText(hwnd uintptr, text string) {
	if hwnd == 0 {
		return
	}
	value, err := windows.UTF16PtrFromString(text)
	if err == nil {
		installerSetWindowText.Call(hwnd, uintptr(unsafe.Pointer(value)))
	}
}

func getInstallerText(hwnd uintptr) string {
	if hwnd == 0 {
		return ""
	}
	length, _, _ := installerGetWindowTextLen.Call(hwnd)
	buffer := make([]uint16, int(length)+1)
	if len(buffer) == 0 {
		return ""
	}
	installerGetWindowText.Call(hwnd, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)))
	return windows.UTF16ToString(buffer)
}

func installerDefaultWindowProc(hwnd, message, wParam, lParam uintptr) uintptr {
	result, _, _ := installerDefWindowProc.Call(hwnd, message, wParam, lParam)
	return result
}

func mustInstallerUTF16(value string) *uint16 {
	text, err := windows.UTF16PtrFromString(value)
	if err != nil {
		return nil
	}
	return text
}
