//go:build windows

package main

import (
	"bytes"
	"embed"
	"errors"
	"fmt"
	"image/png"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// installerLogoPNG is the product mark already used by the Synesis browser
// surface. Embedding it keeps the native installer self-contained.
//
//go:embed assets/synesis-logo.png
var installerLogoPNG []byte

//go:embed assets/setup/*.png
var installerArtwork embed.FS

type installerArt struct {
	bitmap        uintptr
	width, height int32
}

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
	installerDesktopScale = 0.64

	installerActionInstall installerAction = iota
	installerActionUninstall

	installerPageAction installerPage = iota
	installerPageInstall
	installerPageUninstall
	installerPageConfirm
	installerPageProgress
	installerPageResult

	installerMessageDone = 0x8001
	installerMessageShow = 0x8002

	wsChild         = 0x40000000
	wsVisible       = 0x10000000
	wsTabStop       = 0x00010000
	wsGroup         = 0x00020000
	wsCaption       = 0x00c00000
	wsSysMenu       = 0x00080000
	wsMinimizeBox   = 0x00020000
	wsClipChildren  = 0x02000000
	wsExClientEdge  = 0x00000200
	wsExDlgModal    = 0x00000001
	ssLeft          = 0x00000000
	ssCenter        = 0x00000001
	ssLeftNoWrap    = 0x0000000c
	ssEtchedHorz    = 0x00000010
	ssOwnerDraw     = 0x0000000d
	bsPushButton    = 0x00000000
	bsDefPushButton = 0x00000001
	bsAutoCheckBox  = 0x00000003
	bsOwnerDraw     = 0x0000000b
	esAutoHScroll   = 0x00000080
	esReadOnly      = 0x00000800
	pbsMarquee      = 0x00000008

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
	bmGetCheck       = 0x00f0
	bmSetCheck       = 0x00f1
	bnClicked        = 0
	bstChecked       = 1
	swHide           = 0
	swShow           = 5
	rdwInvalidate    = 0x0001
	rdwErase         = 0x0004
	rdwAllChildren   = 0x0080
	rdwUpdateNow     = 0x0100

	mbOK              = 0x00000000
	mbIconError       = 0x00000010
	mbIconInformation = 0x00000040
	mbIconWarning     = 0x00000030

	transparent = 1
	opaque      = 2
	nullBrush   = 5
	nullPen     = 8
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
	controlUninstallPanel
	controlUninstallGroup
	controlUninstallPathLabel
	controlUninstallPath
	controlMetadata
	controlUninstallWarning
	controlConfirmGroup
	controlConfirmSummary
	controlProgressStatus
	controlProgressBar
	controlResult
	controlBack
	controlNext
	controlCancel
)

const (
	pbmSetBkColor  = wmUser + 1
	pbmSetBarColor = wmUser + 9
	pbmSetMarquee  = wmUser + 10
)

var errInstallerBrowseCancelled = errors.New("folder selection cancelled")

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
	installerEllipse            = installerGDI32.NewProc("Ellipse")
	installerPolygon            = installerGDI32.NewProc("Polygon")
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

type installerAction uint8
type installerPage uint8

type installerOperationResult struct {
	err     error
	warning string
}

type installerWizard struct {
	art             map[string]installerArt
	bundle          string
	installRoot     string
	scale           float64
	installed       bool
	action          installerAction
	page            installerPage
	metadata        bool
	result          installerOperationResult
	hwnd            uintptr
	instance        uintptr
	controls        map[int]uintptr
	busy            bool
	mu              sync.Mutex
	titleFont       uintptr
	bodyFont        uintptr
	detailFont      uintptr
	productFont     uintptr
	buttonFont      uintptr
	cardTitleFont   uintptr
	cardDetailFont  uintptr
	iconFont        uintptr
	wordmarkFont    uintptr
	windowIconLarge uintptr
	windowIconSmall uintptr
	logoBitmap      uintptr
	logoWidth       int32
	logoHeight      int32
	darkBrush       uintptr
	surfaceBrush    uintptr
	cardBrush       uintptr
}

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

type installerBlendFunction struct {
	blendOp             byte
	blendFlags          byte
	sourceConstantAlpha byte
	alphaFormat         byte
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

var installerWindows sync.Map
var installerWndProc = syscall.NewCallback(installerWindowProc)

// runInstallerMenu opens the native Windows setup wizard for a local bundle.
// The self-extracting wrapper delegates into this same entry point after it
// has unpacked its verified bundle into a temporary directory.
func runInstallerMenu() error {
	// Win32 windows and their child controls are thread-affine. Keep the Go
	// goroutine on the creating OS thread for the entire message-loop lifetime.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	bundle, err := runningBundleRoot()
	if err != nil {
		showInstallerMessage(0, "Synesis Setup", err.Error(), mbIconError)
		return nil
	}
	wizard, err := newInstallerWizard(bundle)
	if err != nil {
		showInstallerMessage(0, "Synesis Setup", err.Error(), mbIconError)
		return nil
	}
	if err := wizard.run(); err != nil {
		showInstallerMessage(wizard.hwnd, "Synesis Setup", err.Error(), mbIconError)
	}
	return nil
}

func newInstallerWizard(bundle string) (*installerWizard, error) {
	defaultPaths, err := installationPaths("")
	if err != nil {
		return nil, err
	}
	root := discoverWizardInstallRoot(defaultPaths.root)
	paths, err := installationPaths(root)
	if err != nil {
		return nil, err
	}
	return &installerWizard{
		bundle:      bundle,
		installRoot: paths.root,
		installed:   fileExists(paths.root),
		action:      installerActionInstall,
		page:        installerPageAction,
		controls:    make(map[int]uintptr),
	}, nil
}

// discoverWizardInstallRoot uses only the installer-owned registry marker to
// find a custom installation. It never scans arbitrary directories.
func discoverWizardInstallRoot(defaultRoot string) string {
	store := windowsProtocolStore{}
	managed, err := store.get(protocolRootKey, protocolManagedName)
	if err != nil || managed != "1" {
		return defaultRoot
	}
	root, err := store.get(protocolRootKey, protocolInstallName)
	if err != nil {
		return defaultRoot
	}
	paths, err := installationPaths(root)
	if err != nil || !fileExists(paths.root) {
		return defaultRoot
	}
	return paths.root
}

func (w *installerWizard) run() error {
	if err := w.createWindow(); err != nil {
		return err
	}
	defer installerWindows.Delete(w.hwnd)
	installerPostMessage.Call(w.hwnd, installerMessageShow, 0, 0)
	return w.messageLoop()
}

func (w *installerWizard) createWindow() error {
	instance, _, _ := installerGetModuleHandle.Call(0)
	if instance == 0 {
		return errors.New("unable to locate the setup module")
	}
	w.instance = instance
	w.darkBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(15, 18, 24))
	w.surfaceBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(27, 32, 43))
	w.cardBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(24, 29, 36))
	if w.darkBrush == 0 || w.surfaceBrush == 0 || w.cardBrush == 0 {
		return errors.New("unable to create the setup theme")
	}
	className, err := windows.UTF16PtrFromString(installerWindowClass)
	if err != nil {
		return err
	}
	w.windowIconLarge, w.windowIconSmall = loadInstallerIcons(instance)
	cursor, _, _ := installerLoadCursor.Call(0, iddArrow)
	class := installerWndClassEx{
		cbSize:      uint32(unsafe.Sizeof(installerWndClassEx{})),
		lpfnWndProc: installerWndProc,
		hInstance:   instance,
		// Keep the class icon empty so the uninstall page can genuinely suppress
		// the product mark via its page-specific WM_SETICON state.
		hIcon:         0,
		hCursor:       cursor,
		hbrBackground: w.darkBrush,
		lpszClassName: className,
		hIconSm:       0,
	}
	installerRegisterClassEx.Call(uintptr(unsafe.Pointer(&class)))

	init := struct {
		dwSize uint32
		dwICC  uint32
	}{cbSizeOfInitCommonControls, iccProgressClass}
	installerInitCommonControls.Call(uintptr(unsafe.Pointer(&init)))

	style := installerWindowStyle()
	windowWidth, windowHeight, scale := installerWindowMetrics()
	w.scale = scale
	windowRect := installerRect{right: windowWidth, bottom: windowHeight}
	installerAdjustWindowRectEx.Call(
		uintptr(unsafe.Pointer(&windowRect)),
		uintptr(style),
		0,
		wsExDlgModal,
	)
	width := windowRect.right - windowRect.left
	height := windowRect.bottom - windowRect.top
	title, err := windows.UTF16PtrFromString("Synesis Setup")
	if err != nil {
		return err
	}
	w.hwnd = createInstallerWindow(
		installerWindowClass,
		windows.UTF16PtrToString(title),
		wsExDlgModal,
		style,
		int32(-2147483648),
		int32(-2147483648),
		width,
		height,
		0,
		0,
		instance,
	)
	if w.hwnd == 0 {
		return errors.New("unable to create the setup window")
	}
	setInstallerDarkTitleBar(w.hwnd)
	w.logoBitmap, w.logoWidth, w.logoHeight, _ = createInstallerLogo(w.hwnd)
	w.art = make(map[string]installerArt)
	for _, name := range []string{"brand", "ribbon", "card", "card-selected", "card-disabled", "primary", "secondary", "install", "link", "data"} {
		data, err := installerArtwork.ReadFile("assets/setup/" + name + ".png")
		if err != nil {
			return err
		}
		bitmap, width, height, err := createInstallerBitmap(w.hwnd, data)
		if err != nil {
			return err
		}
		w.art[name] = installerArt{bitmap, width, height}
	}
	installerWindows.Store(w.hwnd, w)
	w.createControls()
	w.showPage(installerPageAction)
	return nil
}

func installerWindowStyle() uint32 {
	return uint32(wsCaption | wsSysMenu | wsMinimizeBox | wsClipChildren)
}

// installerWindowMetrics keeps the reference composition intact on a normal
// desktop and scales it down to the current work area on smaller displays.
func installerWindowMetrics() (width, height int32, scale float64) {
	scale = installerDesktopScale
	workWidth, _, _ := installerGetSystemMetrics.Call(smCxWorkArea)
	workHeight, _, _ := installerGetSystemMetrics.Call(smCyWorkArea)
	if workWidth > 0 && workHeight > 0 {
		availableWidth := float64(workWidth) - 48
		availableHeight := float64(workHeight) - 48
		if availableWidth > 0 && availableHeight > 0 {
			scale = math.Min(installerDesktopScale, math.Min(availableWidth/float64(installerWindowWidth), availableHeight/float64(installerWindowHeight)))
		}
	}
	if scale <= 0 {
		scale = 1
	}
	width = int32(math.Round(float64(installerWindowWidth) * scale))
	height = int32(math.Round(float64(installerWindowHeight) * scale))
	return width, height, scale
}

// loadInstallerIcons uses the icon resource linked into the native executable
// so the same Synesis mark is visible in the title bar, taskbar, and Explorer.
// The stock application icon remains a safe fallback for development builds
// where the resource object has not been linked yet.
func loadInstallerIcons(instance uintptr) (large, small uintptr) {
	large, _, _ = installerLoadImage.Call(instance, 1, 1, 32, 32, 0x00000040)
	small, _, _ = installerLoadImage.Call(instance, 1, 1, 16, 16, 0x00000040)
	if large == 0 {
		large, _, _ = installerLoadIcon.Call(0, idiApplication)
	}
	if small == 0 {
		small = large
	}
	return large, small
}

func (w *installerWizard) scaled(value int32) int32 {
	scale := w.scale
	if scale <= 0 {
		scale = 1
	}
	return int32(math.Round(float64(value) * scale))
}

func scaledPenWidth(scale float64) uintptr {
	if scale < 0.75 {
		return 1
	}
	return 2
}

func setInstallerDarkTitleBar(hwnd uintptr) {
	value := uint32(1)
	// DWMWA_USE_IMMERSIVE_DARK_MODE is 20 on current Windows builds and 19
	// on the earlier Windows 10 implementation. Unsupported attributes simply
	// fail and leave the already-dark client area intact.
	result, _, _ := installerDwmSetWindowAttr.Call(hwnd, 20, uintptr(unsafe.Pointer(&value)), uintptr(unsafe.Sizeof(value)))
	if result != 0 {
		installerDwmSetWindowAttr.Call(hwnd, 19, uintptr(unsafe.Pointer(&value)), uintptr(unsafe.Sizeof(value)))
	}
	caption := uint32(installerRGB(15, 18, 24))
	text := uint32(installerRGB(226, 232, 240))
	border := uint32(installerRGB(15, 18, 24))
	// These attributes are ignored on older Windows builds. On current Windows
	// they prevent the system accent colour from turning the setup title bar
	// purple while the rest of the installer is deliberately near-black.
	installerDwmSetWindowAttr.Call(hwnd, 35, uintptr(unsafe.Pointer(&caption)), uintptr(unsafe.Sizeof(caption)))
	installerDwmSetWindowAttr.Call(hwnd, 36, uintptr(unsafe.Pointer(&text)), uintptr(unsafe.Sizeof(text)))
	installerDwmSetWindowAttr.Call(hwnd, 34, uintptr(unsafe.Pointer(&border)), uintptr(unsafe.Sizeof(border)))
}

// setPageIcon removes the product mark from the uninstall surface while
// retaining the normal application icon on the install/update pages. The
// uninstall page is intentionally the quiet, logo-free screen from the
// approved mockup; this changes presentation only and does not affect the
// executable or taskbar resource.
func (w *installerWizard) setPageIcon(page installerPage) {
	large, small := w.windowIconLarge, w.windowIconSmall
	if page == installerPageUninstall {
		large, small = 0, 0
	}
	installerSendMessage.Call(w.hwnd, wmSetIcon, iconBig, large)
	installerSendMessage.Call(w.hwnd, wmSetIcon, iconSmall, small)
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

func (w *installerWizard) createControls() {
	child := uint32(wsChild | wsVisible)
	button := uint32(wsChild | wsVisible | wsTabStop)
	static := uint32(wsChild | wsVisible | ssLeft)

	w.controls[controlTitle] = w.addControl(controlTitle, "STATIC", "", static, 60, 48, 1280, 68, 0)
	w.controls[controlDescription] = w.addControl(controlDescription, "STATIC", "", static, 60, 122, 1280, 78, 0)
	w.controls[controlProductName] = w.addControl(controlProductName, "STATIC", "", static, 60, 218, 700, 46, 0)
	w.controls[controlProductDetails] = w.addControl(controlProductDetails, "STATIC", "", static, 60, 264, 820, 62, 0)
	w.controls[controlCapabilities] = w.addControl(controlCapabilities, "STATIC", "", static, 60, 344, 880, 38, 0)
	w.controls[controlCapabilityInstall] = w.addControl(controlCapabilityInstall, "STATIC", "", static, 120, 392, 950, 34, 0)
	w.controls[controlCapabilityLink] = w.addControl(controlCapabilityLink, "STATIC", "", static, 120, 432, 950, 34, 0)
	w.controls[controlCapabilityData] = w.addControl(controlCapabilityData, "STATIC", "", static, 120, 472, 950, 34, 0)

	w.controls[controlActionGroup] = w.addControl(controlActionGroup, "STATIC", "Choose what to do", static, 60, 525, 1280, 40, 0)
	w.controls[controlActionInstall] = w.addControl(controlActionInstall, "BUTTON", "Install or update Synesis", button|bsOwnerDraw|wsGroup, 60, 569, 1280, 96, 0)
	w.controls[controlActionUninstall] = w.addControl(controlActionUninstall, "BUTTON", "Uninstall Synesis", button|bsOwnerDraw, 60, 681, 1280, 96, 0)
	w.controls[controlActionPath] = w.addControl(controlActionPath, "STATIC", "", static|ssLeftNoWrap, 60, 890, 900, 42, 0)

	w.controls[controlInstallGroup] = w.addControl(controlInstallGroup, "STATIC", "Destination folder", static, 60, 250, 1280, 40, 0)
	w.controls[controlInstallEdit] = w.addControl(controlInstallEdit, "EDIT", "", child|wsTabStop|esAutoHScroll, 80, 310, 1020, 46, wsExClientEdge)
	w.controls[controlBrowse] = w.addControl(controlBrowse, "BUTTON", "Browse...", button|bsPushButton, 1120, 310, 220, 46, 0)
	w.controls[controlInstallHint] = w.addControl(controlInstallHint, "STATIC", "", static, 80, 382, 1180, 60, 0)

	// The options surface is painted by the parent so it cannot obscure sibling
	// controls when Windows repaints the page or switches focus.
	w.controls[controlUninstallGroup] = w.addControl(controlUninstallGroup, "STATIC", "Uninstall options", static, 88, 274, 1190, 40, 0)
	w.controls[controlUninstallPathLabel] = w.addControl(controlUninstallPathLabel, "STATIC", "Installation folder", static, 88, 328, 1190, 34, 0)
	w.controls[controlUninstallPath] = w.addControl(controlUninstallPath, "EDIT", "", child|esReadOnly|esAutoHScroll, 90, 368, 1220, 58, 0)
	w.controls[controlMetadata] = w.addControl(controlMetadata, "BUTTON", "Remove Synesis metadata, identity, and installer state", button|bsOwnerDraw, 88, 454, 1220, 54, 0)
	w.controls[controlUninstallWarning] = w.addControl(controlUninstallWarning, "STATIC", "", static, 136, 516, 1170, 74, 0)

	w.controls[controlConfirmGroup] = w.addControl(controlConfirmGroup, "STATIC", "Review", static, 60, 250, 1280, 40, 0)
	w.controls[controlConfirmSummary] = w.addControl(controlConfirmSummary, "STATIC", "", static, 80, 302, 1180, 260, 0)

	w.controls[controlProgressStatus] = w.addControl(controlProgressStatus, "STATIC", "", static, 60, 300, 1280, 70, 0)
	w.controls[controlProgressBar] = w.addControl(controlProgressBar, "msctls_progress32", "", child|pbsMarquee, 60, 410, 1280, 34, 0)
	w.controls[controlResult] = w.addControl(controlResult, "STATIC", "", static, 60, 270, 1280, 330, 0)
	// Give the compact action row an even rhythm and enough visual weight for
	// the destructive primary action without changing its command routing.
	w.controls[controlBack] = w.addControl(controlBack, "BUTTON", "Back", button|bsOwnerDraw, 780, 886, 185, 72, 0)
	w.controls[controlNext] = w.addControl(controlNext, "BUTTON", "Next  ›", button|bsOwnerDraw, 985, 886, 175, 72, 0)
	w.controls[controlCancel] = w.addControl(controlCancel, "BUTTON", "Cancel", button|bsOwnerDraw, 1174, 886, 165, 72, 0)

	font, _, _ := installerGetStockObject.Call(defaultGuiFont)
	for _, control := range w.controls {
		installerSendMessage.Call(control, wmSetFont, font, 1)
	}
	w.titleFont = createInstallerFont(w.scaled(-56), fontWeightBold)
	w.bodyFont = createInstallerFont(w.scaled(-27), fontWeightNormal)
	w.detailFont = createInstallerFont(w.scaled(-22), fontWeightNormal)
	w.productFont = createInstallerFont(w.scaled(-34), fontWeightBold)
	w.buttonFont = createInstallerFont(w.scaled(-24), fontWeightNormal)
	w.cardTitleFont = createInstallerFont(w.scaled(-27), fontWeightBold)
	w.cardDetailFont = createInstallerFont(w.scaled(-22), fontWeightNormal)
	w.iconFont = createInstallerFont(w.scaled(-60), fontWeightBold)
	w.wordmarkFont = createInstallerFont(w.scaled(-16), fontWeightNormal)
	setInstallerFont(w.controls[controlTitle], w.titleFont)
	setInstallerFont(w.controls[controlDescription], w.bodyFont)
	setInstallerFont(w.controls[controlProductName], w.productFont)
	setInstallerFont(w.controls[controlProductDetails], w.detailFont)
	setInstallerFont(w.controls[controlCapabilities], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityInstall], w.buttonFont)
	setInstallerFont(w.controls[controlCapabilityLink], w.buttonFont)
	setInstallerFont(w.controls[controlCapabilityData], w.buttonFont)
	setInstallerFont(w.controls[controlActionGroup], w.bodyFont)
	setInstallerFont(w.controls[controlActionPath], w.detailFont)
	setInstallerFont(w.controls[controlInstallGroup], w.detailFont)
	setInstallerFont(w.controls[controlUninstallGroup], w.cardTitleFont)
	setInstallerFont(w.controls[controlUninstallPathLabel], w.bodyFont)
	setInstallerFont(w.controls[controlUninstallPath], w.bodyFont)
	setInstallerFont(w.controls[controlMetadata], w.bodyFont)
	setInstallerFont(w.controls[controlUninstallWarning], w.detailFont)
	setInstallerFont(w.controls[controlConfirmGroup], w.detailFont)
	setInstallerFont(w.controls[controlBack], w.buttonFont)
	setInstallerFont(w.controls[controlNext], w.buttonFont)
	setInstallerFont(w.controls[controlCancel], w.buttonFont)
	installerSendMessage.Call(w.controls[controlProgressBar], pbmSetBkColor, 0, installerRGB(36, 42, 52))
	installerSendMessage.Call(w.controls[controlProgressBar], pbmSetBarColor, 0, installerRGB(0, 145, 255))
	if !w.installed {
		setInstallerEnabled(w.controls[controlActionUninstall], false)
	}
}

func (w *installerWizard) addControl(id int, className, title string, style uint32, x, y, width, height int32, exStyle uint32) uintptr {
	return createInstallerWindow(className, title, exStyle, style,
		w.scaled(x), w.scaled(y), w.scaled(width), w.scaled(height), w.hwnd, uintptr(id), w.instance)
}

func createInstallerFont(height int32, weight int32) uintptr {
	face, err := windows.UTF16PtrFromString("Segoe UI")
	if err != nil {
		return 0
	}
	font, _, _ := installerCreateFont.Call(
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

func setInstallerFont(hwnd, font uintptr) {
	if hwnd != 0 && font != 0 {
		installerSendMessage.Call(hwnd, wmSetFont, font, 1)
	}
}

func (w *installerWizard) showPage(page installerPage) {
	w.page = page
	w.setPageIcon(page)
	for _, control := range w.controls {
		setInstallerVisible(control, false)
	}
	w.layoutPage(page)
	setInstallerVisible(w.controls[controlTitle], true)
	setInstallerVisible(w.controls[controlDescription], true)

	title := "Synesis Setup"
	description := "Choose an action to continue."
	switch page {
	case installerPageAction:
		if w.installed {
			title = "Update Synesis"
		} else {
			title = "Install Synesis"
		}
		description = "A local-first collaboration runtime for secure, project-aware work."
		setInstallerVisible(w.controls[controlProductName], true)
		setInstallerVisible(w.controls[controlProductDetails], true)
		setInstallerVisible(w.controls[controlCapabilities], true)
		setInstallerVisible(w.controls[controlCapabilityInstall], true)
		setInstallerVisible(w.controls[controlCapabilityLink], true)
		setInstallerVisible(w.controls[controlCapabilityData], true)
		setInstallerVisible(w.controls[controlActionGroup], true)
		setInstallerVisible(w.controls[controlActionInstall], true)
		setInstallerVisible(w.controls[controlActionUninstall], true)
		setInstallerVisible(w.controls[controlActionPath], true)
		setInstallerText(w.controls[controlProductName], "Synesis")
		setInstallerText(w.controls[controlProductDetails], "Local-first collaboration runtime\r\nVersion 0.1.0-dev.local · Synesis contributors")
		setInstallerText(w.controls[controlCapabilities], "Capabilities:")
		setInstallerText(w.controls[controlCapabilityInstall], "Install or repair the verified local runtime")
		setInstallerText(w.controls[controlCapabilityLink], "Register the per-user synesis:// invitation handler")
		setInstallerText(w.controls[controlCapabilityData], "Preserve project, repository, and workspace data")
		if w.installed {
			setInstallerText(w.controls[controlActionPath], "Current installation: "+w.installRoot)
		} else {
			setInstallerText(w.controls[controlActionPath], "No existing installation found. Install is selected.")
		}
	case installerPageInstall:
		title = "Choose Install Location"
		description = "Choose the folder where Synesis will be installed. The installer manages only its own application files and metadata."
		setInstallerVisible(w.controls[controlInstallGroup], true)
		setInstallerVisible(w.controls[controlInstallEdit], true)
		setInstallerVisible(w.controls[controlBrowse], true)
		setInstallerVisible(w.controls[controlInstallHint], true)
		setInstallerText(w.controls[controlInstallGroup], "Destination folder")
		setInstallerText(w.controls[controlInstallEdit], w.installRoot)
		setInstallerText(w.controls[controlInstallHint], "Projects, repositories, workspaces, and provider configuration are not installed or removed here.")
	case installerPageUninstall:
		title = "Uninstall Synesis"
		description = "Synesis will be removed from the selected installation folder. Your projects, repositories, and workspaces remain untouched; the optional cleanup below removes only installer-owned metadata."
		setInstallerVisible(w.controls[controlUninstallGroup], true)
		setInstallerVisible(w.controls[controlUninstallPathLabel], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerVisible(w.controls[controlMetadata], true)
		setInstallerVisible(w.controls[controlUninstallWarning], true)
		setInstallerText(w.controls[controlUninstallPath], w.installRoot)
		setInstallerText(w.controls[controlMetadata], "Remove Synesis metadata, identity, and installer state")
		setInstallerText(w.controls[controlUninstallWarning], "Checked: removes installer-owned records. Unchecked: keeps them for a future reinstall.")
	case installerPageConfirm:
		title = "Ready to Continue"
		description = "Review the choices below, then select the operation button."
		setInstallerVisible(w.controls[controlConfirmGroup], true)
		setInstallerVisible(w.controls[controlConfirmSummary], true)
		setInstallerText(w.controls[controlConfirmSummary], w.confirmationText())
	case installerPageProgress:
		if w.action == installerActionUninstall {
			title = "Uninstalling Synesis"
		} else if fileExists(filepath.Join(w.installRoot, "current.json")) {
			title = "Repairing Synesis"
		} else {
			title = "Installing Synesis"
		}
		description = "Please wait while the verified bundle is processed. This window will remain responsive until the operation finishes."
		setInstallerVisible(w.controls[controlProgressStatus], true)
		setInstallerVisible(w.controls[controlProgressBar], true)
		setInstallerText(w.controls[controlProgressStatus], "Preparing the operation...")
		installerSendMessage.Call(w.controls[controlProgressBar], pbmSetMarquee, 1, 30)
	case installerPageResult:
		setInstallerVisible(w.controls[controlResult], true)
		setInstallerText(w.controls[controlResult], w.resultText())
	}
	setInstallerText(w.controls[controlTitle], title)
	setInstallerText(w.controls[controlDescription], description)

	showBack := page == installerPageInstall || page == installerPageUninstall || page == installerPageConfirm
	showNext := page == installerPageAction || page == installerPageInstall || page == installerPageUninstall || page == installerPageConfirm
	setInstallerVisible(w.controls[controlBack], showBack)
	setInstallerVisible(w.controls[controlNext], showNext)
	setInstallerVisible(w.controls[controlCancel], page != installerPageResult)
	if page == installerPageResult {
		setInstallerText(w.controls[controlCancel], "Close")
	} else {
		setInstallerText(w.controls[controlCancel], "Cancel")
	}
	if showNext {
		label := "Next  ›"
		if page == installerPageUninstall {
			label = "Uninstall"
		}
		if page == installerPageConfirm {
			if w.action == installerActionUninstall {
				label = "Uninstall"
			} else if fileExists(filepath.Join(w.installRoot, "current.json")) {
				label = "Repair"
			} else {
				label = "Install"
			}
		}
		setInstallerText(w.controls[controlNext], label)
	}
	if page == installerPageAction {
		installerSetFocus.Call(w.controls[controlActionInstall])
	} else if page == installerPageInstall {
		installerSetFocus.Call(w.controls[controlInstallEdit])
	}
	// A hidden owner-drawn child can leave its old pixels behind when a page
	// changes. Redraw the parent and all children synchronously so returning
	// from uninstall cannot expose the old Back button.
	installerRedrawWindow.Call(w.hwnd, 0, 0, rdwInvalidate|rdwErase|rdwAllChildren|rdwUpdateNow)
}

// layoutPage confines shared controls to the space actually available on each
// page. All coordinates retain the existing compact window's 0.64 scale.
func (w *installerWizard) layoutPage(page installerPage) {
	move := func(id int, x, y, width, height int32) {
		installerMoveWindow.Call(w.controls[id], uintptr(w.scaled(x)), uintptr(w.scaled(y)),
			uintptr(w.scaled(width)), uintptr(w.scaled(height)), 0)
	}
	headerWidth := int32(1040)
	if page == installerPageUninstall {
		headerWidth = 1280
	}
	move(controlTitle, 60, 48, headerWidth, 68)
	descriptionHeight := int32(60)
	if page == installerPageUninstall {
		descriptionHeight = 100
	}
	move(controlDescription, 60, 122, headerWidth, descriptionHeight)
	move(controlNext, 1010, 894, 175, 64)
	move(controlCancel, 1174, 894, 165, 64)
	move(controlBack, 780, 894, 185, 64)
	if page == installerPageAction {
		move(controlActionPath, 60, 890, 900, 42)
		move(controlNext, 980, 879, 175, 64)
		move(controlCancel, 1174, 879, 165, 64)
	}
	if page == installerPageUninstall {
		move(controlBack, 740, 886, 180, 72)
		move(controlNext, 930, 886, 220, 72)
		move(controlCancel, 1159, 886, 180, 72)
	}
}

func (w *installerWizard) confirmationText() string {
	if w.action == installerActionUninstall {
		metadata := "No: installer-owned identity and admin metadata will be kept."
		if w.metadata {
			metadata = "Yes: installer-owned identity and admin metadata will also be removed."
		}
		return "Operation: Uninstall Synesis\r\n\r\n" +
			"Installation folder:\r\n" + w.installRoot + "\r\n\r\n" +
			metadata + "\r\n\r\n" +
			"Projects, repositories, workspaces, and provider data are preserved."
	}
	operation := "Install"
	if fileExists(filepath.Join(w.installRoot, "current.json")) {
		operation = "Repair"
	}
	return "Operation: " + operation + " Synesis\r\n\r\n" +
		"Installation folder:\r\n" + w.installRoot + "\r\n\r\n" +
		"The verified local bundle will be activated and the per-user\r\n" +
		"synesis:// invitation handler will be registered if available."
}

func (w *installerWizard) resultText() string {
	if w.result.err != nil {
		return "The operation could not be completed.\r\n\r\n" + w.result.err.Error()
	}
	if w.result.warning != "" {
		return "Synesis was installed successfully, but one integration step needs attention.\r\n\r\n" + w.result.warning
	}
	return "Synesis setup completed successfully.\r\n\r\nYou can close this window and use the installed Synesis command."
}

func (w *installerWizard) messageLoop() error {
	var message installerMessage
	for {
		result, _, callErr := installerGetMessage.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(result) == -1 {
			return fmt.Errorf("Windows setup message loop failed: %v", callErr)
		}
		if result == 0 {
			return nil
		}
		installerTranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		installerDispatchMessage.Call(uintptr(unsafe.Pointer(&message)))
	}
}

func (w *installerWizard) handleCommand(id uint16, notification uint16) {
	if notification != bnClicked {
		return
	}
	switch int(id) {
	case controlActionInstall:
		w.action = installerActionInstall
		w.invalidateActionCards()
	case controlActionUninstall:
		if w.installed {
			w.action = installerActionUninstall
			w.invalidateActionCards()
		}
	case controlMetadata:
		w.metadata = !w.metadata
		installerInvalidateRect.Call(w.controls[controlMetadata], 0, 1)
	case controlBrowse:
		w.browse()
	case controlBack:
		w.goBack()
	case controlNext:
		w.goNext()
	case controlCancel:
		w.closeOrWarn()
	}
}

func (w *installerWizard) invalidateActionCards() {
	for _, controlID := range []int{controlActionInstall, controlActionUninstall} {
		if control := w.controls[controlID]; control != 0 {
			installerInvalidateRect.Call(control, 0, 1)
		}
	}
}

func (w *installerWizard) browse() {
	selected, err := browseInstallerFolder(w.bundle)
	if errors.Is(err, errInstallerBrowseCancelled) {
		return
	}
	if err != nil {
		showInstallerMessage(w.hwnd, "Choose Install Location", err.Error(), mbIconError)
		return
	}
	setInstallerText(w.controls[controlInstallEdit], selected)
}

func drawInstallerChrome(w *installerWizard) {
	paint := installerPaintStruct{}
	hdc, _, _ := installerBeginPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	if hdc == 0 {
		return
	}
	defer installerEndPaint.Call(w.hwnd, uintptr(unsafe.Pointer(&paint)))
	client := installerRect{}
	installerGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&client)))
	installerFillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), w.darkBrush)
	if w.page == installerPageUninstall {
		w.drawUninstallPanel(hdc)
	}

	if w.page != installerPageUninstall {
		// Keep the ribbon outside opaque native text-control bounds.
		w.drawArt(hdc, "ribbon", w.scaled(1100), 0, w.scaled(300), w.scaled(310))
		brandRight := client.right - w.scaled(60)
		brandLeft := brandRight - w.scaled(190)
		brandTop := w.scaled(40)
		brandBottom := brandTop + w.scaled(190)
		w.drawArt(hdc, "brand", brandLeft, brandTop, w.scaled(190), w.scaled(190))
		if w.logoBitmap != 0 {
			drawInstallerLogo(hdc, w.logoBitmap, w.logoWidth, w.logoHeight,
				brandLeft+w.scaled(25), brandTop+w.scaled(22), w.scaled(140), w.scaled(140))
		} else {
			oldFont, _, _ := installerSelectObject.Call(hdc, w.iconFont)
			installerSetBkMode.Call(hdc, transparent)
			installerSetTextColor.Call(hdc, installerRGB(255, 255, 255))
			logo, _ := windows.UTF16PtrFromString("S")
			logoRect := installerRect{left: brandLeft, top: brandTop, right: brandRight, bottom: brandBottom}
			installerDrawText.Call(hdc, uintptr(unsafe.Pointer(logo)), 1, uintptr(unsafe.Pointer(&logoRect)), dtCenter|dtVCenter|dtSingleLine)
			installerSelectObject.Call(hdc, oldFont)
		}
		oldFont, _, _ := installerSelectObject.Call(hdc, w.wordmarkFont)
		installerSetBkMode.Call(hdc, transparent)
		installerSetTextColor.Call(hdc, installerRGB(170, 182, 201))
		wordmark, _ := windows.UTF16PtrFromString("S  Y  N  E  S  I  S")
		wordmarkRect := installerRect{left: brandLeft, top: brandBottom + w.scaled(20), right: brandRight, bottom: brandBottom + w.scaled(48)}
		installerDrawText.Call(hdc, uintptr(unsafe.Pointer(wordmark)), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&wordmarkRect)), dtCenter|dtVCenter|dtSingleLine)
		installerSelectObject.Call(hdc, oldFont)
	}

	linePen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(35, 43, 54))
	oldPen, _, _ := installerSelectObject.Call(hdc, linePen)
	if w.page != installerPageUninstall {
		installerMoveToEx.Call(hdc, uintptr(w.scaled(60)), uintptr(w.scaled(192)), 0)
		installerLineTo.Call(hdc, uintptr(w.scaled(1030)), uintptr(w.scaled(192)))
	}
	installerSelectObject.Call(hdc, oldPen)
	installerDeleteObject.Call(linePen)

	for _, capability := range []struct {
		kind string
		y    int32
	}{
		{kind: "install", y: 409},
		{kind: "link", y: 449},
		{kind: "data", y: 489},
	} {
		drawInstallerCapabilityIcon(hdc, w, capability.kind, w.scaled(84), w.scaled(capability.y), w.scaled(28))
	}
}

func (w *installerWizard) drawUninstallPanel(device uintptr) {
	drawInstallerSurface(device, installerRect{left: w.scaled(60), top: w.scaled(250), right: w.scaled(1340), bottom: w.scaled(650)},
		installerRGB(24, 29, 36), installerRGB(50, 60, 72), w.scaled(15), w.scale)

	// Keep the read-only path field visually distinct without making it look
	// editable. The EDIT child is inset slightly so this border remains visible.
	drawInstallerSurface(device, installerRect{
		left:   w.scaled(86),
		top:    w.scaled(364),
		right:  w.scaled(1314),
		bottom: w.scaled(430),
	}, installerRGB(27, 32, 43), installerRGB(58, 70, 84), w.scaled(8), w.scale)
}

func drawInstallerSurface(device uintptr, rect installerRect, fill, border uintptr, radius int32, scale float64) {
	brush, _, _ := installerCreateSolidBrush.Call(fill)
	pen, _, _ := installerCreatePen.Call(0, scaledPenWidth(scale), border)
	if brush == 0 || pen == 0 {
		if brush != 0 {
			installerDeleteObject.Call(brush)
		}
		if pen != 0 {
			installerDeleteObject.Call(pen)
		}
		return
	}
	oldBrush, _, _ := installerSelectObject.Call(device, brush)
	oldPen, _, _ := installerSelectObject.Call(device, pen)
	installerRoundRect.Call(device, uintptr(rect.left), uintptr(rect.top), uintptr(rect.right), uintptr(rect.bottom), uintptr(radius), uintptr(radius))
	installerSelectObject.Call(device, oldPen)
	installerSelectObject.Call(device, oldBrush)
	installerDeleteObject.Call(pen)
	installerDeleteObject.Call(brush)
}

func (w *installerWizard) drawMetadataControl(device uintptr) {
	control := w.controls[controlMetadata]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	installerFillRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.cardBrush)

	checkSize := w.scaled(28)
	checkLeft := w.scaled(2)
	checkTop := (rect.bottom - checkSize) / 2
	checkRect := installerRect{left: checkLeft, top: checkTop, right: checkLeft + checkSize, bottom: checkTop + checkSize}
	checkColor := installerRGB(156, 171, 192)
	if w.metadata {
		checkColor = installerRGB(71, 151, 235)
	}
	checkPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), checkColor)
	checkBrush, _, _ := installerGetStockObject.Call(nullBrush)
	if w.metadata {
		checkBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(71, 151, 235))
	}
	if checkPen != 0 && checkBrush != 0 {
		oldPen, _, _ := installerSelectObject.Call(device, checkPen)
		oldBrush, _, _ := installerSelectObject.Call(device, checkBrush)
		installerRoundRect.Call(device, uintptr(checkRect.left), uintptr(checkRect.top), uintptr(checkRect.right), uintptr(checkRect.bottom), uintptr(w.scaled(5)), uintptr(w.scaled(5)))
		installerSelectObject.Call(device, oldBrush)
		installerSelectObject.Call(device, oldPen)
	}
	if w.metadata {
		tickPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(238, 247, 255))
		if tickPen != 0 {
			oldPen, _, _ := installerSelectObject.Call(device, tickPen)
			installerMoveToEx.Call(device, uintptr(checkLeft+w.scaled(7)), uintptr(checkTop+w.scaled(14)), 0)
			installerLineTo.Call(device, uintptr(checkLeft+w.scaled(12)), uintptr(checkTop+w.scaled(19)))
			installerLineTo.Call(device, uintptr(checkLeft+w.scaled(22)), uintptr(checkTop+w.scaled(8)))
			installerSelectObject.Call(device, oldPen)
			installerDeleteObject.Call(tickPen)
		}
	}
	if w.metadata && checkBrush != 0 {
		installerDeleteObject.Call(checkBrush)
	}
	if checkPen != 0 {
		installerDeleteObject.Call(checkPen)
	}

	if focused, _, _ := installerGetFocus.Call(); focused == control {
		focusPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(125, 211, 252))
		focusBrush, _, _ := installerGetStockObject.Call(nullBrush)
		if focusPen != 0 && focusBrush != 0 {
			oldPen, _, _ := installerSelectObject.Call(device, focusPen)
			oldBrush, _, _ := installerSelectObject.Call(device, focusBrush)
			inset := w.scaled(1)
			installerRoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.right-inset), uintptr(rect.bottom-inset), uintptr(w.scaled(6)), uintptr(w.scaled(6)))
			installerSelectObject.Call(device, oldBrush)
			installerSelectObject.Call(device, oldPen)
		}
		if focusPen != 0 {
			installerDeleteObject.Call(focusPen)
		}
	}

	textRect := installerRect{left: w.scaled(48), top: 0, right: rect.right - w.scaled(8), bottom: rect.bottom}
	oldFont, _, _ := installerSelectObject.Call(device, w.bodyFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(244, 246, 252))
	text := getInstallerText(control)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&textRect)), dtVCenter|dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func drawInstallerCapabilityIcon(device uintptr, w *installerWizard, kind string, centerX, centerY, size int32) {
	if w.page != installerPageAction {
		return
	}
	w.drawArt(device, kind, centerX-size/2, centerY-size/2, size, size)
}

func (w *installerWizard) drawArt(device uintptr, name string, x, y, width, height int32) {
	art := w.art[name]
	if art.bitmap != 0 {
		drawInstallerLogo(device, art.bitmap, art.width, art.height, x, y, width, height)
	}
}

func createInstallerLogo(hwnd uintptr) (bitmap uintptr, width, height int32, err error) {
	return createInstallerBitmap(hwnd, installerLogoPNG)
}

func createInstallerBitmap(hwnd uintptr, dataPNG []byte) (bitmap uintptr, width, height int32, err error) {
	image, decodeErr := png.Decode(bytes.NewReader(dataPNG))
	if decodeErr != nil {
		return 0, 0, 0, fmt.Errorf("decode embedded Synesis logo: %w", decodeErr)
	}
	bounds := image.Bounds()
	width = int32(bounds.Dx())
	height = int32(bounds.Dy())
	if width == 0 || height == 0 {
		return 0, 0, 0, errors.New("embedded Synesis logo is empty")
	}
	info := installerBitmapInfo{header: installerBitmapInfoHeader{
		size:        40,
		width:       width,
		height:      -height,
		planes:      1,
		bitCount:    32,
		compression: 0,
	}}
	hdc, _, _ := installerGetDC.Call(hwnd)
	if hdc == 0 {
		return 0, 0, 0, errors.New("unable to create a logo device context")
	}
	defer installerReleaseDC.Call(hwnd, hdc)
	var pixels unsafe.Pointer
	bitmap, _, callErr := installerCreateDIBSection.Call(
		hdc,
		uintptr(unsafe.Pointer(&info)),
		0,
		uintptr(unsafe.Pointer(&pixels)),
		0,
		0,
	)
	if bitmap == 0 || pixels == nil {
		if callErr != nil {
			return 0, 0, 0, fmt.Errorf("create logo bitmap: %w", callErr)
		}
		return 0, 0, 0, errors.New("create logo bitmap failed")
	}
	data := unsafe.Slice((*byte)(pixels), int(width*height*4))
	for y := 0; y < int(height); y++ {
		for x := 0; x < int(width); x++ {
			red, green, blue, alpha := image.At(bounds.Min.X+x, bounds.Min.Y+y).RGBA()
			index := (y*int(width) + x) * 4
			data[index] = byte(blue >> 8)
			data[index+1] = byte(green >> 8)
			data[index+2] = byte(red >> 8)
			data[index+3] = byte(alpha >> 8)
		}
	}
	return bitmap, width, height, nil
}

func drawInstallerLogo(device, bitmap uintptr, sourceWidth, sourceHeight, x, y, width, height int32) {
	memory, _, _ := installerCreateCompatibleDC.Call(device)
	if memory == 0 {
		return
	}
	defer installerDeleteDC.Call(memory)
	oldBitmap, _, _ := installerSelectObject.Call(memory, bitmap)
	// AlphaBlend takes BLENDFUNCTION by value, not by pointer. The earlier
	// pointer form silently turned the alpha fields into address bits and made
	// the otherwise valid logo bitmap disappear.
	blend := installerBlendValue()
	installerAlphaBlend.Call(
		device,
		uintptr(x),
		uintptr(y),
		uintptr(width),
		uintptr(height),
		memory,
		0,
		0,
		uintptr(sourceWidth),
		uintptr(sourceHeight),
		blend,
	)
	installerSelectObject.Call(memory, oldBitmap)
}

func installerBlendValue() uintptr {
	return uintptr(255)<<16 | uintptr(1)<<24
}

func installerRGB(red, green, blue byte) uintptr {
	return uintptr(red) | uintptr(green)<<8 | uintptr(blue)<<16
}

func (w *installerWizard) installerControlID(hwnd uintptr) int {
	for id, control := range w.controls {
		if control == hwnd {
			return id
		}
	}
	return 0
}

func (w *installerWizard) drawButton(controlID int, device uintptr) {
	if controlID == controlActionInstall || controlID == controlActionUninstall {
		w.drawActionCard(controlID, device)
		return
	}
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	primary := controlID == controlNext
	installerFillRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.darkBrush)
	art := "secondary"
	if primary {
		art = "primary"
	}
	w.drawArt(device, art, 0, 0, rect.right, rect.bottom)
	textRed, textGreen, textBlue := byte(248), byte(250), byte(255)

	oldFont, _, _ := installerSelectObject.Call(device, w.buttonFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(textRed, textGreen, textBlue))
	text := getInstallerText(control)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), dtCenter|dtVCenter|dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func (w *installerWizard) drawActionCard(controlID int, device uintptr) {
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	if device == 0 {
		return
	}
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	selected := (controlID == controlActionInstall && w.action == installerActionInstall) ||
		(controlID == controlActionUninstall && w.action == installerActionUninstall)
	enabled := controlID != controlActionUninstall || w.installed
	textRed, textGreen, textBlue := byte(244), byte(246), byte(252)
	detailRed, detailGreen, detailBlue := byte(171), byte(185), byte(207)
	art := "card"
	if selected {
		art = "card-selected"
	}
	if !enabled {
		art = "card-disabled"
		textRed, textGreen, textBlue = 100, 116, 139
		detailRed, detailGreen, detailBlue = 71, 85, 105
	}
	installerFillRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.darkBrush)
	w.drawArt(device, art, 0, 0, rect.right, rect.bottom)
	focused, _, _ := installerGetFocus.Call()
	if focused == control && !selected {
		focusPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(125, 211, 252))
		nullBrush, _, _ := installerGetStockObject.Call(nullBrush)
		oldFocusPen, _, _ := installerSelectObject.Call(device, focusPen)
		oldFocusBrush, _, _ := installerSelectObject.Call(device, nullBrush)
		inset := w.scaled(3)
		installerRoundRect.Call(device, uintptr(inset), uintptr(inset), uintptr(rect.right-inset), uintptr(rect.bottom-inset), uintptr(w.scaled(11)), uintptr(w.scaled(11)))
		installerSelectObject.Call(device, oldFocusBrush)
		installerSelectObject.Call(device, oldFocusPen)
		installerDeleteObject.Call(focusPen)
	}

	// The mockup uses a radio indicator as the first visual anchor in each
	// card. Keep it inside the owner-drawn button so keyboard focus and mouse
	// activation continue to use the same native control.
	radioCenterX := w.scaled(48)
	radioCenterY := rect.bottom / 2
	radioRadius := w.scaled(18)
	radioPenColor := installerRGB(128, 145, 168)
	if selected {
		radioPenColor = installerRGB(115, 198, 255)
	}
	radioPen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), radioPenColor)
	oldRadioPen, _, _ := installerSelectObject.Call(device, radioPen)
	nullBrush, _, _ := installerGetStockObject.Call(nullBrush)
	radioBrush := nullBrush
	if selected {
		radioBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(9, 104, 217))
		defer installerDeleteObject.Call(radioBrush)
	}
	oldRadioBrush, _, _ := installerSelectObject.Call(device, radioBrush)
	installerEllipse.Call(device, uintptr(radioCenterX-radioRadius), uintptr(radioCenterY-radioRadius), uintptr(radioCenterX+radioRadius), uintptr(radioCenterY+radioRadius))
	installerSelectObject.Call(device, oldRadioBrush)
	installerSelectObject.Call(device, oldRadioPen)
	installerDeleteObject.Call(radioPen)
	if selected {
		dotBrush, _, _ := installerCreateSolidBrush.Call(installerRGB(225, 245, 255))
		dotPen, _, _ := installerCreatePen.Call(0, 1, installerRGB(225, 245, 255))
		oldDotBrush, _, _ := installerSelectObject.Call(device, dotBrush)
		oldDotPen, _, _ := installerSelectObject.Call(device, dotPen)
		dotRadius := w.scaled(6)
		installerEllipse.Call(device, uintptr(radioCenterX-dotRadius), uintptr(radioCenterY-dotRadius), uintptr(radioCenterX+dotRadius), uintptr(radioCenterY+dotRadius))
		installerSelectObject.Call(device, oldDotBrush)
		installerSelectObject.Call(device, oldDotPen)
		installerDeleteObject.Call(dotBrush)
		installerDeleteObject.Call(dotPen)
	}

	titleRect := installerRect{left: w.scaled(94), top: w.scaled(14), right: rect.right - w.scaled(28), bottom: w.scaled(49)}
	detailRect := installerRect{left: w.scaled(94), top: w.scaled(50), right: rect.right - w.scaled(28), bottom: rect.bottom - w.scaled(12)}
	oldFont, _, _ := installerSelectObject.Call(device, w.cardTitleFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(textRed, textGreen, textBlue))
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(getInstallerText(control)))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&titleRect)), dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
	detail := "Keep your identity, projects, and workspace data."
	if controlID == controlActionUninstall {
		detail = "Remove the application; metadata stays unless you choose otherwise."
	}
	oldFont, _, _ = installerSelectObject.Call(device, w.cardDetailFont)
	installerSetTextColor.Call(device, installerRGB(detailRed, detailGreen, detailBlue))
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(detail))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&detailRect)), dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func subtractByte(value, amount byte) byte {
	if value <= amount {
		return 0
	}
	return value - amount
}

func mustInstallerUTF16(value string) *uint16 {
	text, err := windows.UTF16PtrFromString(value)
	if err != nil {
		return nil
	}
	return text
}

func (w *installerWizard) colorStatic(device, hwnd uintptr) uintptr {
	installerSetBkMode.Call(device, transparent)
	red, green, blue := byte(226), byte(232), byte(240)
	id := w.installerControlID(hwnd)
	if id == controlUninstallPath {
		installerSetBkColor.Call(device, installerRGB(27, 32, 43))
		installerSetTextColor.Call(device, installerRGB(226, 232, 240))
		return w.surfaceBrush
	}
	switch id {
	case controlProductName:
		red, green, blue = 248, 250, 252
	case controlDescription:
		red, green, blue = 174, 186, 207
	case controlProductDetails, controlActionPath, controlInstallHint, controlUninstallWarning,
		controlCapabilityInstall, controlCapabilityLink, controlCapabilityData:
		red, green, blue = 190, 201, 219
	case controlTitle:
		red, green, blue = 248, 250, 252
	}
	installerSetTextColor.Call(device, installerRGB(red, green, blue))
	if id == controlUninstallGroup || id == controlUninstallPathLabel || id == controlUninstallWarning {
		return w.cardBrush
	}
	return w.darkBrush
}

func (w *installerWizard) disposeFonts() {
	for _, font := range []uintptr{w.titleFont, w.bodyFont, w.detailFont, w.productFont, w.buttonFont, w.cardTitleFont, w.cardDetailFont, w.iconFont, w.wordmarkFont} {
		if font != 0 {
			installerDeleteObject.Call(font)
		}
	}
	w.titleFont = 0
	w.bodyFont = 0
	w.detailFont = 0
	w.productFont = 0
	w.buttonFont = 0
	w.cardTitleFont = 0
	w.cardDetailFont = 0
	w.iconFont = 0
	w.wordmarkFont = 0
}

func (w *installerWizard) disposeTheme() {
	for _, art := range w.art {
		installerDeleteObject.Call(art.bitmap)
	}
	w.art = nil
	if w.logoBitmap != 0 {
		installerDeleteObject.Call(w.logoBitmap)
		w.logoBitmap = 0
	}
	if w.darkBrush != 0 {
		installerDeleteObject.Call(w.darkBrush)
		w.darkBrush = 0
	}
	if w.surfaceBrush != 0 {
		installerDeleteObject.Call(w.surfaceBrush)
		w.surfaceBrush = 0
	}
	if w.cardBrush != 0 {
		installerDeleteObject.Call(w.cardBrush)
		w.cardBrush = 0
	}
}

func (w *installerWizard) goBack() {
	switch w.page {
	case installerPageInstall, installerPageUninstall:
		w.showPage(installerPageAction)
	case installerPageConfirm:
		if w.action == installerActionUninstall {
			w.showPage(installerPageUninstall)
		} else {
			w.showPage(installerPageInstall)
		}
	}
}

func (w *installerWizard) goNext() {
	switch w.page {
	case installerPageAction:
		if w.action == installerActionUninstall {
			w.showPage(installerPageUninstall)
		} else {
			w.showPage(installerPageInstall)
		}
	case installerPageInstall:
		root, err := w.readInstallRoot()
		if err != nil {
			showInstallerMessage(w.hwnd, "Choose Install Location", err.Error(), mbIconError)
			return
		}
		w.installRoot = root
		w.showPage(installerPageConfirm)
	case installerPageUninstall:
		w.showPage(installerPageConfirm)
	case installerPageConfirm:
		w.startOperation()
	case installerPageResult:
		w.closeOrWarn()
	}
}

func (w *installerWizard) readInstallRoot() (string, error) {
	value := strings.TrimSpace(getInstallerText(w.controls[controlInstallEdit]))
	if value == "" {
		return "", errors.New("choose an installation folder")
	}
	paths, err := installationPaths(value)
	if err != nil {
		return "", err
	}
	if pathWithin(w.bundle, paths.root) || pathWithin(paths.root, w.bundle) {
		return "", errors.New("choose an installation folder outside the installer bundle")
	}
	if info, statErr := os.Stat(paths.root); statErr == nil && !info.IsDir() {
		return "", errors.New("the installation path is not a folder")
	}
	return paths.root, nil
}

func (w *installerWizard) startOperation() {
	if w.busy {
		return
	}
	w.busy = true
	w.showPage(installerPageProgress)
	root := w.installRoot
	action := w.action
	metadata := w.metadata
	bundle := w.bundle
	go func() {
		result := installerOperationResult{}
		paths, err := installationPaths(root)
		if err != nil {
			result.err = err
		} else if action == installerActionUninstall {
			args := []string{"--install-dir", paths.root}
			if metadata {
				args = append(args, "--remove-metadata")
			}
			result.err = runUninstall(args)
		} else {
			args := []string{"--bundle", bundle, "--install-dir", paths.root}
			if fileExists(paths.current) {
				result.err = runRepair(args)
			} else {
				result.err = runInstall("install", args)
			}
			if result.err == nil {
				if registrationErr := registerProtocol(paths); registrationErr != nil {
					result.warning = "The application is installed, but Windows could not register the synesis:// invitation handler: " + registrationErr.Error()
				}
			}
		}
		w.mu.Lock()
		w.result = result
		w.mu.Unlock()
		installerPostMessage.Call(w.hwnd, installerMessageDone, 0, 0)
	}()
}

func (w *installerWizard) handleDone() {
	w.mu.Lock()
	result := w.result
	w.mu.Unlock()
	w.busy = false
	installerSendMessage.Call(w.controls[controlProgressBar], pbmSetMarquee, 0, 0)
	w.showPage(installerPageResult)
	if result.err != nil {
		showInstallerMessage(w.hwnd, "Synesis Setup", result.err.Error(), mbIconError)
	} else if result.warning != "" {
		showInstallerMessage(w.hwnd, "Synesis Setup", result.warning, mbIconWarning)
	}
}

func (w *installerWizard) closeOrWarn() {
	if w.busy {
		showInstallerMessage(w.hwnd, "Synesis Setup", "The current operation is still running. Please wait for it to finish.", mbIconInformation)
		return
	}
	installerDestroyWindow.Call(w.hwnd)
}

func browseInstallerFolder(bundle string) (string, error) {
	picker := filepath.Join(bundle, "bin", "synesis-folder-picker.exe")
	if _, err := os.Stat(picker); err != nil {
		return "", fmt.Errorf("the native folder picker is unavailable: %w", err)
	}
	command := exec.Command(picker)
	command.Dir = bundle
	output, err := command.Output()
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && exitErr.ExitCode() == 2 {
			return "", errInstallerBrowseCancelled
		}
		return "", fmt.Errorf("the folder picker failed: %w", err)
	}
	selected := strings.TrimSpace(string(output))
	if selected == "" {
		return "", errors.New("the folder picker returned no path")
	}
	return selected, nil
}

func installerWindowProc(hwnd, message, wParam, lParam uintptr) uintptr {
	value, ok := installerWindows.Load(hwnd)
	if !ok {
		return installerDefaultWindowProc(hwnd, message, wParam, lParam)
	}
	wizard := value.(*installerWizard)
	switch uint32(message) {
	case wmPaint:
		drawInstallerChrome(wizard)
		return 0
	case wmEraseBkgnd:
		return 1
	case wmCtlColorStatic:
		return wizard.colorStatic(wParam, lParam)
	case wmCtlColorEdit:
		installerSetBkMode.Call(wParam, opaque)
		installerSetTextColor.Call(wParam, installerRGB(241, 245, 249))
		return wizard.surfaceBrush
	case wmCtlColorBtn:
		installerSetBkMode.Call(wParam, transparent)
		installerSetTextColor.Call(wParam, installerRGB(226, 232, 240))
		return wizard.darkBrush
	case wmDrawItem:
		if lParam == 0 {
			return 0
		}
		// Copy the Windows-owned message data into Go-owned memory without
		// treating an integer callback parameter as a Go pointer.
		var item installerDrawItem
		installerCopyMemory.Call(uintptr(unsafe.Pointer(&item)), lParam, unsafe.Sizeof(item))
		if int(wParam) == controlMetadata {
			wizard.drawMetadataControl(item.device)
		} else {
			wizard.drawButton(int(wParam), item.device)
		}
		return 1
	case installerMessageShow:
		showInstallerWindow(hwnd)
		return 0
	case wmCommand:
		wizard.handleCommand(uint16(wParam&0xffff), uint16((wParam>>16)&0xffff))
		return 0
	case installerMessageDone:
		wizard.handleDone()
		return 0
	case wmClose:
		wizard.closeOrWarn()
		return 0
	case wmDestroy:
		wizard.disposeFonts()
		wizard.disposeTheme()
		installerWindows.Delete(hwnd)
		installerPostQuitMessage.Call(0)
		return 0
	default:
		return installerDefaultWindowProc(hwnd, message, wParam, lParam)
	}
}

func installerDefaultWindowProc(hwnd, message, wParam, lParam uintptr) uintptr {
	result, _, _ := installerDefWindowProc.Call(hwnd, message, wParam, lParam)
	return result
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

func checkInstallerButton(hwnd uintptr, checked bool) {
	value := uintptr(bstChecked)
	if !checked {
		value = 0
	}
	installerSendMessage.Call(hwnd, bmSetCheck, value, 0)
}

func setInstallerCheck(hwnd uintptr, checked bool) {
	checkInstallerButton(hwnd, checked)
}

func installerButtonChecked(hwnd uintptr) bool {
	value, _, _ := installerSendMessage.Call(hwnd, bmGetCheck, 0, 0)
	return value == bstChecked
}
