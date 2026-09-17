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
	"time"
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
	rdwFrame         = 0x0400

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
	controlBack
	controlNext
	controlCancel
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

type installerStep struct {
	id, title, status, detail string
}

type installerLogEntry struct {
	status, detail string
}

func installerOperationSteps(action installerAction, metadata bool) []installerStep {
	if action == installerActionUninstall {
		metadataTitle := "Preserve metadata"
		if metadata {
			metadataTitle = "Remove metadata"
		}
		return []installerStep{
			{id: "shell", title: "Remove shell integration", status: "pending"},
			{id: "runtime", title: "Remove application files", status: "pending"},
			{id: "metadata", title: metadataTitle, status: "pending"},
			{id: "cleanup", title: "Clean obsolete installer files", status: "pending"},
		}
	}
	return []installerStep{
		{id: "verify", title: "Verify local bundle", status: "pending"},
		{id: "runtime", title: "Activate runtime files", status: "pending"},
		{id: "shell", title: "Update shell integration", status: "pending"},
		{id: "protocol", title: "Register synesis:// link handler", status: "pending"},
	}
}

type installerWizard struct {
	art               map[string]installerArt
	bundle            string
	installRoot       string
	installDraft      string
	scale             float64
	installed         bool
	action            installerAction
	page              installerPage
	metadata          bool
	result            installerOperationResult
	hwnd              uintptr
	instance          uintptr
	controls          map[int]uintptr
	busy              bool
	mu                sync.Mutex
	steps             []installerStep
	logEntries        []installerLogEntry
	logRendered       int
	operationStatus   string
	logFont           uintptr
	progressMotion    installerProgressMotion
	progressEpoch     time.Time
	progressComplete  time.Time
	progressRaster    *installerRaster
	progressTimer     uintptr
	smoothIcons       map[string]*installerRaster
	backdrop          *installerRaster
	backdropBrush     uintptr
	resultDetailsOpen bool
	executeOperation  func(string, string, installerAction, bool, operationProgress) installerOperationResult
	titleFont         uintptr
	bodyFont          uintptr
	detailFont        uintptr
	productFont       uintptr
	buttonFont        uintptr
	cardTitleFont     uintptr
	cardDetailFont    uintptr
	iconFont          uintptr
	wordmarkFont      uintptr
	windowIconLarge   uintptr
	windowIconSmall   uintptr
	logoBitmap        uintptr
	logoWidth         int32
	logoHeight        int32
	darkBrush         uintptr
	surfaceBrush      uintptr
	cardBrush         uintptr
	warningBrush      uintptr
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
	w.warningBrush, _, _ = installerCreateSolidBrush.Call(installerRGB(36, 31, 22))
	if w.darkBrush == 0 || w.surfaceBrush == 0 || w.cardBrush == 0 || w.warningBrush == 0 {
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

// setPageIcon keeps every wizard page on the same native application shell.
func (w *installerWizard) setPageIcon(_ installerPage) {
	installerSendMessage.Call(w.hwnd, wmSetIcon, iconBig, w.windowIconLarge)
	installerSendMessage.Call(w.hwnd, wmSetIcon, iconSmall, w.windowIconSmall)
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

	w.controls[controlTitle] = w.addControl(controlTitle, "STATIC", "", static, 60, 48, 1280, 84, 0)
	w.controls[controlDescription] = w.addControl(controlDescription, "STATIC", "", static, 60, 132, 1280, 58, 0)
	w.controls[controlProductName] = w.addControl(controlProductName, "STATIC", "", static, 60, 218, 700, 46, 0)
	w.controls[controlProductDetails] = w.addControl(controlProductDetails, "STATIC", "", static, 60, 264, 820, 62, 0)
	w.controls[controlCapabilities] = w.addControl(controlCapabilities, "STATIC", "", static, 60, 220, 880, 38, 0)
	w.controls[controlCapabilityInstall] = w.addControl(controlCapabilityInstall, "STATIC", "", static, 120, 268, 950, 42, 0)
	w.controls[controlCapabilityLink] = w.addControl(controlCapabilityLink, "STATIC", "", static, 120, 312, 950, 42, 0)
	w.controls[controlCapabilityData] = w.addControl(controlCapabilityData, "STATIC", "", static, 120, 356, 950, 42, 0)

	w.controls[controlActionGroup] = w.addControl(controlActionGroup, "STATIC", "Choose an action", static, 60, 430, 1280, 40, 0)
	w.controls[controlActionInstall] = w.addControl(controlActionInstall, "BUTTON", "Install or update", button|bsOwnerDraw|wsGroup, 60, 490, 1280, 120, 0)
	w.controls[controlActionUninstall] = w.addControl(controlActionUninstall, "BUTTON", "Uninstall", button|bsOwnerDraw, 60, 626, 1280, 120, 0)
	w.controls[controlActionPath] = w.addControl(controlActionPath, "STATIC", "", static|ssLeftNoWrap, 60, 840, 900, 42, 0)

	w.controls[controlInstallGroup] = w.addControl(controlInstallGroup, "STATIC", "Destination folder", static, 60, 250, 1280, 40, 0)
	w.controls[controlInstallEdit] = w.addControl(controlInstallEdit, "EDIT", "", child|wsTabStop|esAutoHScroll, 80, 310, 1020, 46, 0)
	w.controls[controlBrowse] = w.addControl(controlBrowse, "BUTTON", "Browse...", button|bsOwnerDraw, 1120, 310, 220, 46, 0)
	w.controls[controlInstallHint] = w.addControl(controlInstallHint, "STATIC", "", static, 80, 382, 1180, 60, 0)

	// The options surface is painted by the parent so it cannot obscure sibling
	// controls when Windows repaints the page or switches focus.
	w.controls[controlUninstallGroup] = w.addControl(controlUninstallGroup, "STATIC", "Uninstall options", static, 88, 330, 1190, 40, 0)
	w.controls[controlUninstallPathLabel] = w.addControl(controlUninstallPathLabel, "STATIC", "Installation folder", static, 88, 380, 1190, 34, 0)
	w.controls[controlUninstallPath] = w.addControl(controlUninstallPath, "EDIT", "", child|esReadOnly|esAutoHScroll, 90, 418, 1220, 58, 0)
	w.controls[controlMetadata] = w.addControl(controlMetadata, "BUTTON", "Remove metadata", button|bsOwnerDraw, 88, 510, 1220, 54, 0)
	w.controls[controlUninstallWarning] = w.addControl(controlUninstallWarning, "STATIC", "", static, 136, 568, 1200, 60, 0)
	w.controls[controlUninstallImpact] = w.addControl(controlUninstallImpact, "STATIC", "", static, 165, 684, 1120, 52, 0)

	w.controls[controlProgressStatus] = w.addControl(controlProgressStatus, "STATIC", "", static, 60, 300, 1280, 70, 0)
	w.controls[controlProgressBar] = w.addControl(controlProgressBar, "STATIC", "Operation in progress", child|ssOwnerDraw, 60, 410, 1280, 34, 0)
	w.controls[controlProgressCount] = w.addControl(controlProgressCount, "STATIC", "", static, 1130, 344, 210, 40, 0)
	w.controls[controlProgressSteps] = w.addControl(controlProgressSteps, "STATIC", "", child|ssOwnerDraw, 60, 350, 1280, 300, 0)
	w.controls[controlProgressLogTitle] = w.addControl(controlProgressLogTitle, "STATIC", "Operation log", static, 60, 662, 1280, 38, 0)
	// Native owner-drawn list keeps rows accessible, selectable and scrollable.
	// LBS_OWNERDRAWFIXED | LBS_HASSTRINGS, vertical/horizontal scroll. Keep the
	// default integral row height so scrolling never exposes half a line.
	w.controls[controlProgressLog] = w.addControl(controlProgressLog, "LISTBOX", "Operation log", child|wsTabStop|0x20|0x40|0x00200000|0x00100000, 60, 706, 1280, 154, 0)
	// Keep native scrolling and keyboard behavior while matching the dark log.
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlProgressLog], uintptr(unsafe.Pointer(mustInstallerUTF16("DarkMode_Explorer"))), 0)
	w.controls[controlResult] = w.addControl(controlResult, "STATIC", "", static, 60, 270, 1280, 330, 0)
	w.controls[controlResultDetailsToggle] = w.addControl(controlResultDetailsToggle, "BUTTON", "Show details", button|bsOwnerDraw, 60, 596, 300, 52, 0)
	// Read-only, wrapping diagnostics: one vertical scrollbar, no horizontal
	// scrolling required to read an error or a long filesystem path.
	w.controls[controlResultDetails] = w.addControl(controlResultDetails, "EDIT", "", child|wsTabStop|esReadOnly|0x4|0x40|0x00200000, 74, 668, 1252, 182, 0)
	windows.NewLazySystemDLL("uxtheme.dll").NewProc("SetWindowTheme").Call(w.controls[controlResultDetails], uintptr(unsafe.Pointer(mustInstallerUTF16("DarkMode_Explorer"))), 0)
	installerSendMessage.Call(w.controls[controlResultDetails], 0x00c5, 256*1024, 0) // EM_SETLIMITTEXT
	// Give the compact action row an even rhythm and enough visual weight for
	// the destructive primary action without changing its command routing.
	w.controls[controlBack] = w.addControl(controlBack, "BUTTON", "Back", button|bsOwnerDraw, 780, 886, 185, 72, 0)
	w.controls[controlNext] = w.addControl(controlNext, "BUTTON", "Next  ›", button|bsOwnerDraw, 985, 886, 175, 72, 0)
	w.controls[controlCancel] = w.addControl(controlCancel, "BUTTON", "Cancel", button|bsOwnerDraw, 1175, 886, 165, 72, 0)

	font, _, _ := installerGetStockObject.Call(defaultGuiFont)
	for _, control := range w.controls {
		installerSendMessage.Call(control, wmSetFont, font, 1)
	}
	// Keep the compact window footprint while giving the maintenance copy the
	// readable hierarchy of the reference composition.
	w.titleFont = createInstallerFont(w.scaled(-64), fontWeightBold)
	w.bodyFont = createInstallerFont(w.scaled(-32), fontWeightNormal)
	w.detailFont = createInstallerFont(w.scaled(-28), fontWeightNormal)
	w.productFont = createInstallerFont(w.scaled(-38), fontWeightBold)
	w.buttonFont = createInstallerFont(w.scaled(-29), fontWeightNormal)
	w.cardTitleFont = createInstallerFont(w.scaled(-32), fontWeightBold)
	w.cardDetailFont = createInstallerFont(w.scaled(-27), fontWeightNormal)
	w.iconFont = createInstallerFont(w.scaled(-60), fontWeightBold)
	w.wordmarkFont = createInstallerFont(w.scaled(-16), fontWeightNormal)
	w.logFont = createInstallerNamedFont(w.scaled(-27), fontWeightNormal, "Consolas")
	setInstallerFont(w.controls[controlTitle], w.titleFont)
	setInstallerFont(w.controls[controlDescription], w.bodyFont)
	setInstallerFont(w.controls[controlProductName], w.productFont)
	setInstallerFont(w.controls[controlProductDetails], w.detailFont)
	setInstallerFont(w.controls[controlCapabilities], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityInstall], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityLink], w.bodyFont)
	setInstallerFont(w.controls[controlCapabilityData], w.bodyFont)
	setInstallerFont(w.controls[controlActionGroup], w.bodyFont)
	setInstallerFont(w.controls[controlActionPath], w.detailFont)
	setInstallerFont(w.controls[controlInstallGroup], w.detailFont)
	setInstallerFont(w.controls[controlUninstallGroup], w.cardTitleFont)
	setInstallerFont(w.controls[controlUninstallPathLabel], w.detailFont)
	setInstallerFont(w.controls[controlUninstallPath], w.bodyFont)
	setInstallerFont(w.controls[controlMetadata], w.bodyFont)
	setInstallerFont(w.controls[controlUninstallWarning], w.detailFont)
	setInstallerFont(w.controls[controlUninstallImpact], w.buttonFont)
	setInstallerFont(w.controls[controlInstallEdit], w.bodyFont)
	setInstallerFont(w.controls[controlInstallHint], w.detailFont)
	setInstallerFont(w.controls[controlBrowse], w.buttonFont)
	setInstallerFont(w.controls[controlProgressStatus], w.bodyFont)
	setInstallerFont(w.controls[controlProgressCount], w.detailFont)
	setInstallerFont(w.controls[controlProgressLogTitle], w.cardTitleFont)
	setInstallerFont(w.controls[controlProgressLog], w.logFont)
	setInstallerFont(w.controls[controlResult], w.bodyFont)
	setInstallerFont(w.controls[controlResultDetailsToggle], w.detailFont)
	setInstallerFont(w.controls[controlResultDetails], w.logFont)
	installerSendMessage.Call(w.controls[controlProgressLog], 0x01a0, 0, uintptr(w.scaled(34))) // LB_SETITEMHEIGHT
	setInstallerFont(w.controls[controlBack], w.buttonFont)
	setInstallerFont(w.controls[controlNext], w.buttonFont)
	setInstallerFont(w.controls[controlCancel], w.buttonFont)
	if !w.installed {
		setInstallerEnabled(w.controls[controlActionUninstall], false)
	}
}

func (w *installerWizard) addControl(id int, className, title string, style uint32, x, y, width, height int32, exStyle uint32) uintptr {
	return createInstallerWindow(className, title, exStyle, style,
		w.scaled(x), w.scaled(y), w.scaled(width), w.scaled(height), w.hwnd, uintptr(id), w.instance)
}

func createInstallerFont(height int32, weight int32) uintptr {
	return createInstallerNamedFont(height, weight, "Segoe UI")
}

func createInstallerNamedFont(height int32, weight int32, name string) uintptr {
	face, err := windows.UTF16PtrFromString(name)
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
	w.resultDetailsOpen = false
	if page != installerPageProgress {
		installerUser32.NewProc("KillTimer").Call(w.hwnd, 1)
		w.progressTimer = 0
		w.progressComplete = time.Time{}
	}
	w.setPageIcon(page)
	for _, control := range w.controls {
		setInstallerVisible(control, false)
	}
	w.layoutPage(page)
	w.rebuildBackdrop()
	setInstallerVisible(w.controls[controlTitle], true)
	setInstallerVisible(w.controls[controlDescription], true)

	title := "Synesis Setup"
	description := "Choose an action to continue."
	switch page {
	case installerPageAction:
		title = "Manage installation"
		description = "Choose what you want to do with the current installation."
		setInstallerVisible(w.controls[controlCapabilities], true)
		setInstallerVisible(w.controls[controlCapabilityInstall], true)
		setInstallerVisible(w.controls[controlCapabilityLink], true)
		setInstallerVisible(w.controls[controlCapabilityData], true)
		setInstallerVisible(w.controls[controlActionGroup], true)
		setInstallerVisible(w.controls[controlActionInstall], true)
		setInstallerVisible(w.controls[controlActionUninstall], true)
		setInstallerVisible(w.controls[controlActionPath], true)
		setInstallerText(w.controls[controlProductName], "")
		setInstallerText(w.controls[controlProductDetails], "")
		setInstallerText(w.controls[controlCapabilities], "Setup includes")
		setInstallerText(w.controls[controlCapabilityInstall], "Local runtime")
		setInstallerText(w.controls[controlCapabilityLink], "synesis:// link handler")
		setInstallerText(w.controls[controlCapabilityData], "Existing project and workspace data is kept")
		setInstallerText(w.controls[controlActionPath], "Installed at "+w.installRoot)
	case installerPageInstall:
		title = "Choose Install Location"
		description = "Choose the folder where Synesis will be installed.\r\nThis is where the application files will be placed."
		setInstallerVisible(w.controls[controlInstallGroup], true)
		setInstallerVisible(w.controls[controlInstallEdit], true)
		setInstallerVisible(w.controls[controlBrowse], true)
		setInstallerVisible(w.controls[controlInstallHint], true)
		setInstallerText(w.controls[controlInstallGroup], "Destination folder")
		if w.installDraft == "" {
			w.installDraft = w.installRoot
		}
		setInstallerText(w.controls[controlInstallEdit], w.installDraft)
		setInstallerText(w.controls[controlInstallHint], "Projects, repositories, workspaces, and provider configuration are not installed or removed here.")
	case installerPageUninstall:
		title = "Uninstall"
		description = "Remove the application from this computer.\r\nYour projects, repositories, and workspace data will be preserved."
		setInstallerVisible(w.controls[controlUninstallGroup], true)
		setInstallerVisible(w.controls[controlUninstallPathLabel], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerVisible(w.controls[controlMetadata], true)
		setInstallerVisible(w.controls[controlUninstallWarning], true)
		setInstallerVisible(w.controls[controlUninstallImpact], true)
		setInstallerText(w.controls[controlUninstallPath], w.installRoot)
		setInstallerText(w.controls[controlMetadata], "Remove metadata")
		setInstallerText(w.controls[controlUninstallWarning], "Removes local identity and installer state.\r\nProject, repository, and workspace data stay intact.")
		setInstallerText(w.controls[controlUninstallImpact], "Application files and the synesis:// handler will be removed.")
	case installerPageProgress:
		w.progressEpoch = time.Now()
		w.progressMotion = installerProgressMotion{}
		w.progressComplete = time.Time{}
		if w.action == installerActionUninstall {
			title = "Uninstalling"
		} else {
			title = "Installing"
		}
		description = "Setting up the local runtime and shell integration. This may take a minute."
		if w.action == installerActionUninstall {
			description = "Removing the application and shell integration. Your project data is preserved."
		}
		setInstallerVisible(w.controls[controlProgressStatus], true)
		setInstallerVisible(w.controls[controlProgressBar], true)
		setInstallerVisible(w.controls[controlProgressCount], true)
		setInstallerVisible(w.controls[controlProgressSteps], true)
		setInstallerVisible(w.controls[controlProgressLogTitle], true)
		setInstallerVisible(w.controls[controlProgressLog], true)
		if w.action == installerActionUninstall {
			setInstallerText(w.controls[controlProgressLogTitle], "Uninstallation log")
		} else {
			setInstallerText(w.controls[controlProgressLogTitle], "Installation log")
		}
		setInstallerText(w.controls[controlProgressStatus], "Preparing the operation...")
		w.refreshProgress()
		w.progressTimer, _, _ = installerUser32.NewProc("SetTimer").Call(w.hwnd, 1, 16, 0)
	case installerPageResult:
		title, description = "Installed", "Synesis is ready to use."
		if w.action == installerActionUninstall {
			title, description = "Uninstalled", "The application has been removed."
		}
		if w.result.err != nil {
			title = "Installation incomplete"
			if w.action == installerActionUninstall {
				title = "Uninstall incomplete"
			}
			description = w.resultFailureDescription()
		} else if w.result.warning != "" {
			description = "Application installed. Link registration needs attention."
		}
		setInstallerVisible(w.controls[controlResult], true)
		setInstallerText(w.controls[controlResult], w.resultText())
		setInstallerVisible(w.controls[controlUninstallPathLabel], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerText(w.controls[controlUninstallPath], w.installRoot)
		details := w.resultDetailsText()
		setInstallerText(w.controls[controlResultDetails], details)
		setInstallerText(w.controls[controlResultDetailsToggle], "Show details")
		setInstallerVisible(w.controls[controlResultDetailsToggle], details != "")
	}
	setInstallerText(w.controls[controlTitle], title)
	setInstallerText(w.controls[controlDescription], description)

	showBack := page == installerPageInstall || page == installerPageUninstall || page == installerPageProgress
	showNext := page == installerPageAction || page == installerPageInstall || page == installerPageUninstall || page == installerPageProgress
	setInstallerVisible(w.controls[controlBack], showBack)
	setInstallerVisible(w.controls[controlNext], showNext)
	setInstallerVisible(w.controls[controlCancel], true)
	setInstallerEnabled(w.controls[controlBack], !w.busy)
	setInstallerEnabled(w.controls[controlNext], !w.busy)
	setInstallerEnabled(w.controls[controlCancel], !w.busy)
	if page == installerPageResult {
		setInstallerText(w.controls[controlCancel], "Close")
	} else {
		setInstallerText(w.controls[controlCancel], "Cancel")
	}
	if showNext {
		w.updateActionLabel()
	}
	if page == installerPageAction {
		installerSetFocus.Call(w.controls[controlActionInstall])
	} else if page == installerPageInstall {
		installerSetFocus.Call(w.controls[controlInstallEdit])
	}
	// Paint the new parent surface before refreshing its child controls. The
	// posted second pass runs after the current navigation message has unwound;
	// this keeps the branded chrome and unchanged footer controls from retaining
	// stale pixels across page transitions.
	w.refreshPage()
	installerPostMessage.Call(w.hwnd, installerMessageRefresh, 0, 0)
}

func (w *installerWizard) refreshPage() {
	// Controls move between page layouts. Repaint their former bounds as well as
	// their new bounds, including the entire right-hand footer and brand area.
	installerInvalidateRect.Call(w.hwnd, 0, 1)
	installerUpdateWindow.Call(w.hwnd)
	for _, control := range w.controls {
		installerRedrawWindow.Call(control, 0, 0, rdwInvalidate|rdwErase|rdwUpdateNow)
	}
}

func (w *installerWizard) refreshEntireWindow() {
	installerRedrawWindow.Call(w.hwnd, 0, 0, rdwInvalidate|rdwErase|rdwAllChildren|rdwUpdateNow|rdwFrame)
	for _, control := range w.controls {
		installerRedrawWindow.Call(control, 0, 0, rdwInvalidate|rdwErase|rdwUpdateNow)
	}
}

// layoutPage confines shared controls to the space actually available on each
// page while keeping the compact maintenance layout aligned to the footer.
func (w *installerWizard) layoutPage(page installerPage) {
	move := func(id int, x, y, width, height int32) {
		installerMoveWindow.Call(w.controls[id], uintptr(w.scaled(x)), uintptr(w.scaled(y)),
			uintptr(w.scaled(width)), uintptr(w.scaled(height)), 0)
	}
	headerWidth := int32(1040)
	move(controlTitle, 60, 48, headerWidth, 84)
	descriptionHeight := int32(60)
	move(controlDescription, 60, 132, headerWidth, descriptionHeight)
	move(controlNext, 980, 894, 175, 64)
	move(controlCancel, 1175, 894, 165, 64)
	move(controlBack, 785, 894, 175, 64)
	if page == installerPageInstall {
		move(controlDescription, 60, 132, headerWidth, 96)
		move(controlInstallGroup, 60, 330, 1280, 42)
		move(controlInstallEdit, 74, 405, 1008, 54)
		move(controlBrowse, 1120, 390, 220, 82)
		move(controlInstallHint, 60, 518, 1260, 100)
		move(controlBack, 785, 825, 175, 72)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
	if page == installerPageProgress || page == installerPageResult {
		move(controlDescription, 60, 132, headerWidth, 90)
		move(controlProgressStatus, 60, 280, 1280, 40)
		move(controlProgressBar, 60, 328, 1050, 36)
		move(controlProgressCount, 1140, 324, 200, 44)
		move(controlProgressSteps, 60, 384, 1280, 272)
		move(controlProgressLogTitle, 60, 674, 1280, 38)
		move(controlProgressLog, 60, 718, 1280, 145)
		move(controlResult, 60, 300, 1250, 310)
	}
	if page == installerPageResult {
		move(controlResult, 88, 320, 1224, 96)
		move(controlUninstallPathLabel, 88, 432, 1224, 38)
		move(controlUninstallPath, 102, 494, 1196, 40)
		move(controlResultDetailsToggle, 60, 596, 300, 52)
		move(controlResultDetails, 74, 668, 1252, 182)
	}
	if page == installerPageAction {
		move(controlCapabilities, 60, 220, 880, 38)
		move(controlCapabilityInstall, 120, 268, 950, 42)
		move(controlCapabilityLink, 120, 312, 950, 42)
		move(controlCapabilityData, 120, 356, 950, 42)
		move(controlActionGroup, 60, 430, 1280, 40)
		move(controlActionInstall, 60, 490, 1280, 120)
		move(controlActionUninstall, 60, 626, 1280, 120)
		move(controlActionPath, 60, 840, 900, 42)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
	if page == installerPageUninstall {
		move(controlDescription, 60, 132, headerWidth, 96)
		move(controlUninstallGroup, 88, 328, 1190, 44)
		move(controlUninstallPathLabel, 88, 384, 1190, 40)
		move(controlUninstallPath, 104, 454, 1192, 42)
		move(controlMetadata, 88, 528, 1220, 48)
		move(controlUninstallWarning, 136, 584, 1172, 80)
		move(controlUninstallImpact, 164, 721, 1144, 42)
		move(controlBack, 785, 825, 175, 72)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
}

func (w *installerWizard) resultText() string {
	if w.result.err != nil {
		return "Some changes may already have been applied."
	}
	if w.action == installerActionUninstall {
		metadata := "Installer-owned metadata was preserved."
		if w.metadata {
			metadata = "Installer-owned metadata was removed."
		}
		return metadata +
			"\r\nYour projects, repositories, and workspace data were left untouched."
	}
	protocol := "The synesis:// link handler is registered."
	if w.result.warning != "" {
		protocol = "The synesis:// link handler could not be registered."
	}
	return "Runtime and shell integration are ready.\r\n" + protocol
}

func (w *installerWizard) resultFailureDescription() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	stage := ""
	for _, step := range w.steps {
		if step.status == "failed" {
			stage = step.id
			break
		}
	}
	if w.action == installerActionUninstall {
		switch stage {
		case "runtime":
			return "Application files could not be removed."
		case "shell":
			return "Shell integration could not be fully removed."
		case "metadata":
			return "Installer metadata could not be removed."
		case "cleanup":
			return "Some installer files could not be cleaned up."
		}
		return "Synesis could not be fully removed."
	}
	switch stage {
	case "verify":
		return "The local installation bundle could not be verified."
	case "runtime":
		return "Application files could not be installed."
	case "shell":
		return "Shell integration could not be updated."
	}
	return "Setup could not finish the installation."
}

func (w *installerWizard) resultDetailsText() string {
	w.mu.Lock()
	entries := append([]installerLogEntry(nil), w.logEntries...)
	w.mu.Unlock()
	var sections []string
	if w.result.err != nil {
		message := []rune(strings.Join(strings.Fields(w.result.err.Error()), " "))
		if len(message) > 4096 {
			message = append(message[:4093], '.', '.', '.')
		}
		sections = append(sections, "Error: "+string(message))
	} else if w.result.warning != "" {
		sections = append(sections, w.result.warning)
	}
	var history []string
	for _, entry := range entries {
		history = append(history, "["+entry.status+"] "+entry.detail)
	}
	if len(history) > 0 {
		sections = append(sections, strings.Join(history, "\r\n"))
	}
	return strings.Join(sections, "\r\n\r\n")
}

func (w *installerWizard) toggleResultDetails() {
	if w.page != installerPageResult || w.busy {
		return
	}
	w.resultDetailsOpen = !w.resultDetailsOpen
	label := "Show details"
	if w.resultDetailsOpen {
		label = "Hide details"
	}
	setInstallerText(w.controls[controlResultDetailsToggle], label)
	setInstallerVisible(w.controls[controlResultDetails], w.resultDetailsOpen)
	if w.resultDetailsOpen {
		// Start with the error summary, even after earlier diagnostic scrolling.
		installerSendMessage.Call(w.controls[controlResultDetails], 0x00b1, 0, 0) // EM_SETSEL
		installerSendMessage.Call(w.controls[controlResultDetails], 0x00b7, 0, 0) // EM_SCROLLCARET
	}
	w.rebuildBackdrop()
	w.refreshPage()
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
	if notification != bnClicked || w.busy {
		return
	}
	switch int(id) {
	case controlResultDetailsToggle:
		w.toggleResultDetails()
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
	w.updateActionLabel()
	for _, controlID := range []int{controlActionInstall, controlActionUninstall} {
		if control := w.controls[controlID]; control != 0 {
			installerInvalidateRect.Call(control, 0, 1)
		}
	}
}

func (w *installerWizard) updateActionLabel() {
	label := "Install"
	if w.page == installerPageUninstall || (w.page == installerPageAction && w.action == installerActionUninstall) {
		label = "Uninstall"
	}
	if w.page == installerPageProgress {
		label = "Next"
	}
	setInstallerText(w.controls[controlNext], label)
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
	if w.backdrop != nil {
		w.backdrop.draw(hdc, 0, 0)
	} else {
		installerFillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), w.darkBrush)
	}
	brandRight := client.right - w.scaled(60)
	brandLeft := brandRight - w.scaled(190)
	brandTop := w.scaled(40)
	brandBottom := brandTop + w.scaled(190)
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
	if w.page == installerPageUninstall {
		w.drawUninstallPanel(hdc)
	}

	linePen, _, _ := installerCreatePen.Call(0, scaledPenWidth(w.scale), installerRGB(35, 43, 54))
	oldPen, _, _ := installerSelectObject.Call(hdc, linePen)
	headerDividerY := int32(192)
	if w.page != installerPageAction {
		headerDividerY = 250
	}
	installerMoveToEx.Call(hdc, uintptr(w.scaled(60)), uintptr(w.scaled(headerDividerY)), 0)
	installerLineTo.Call(hdc, uintptr(w.scaled(1030)), uintptr(w.scaled(headerDividerY)))
	if w.page != installerPageAction {
		footer := int32(800)
		if w.page == installerPageProgress || w.page == installerPageResult {
			footer = 878
		}
		installerMoveToEx.Call(hdc, uintptr(w.scaled(60)), uintptr(w.scaled(footer)), 0)
		installerLineTo.Call(hdc, uintptr(w.scaled(1340)), uintptr(w.scaled(footer)))
	}
	installerSelectObject.Call(hdc, oldPen)
	installerDeleteObject.Call(linePen)

	for _, capability := range []struct {
		kind string
		y    int32
	}{
		{kind: "install", y: 289},
		{kind: "link", y: 333},
		{kind: "data", y: 377},
	} {
		drawInstallerCapabilityIcon(hdc, w, capability.kind, w.scaled(84), w.scaled(capability.y), w.scaled(28))
	}
}

func (w *installerWizard) drawUninstallPanel(device uintptr) {
	w.drawSmoothIcon(device, w.scaled(112), w.scaled(741), w.scaled(22), "alert", installerRGB(255, 190, 65))
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
	w.paintControlBackground(device, control)

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
	enabled, _, _ := installerUser32.NewProc("IsWindowEnabled").Call(control)
	w.paintControlBackground(device, control)
	if controlID == controlResultDetailsToggle {
		drawInstallerText(device, w.detailFont, getInstallerText(control), rect, installerRGB(113, 190, 249))
		if focus, _, _ := installerGetFocus.Call(); focus == control {
			installerFrameRect.Call(device, uintptr(unsafe.Pointer(&rect)), w.surfaceBrush)
		}
		return
	}
	art := "secondary"
	if primary {
		art = "primary"
	}
	w.drawArt(device, art, 0, 0, rect.right, rect.bottom)
	textRed, textGreen, textBlue := byte(248), byte(250), byte(255)
	if enabled == 0 {
		drawInstallerSurface(device, rect, installerRGB(22, 28, 36), installerRGB(42, 52, 64), w.scaled(10), w.scale)
		textRed, textGreen, textBlue = 95, 108, 123
	}

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
	w.paintControlBackground(device, control)
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
	kind, color := "radio-unselected", installerRGB(128, 145, 168)
	if selected {
		kind, color = "radio-selected", installerRGB(115, 198, 255)
	}
	if !enabled {
		kind, color = "radio-disabled", installerRGB(76, 90, 110)
	}
	w.drawSmoothIcon(device, w.scaled(48), rect.bottom/2, w.scaled(18), kind, color)

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
	if id == controlUninstallPath || id == controlInstallEdit || id == controlResultDetails {
		return w.colorEdit(device)
	}
	switch id {
	case controlProductName:
		red, green, blue = 248, 250, 252
	case controlDescription:
		red, green, blue = 174, 186, 207
	case controlProductDetails, controlActionPath, controlInstallHint, controlUninstallWarning,
		controlCapabilityInstall, controlCapabilityLink, controlCapabilityData:
		red, green, blue = 190, 201, 219
	case controlUninstallImpact:
		red, green, blue = 238, 241, 247
	case controlTitle:
		red, green, blue = 248, 250, 252
	}
	installerSetTextColor.Call(device, installerRGB(red, green, blue))
	if w.backdropBrush != 0 {
		origin := w.controlOrigin(hwnd)
		installerGDI32.NewProc("SetBrushOrgEx").Call(device, uintptr(-origin.x), uintptr(-origin.y), 0)
		return w.backdropBrush
	}
	return w.darkBrush
}

func (w *installerWizard) colorEdit(device uintptr) uintptr {
	// The edit control uses the DC background for text cells and the returned
	// brush for unused space. Both must match, including while typing/focused.
	installerSetBkMode.Call(device, opaque)
	installerSetBkColor.Call(device, installerRGB(27, 32, 43))
	installerSetTextColor.Call(device, installerRGB(241, 245, 249))
	return w.surfaceBrush
}

func (w *installerWizard) disposeFonts() {
	for _, font := range []uintptr{w.titleFont, w.bodyFont, w.detailFont, w.productFont, w.buttonFont, w.cardTitleFont, w.cardDetailFont, w.iconFont, w.wordmarkFont, w.logFont} {
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
	if w.backdropBrush != 0 {
		installerDeleteObject.Call(w.backdropBrush)
		w.backdropBrush = 0
	}
	w.backdrop.dispose()
	w.backdrop = nil
	w.progressRaster.dispose()
	w.progressRaster = nil
	for _, icon := range w.smoothIcons {
		icon.dispose()
	}
	w.smoothIcons = nil
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
	if w.warningBrush != 0 {
		installerDeleteObject.Call(w.warningBrush)
		w.warningBrush = 0
	}
}

func (w *installerWizard) goBack() {
	if w.busy {
		return
	}
	switch w.page {
	case installerPageInstall, installerPageUninstall:
		if w.page == installerPageInstall {
			// Preserve an edited path even when returning before validation.
			w.installDraft = getInstallerText(w.controls[controlInstallEdit])
		}
		w.showPage(installerPageAction)
	}
}

func (w *installerWizard) goNext() {
	if w.busy {
		return
	}
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
		w.startOperation()
	case installerPageUninstall:
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
	w.mu.Lock()
	w.steps = installerOperationSteps(w.action, w.metadata)
	w.logEntries = nil
	w.operationStatus = "Preparing the operation..."
	w.result = installerOperationResult{}
	w.mu.Unlock()
	w.logRendered = 0
	installerSendMessage.Call(w.controls[controlProgressLog], 0x0184, 0, 0) // LB_RESETCONTENT
	w.showPage(installerPageProgress)
	root := w.installRoot
	action := w.action
	metadata := w.metadata
	bundle := w.bundle
	execute := w.executeOperation
	if execute == nil {
		execute = executeInstallerOperation
	}
	go func() {
		result := execute(root, bundle, action, metadata, w.reportProgress)
		if result.err != nil {
			w.reportProgress("", "failed", result.err.Error())
		}
		w.mu.Lock()
		w.result = result
		w.mu.Unlock()
		installerPostMessage.Call(w.hwnd, installerMessageDone, 0, 0)
	}()
}

func installerStatusColor(status string) uintptr {
	switch status {
	case "done":
		return installerRGB(91, 212, 150)
	case "working":
		return installerRGB(81, 178, 250)
	case "warning":
		return installerRGB(239, 189, 93)
	case "failed":
		return installerRGB(245, 122, 122)
	default:
		return installerRGB(145, 160, 180)
	}
}

func drawInstallerText(device, font uintptr, text string, rect installerRect, color uintptr) {
	previous, _, _ := installerSelectObject.Call(device, font)
	defer installerSelectObject.Call(device, previous)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, color)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), dtSingleLine|dtVCenter|dtEndEllipsis)
}

func (w *installerWizard) drawProgressSteps(device uintptr) {
	w.mu.Lock()
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	var rect installerRect
	installerGetClientRect.Call(w.controls[controlProgressSteps], uintptr(unsafe.Pointer(&rect)))
	w.paintControlBackground(device, w.controls[controlProgressSteps])
	for i, step := range steps {
		y := w.scaled(int32(i) * 68)
		w.drawStepMarker(device, w.scaled(21), y+w.scaled(19), step.status, i < len(steps)-1)
		font := w.bodyFont
		if step.status == "working" {
			font = w.cardTitleFont
		}
		drawInstallerText(device, font, step.title, installerRect{left: w.scaled(68), top: y, right: rect.right, bottom: y + w.scaled(36)}, installerRGB(235, 240, 247))
		detail := step.detail
		if detail == "" {
			detail = "Pending"
		}
		drawInstallerText(device, w.detailFont, detail, installerRect{left: w.scaled(68), top: y + w.scaled(35), right: rect.right, bottom: y + w.scaled(66)}, installerRGB(174, 190, 211))
	}
}

func (w *installerWizard) drawStepMarker(device uintptr, x, y int32, status string, connected bool) {
	r := w.scaled(16)
	if connected {
		pen, _, _ := installerCreatePen.Call(0, 1, installerRGB(49, 65, 84))
		previous, _, _ := installerSelectObject.Call(device, pen)
		installerMoveToEx.Call(device, uintptr(x), uintptr(y+r+w.scaled(4)), 0)
		installerLineTo.Call(device, uintptr(x), uintptr(y+w.scaled(68)-r-w.scaled(4)))
		installerSelectObject.Call(device, previous)
		installerDeleteObject.Call(pen)
	}
	w.drawSmoothIcon(device, x, y, r, status, installerStatusColor(status))
}

// Count only completed stages; no estimate of bytes, elapsed time or partial
// work is implied by the bar. Warnings and failed steps are not successes.
func completedInstallerSteps(steps []installerStep) int {
	completed := 0
	for _, step := range steps {
		if step.status == "done" {
			completed++
		}
	}
	return completed
}

func (w *installerWizard) drawProgressBar(device uintptr) {
	var rect installerRect
	installerGetClientRect.Call(w.controls[controlProgressBar], uintptr(unsafe.Pointer(&rect)))
	if w.progressRaster == nil || w.progressRaster.width != rect.right || w.progressRaster.height != rect.bottom {
		w.progressRaster.dispose()
		w.progressRaster = newInstallerRaster(rect.right, rect.bottom)
	}
	if w.progressRaster == nil {
		return
	}
	now := time.Now()
	phase := math.Mod(now.Sub(w.progressEpoch).Seconds(), 2.2) / 2.2
	origin := w.controlOrigin(w.controls[controlProgressBar])
	paintInstallerProgressOnBackground(w.progressRaster.pixels, rect.right, rect.bottom, w.progressMotion.value(now), phase, w.backdrop, origin.x, origin.y)
	w.progressRaster.draw(device, 0, 0)
}

func (w *installerWizard) drawProgressLog(item installerDrawItem) {
	w.mu.Lock()
	if int(item.itemID) >= len(w.logEntries) {
		w.mu.Unlock()
		return
	}
	entry := w.logEntries[item.itemID]
	w.mu.Unlock()
	installerFillRect.Call(item.device, uintptr(unsafe.Pointer(&item.rect)), w.surfaceBrush)
	var client installerRect
	installerGetClientRect.Call(item.window, uintptr(unsafe.Pointer(&client)))
	// Owner-drawn lists can still request a partial final item after a resize
	// or scrollbar change. Keep that sliver blank instead of cutting a line.
	if item.rect.top < client.top || item.rect.bottom > client.bottom {
		return
	}
	if item.state&1 != 0 { // ODS_SELECTED: keep keyboard/selection feedback visible.
		installerFrameRect.Call(item.device, uintptr(unsafe.Pointer(&item.rect)), w.cardBrush)
	}
	rect := item.rect
	rect.left += w.scaled(14)
	previous, _, _ := installerSelectObject.Call(item.device, w.logFont)
	defer installerSelectObject.Call(item.device, previous)
	installerSetBkMode.Call(item.device, transparent)
	// Let the native list clip/scroll the full text instead of baking an
	// ellipsis into a row whose remaining diagnostics should stay reachable.
	for i, text := range []string{"[" + entry.status + "] ", entry.detail} {
		color := installerRGB(226, 232, 240)
		if i == 0 {
			color = installerStatusColor(entry.status)
		}
		installerSetTextColor.Call(item.device, color)
		value, _ := windows.UTF16FromString(text)
		var size installerPoint
		installerGDI32.NewProc("GetTextExtentPoint32W").Call(item.device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		rect.right = rect.left + size.x
		installerDrawText.Call(item.device, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&rect)), dtSingleLine|dtVCenter)
		rect.left = rect.right
	}
}

func executeInstallerOperation(root, bundle string, action installerAction, metadata bool, observer operationProgress) installerOperationResult {
	result := installerOperationResult{}
	paths, err := installationPaths(root)
	if err != nil {
		result.err = err
		return result
	}
	if action == installerActionUninstall {
		args := []string{"--install-dir", paths.root}
		if metadata {
			args = append(args, "--remove-metadata")
		}
		result.err = runUninstall(args, observer)
		return result
	}
	args := []string{"--bundle", bundle, "--install-dir", paths.root}
	if fileExists(paths.current) {
		result.err = runRepair(args, observer)
	} else {
		result.err = runInstall("install", args, observer)
	}
	if result.err == nil {
		reportOperation([]operationProgress{observer}, "protocol", "working", "Registering the owned synesis:// link handler")
		if registrationErr := registerProtocol(paths); registrationErr != nil {
			result.warning = "Windows could not register the synesis:// link handler: " + registrationErr.Error()
			reportOperation([]operationProgress{observer}, "protocol", "warning", result.warning)
		} else {
			reportOperation([]operationProgress{observer}, "protocol", "done", "The synesis:// link handler is registered")
		}
	}
	return result
}

// reportProgress runs on the worker; the posted message is the only UI boundary.
func (w *installerWizard) reportProgress(stage, status, detail string) {
	// Bound retained diagnostics, including untrusted filesystem/error text.
	detail = strings.Map(func(r rune) rune {
		if r < 32 {
			return ' '
		}
		return r
	}, detail)
	if len([]rune(detail)) > 512 {
		detail = string([]rune(detail)[:509]) + "..."
	}
	w.mu.Lock()
	for i := range w.steps {
		if w.steps[i].id == stage || (stage == "" && status == "failed" && w.steps[i].status == "working") {
			w.steps[i].status, w.steps[i].detail = status, detail
		}
	}
	w.operationStatus = detail
	w.logEntries = append(w.logEntries, installerLogEntry{status: status, detail: detail})
	if len(w.logEntries) > 200 {
		w.logEntries = append([]installerLogEntry(nil), w.logEntries[len(w.logEntries)-200:]...)
	}
	w.mu.Unlock()
	installerPostMessage.Call(w.hwnd, installerMessageProgress, 0, 0)
}

func (w *installerWizard) refreshProgress() {
	w.mu.Lock()
	status := w.operationStatus
	entries := append([]installerLogEntry(nil), w.logEntries...)
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	setInstallerText(w.controls[controlProgressStatus], status)
	count := fmt.Sprintf("%d / %d steps", completedInstallerSteps(steps), len(steps))
	if len(steps) > 0 {
		w.progressMotion.setTarget(float64(completedInstallerSteps(steps))/float64(len(steps)), time.Now())
	}
	setInstallerText(w.controls[controlProgressCount], count)
	setInstallerText(w.controls[controlProgressBar], count+" complete")
	installerInvalidateRect.Call(w.controls[controlProgressBar], 0, 0)
	var accessible []string
	for _, step := range steps {
		accessible = append(accessible, step.title+": "+step.status)
	}
	setInstallerText(w.controls[controlProgressSteps], strings.Join(accessible, "; "))
	list := w.controls[controlProgressLog]
	dc, _, _ := installerGetDC.Call(list)
	previous, _, _ := installerSelectObject.Call(dc, w.logFont)
	var extent int32
	for _, entry := range entries {
		value, _ := windows.UTF16FromString("[" + entry.status + "] " + entry.detail)
		var size installerPoint
		installerGDI32.NewProc("GetTextExtentPoint32W").Call(dc, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		if size.x+w.scaled(28) > extent {
			extent = size.x + w.scaled(28)
		}
	}
	installerSelectObject.Call(dc, previous)
	installerReleaseDC.Call(list, dc)
	installerSendMessage.Call(list, 0x0194, uintptr(extent), 0) // horizontal scroll only if needed
	top, _, _ := installerSendMessage.Call(list, 0x018e, 0, 0)  // LB_GETTOPINDEX
	followTail := int(top)+4 >= w.logRendered
	installerSendMessage.Call(list, 0x0184, 0, 0)
	for _, entry := range entries {
		text := "[" + entry.status + "] " + entry.detail
		installerSendMessage.Call(list, 0x0180, 0, uintptr(unsafe.Pointer(mustInstallerUTF16(text)))) // LB_ADDSTRING
	}
	w.logRendered = len(entries)
	if followTail && len(entries) > 4 {
		top = uintptr(len(entries) - 4)
	}
	installerSendMessage.Call(list, 0x0197, top, 0) // LB_SETTOPINDEX
	installerInvalidateRect.Call(w.controls[controlProgressSteps], 0, 1)
	installerInvalidateRect.Call(list, 0, 1)
}

func (w *installerWizard) handleDone() {
	w.mu.Lock()
	w.mu.Unlock() // acquire the worker's final result before presenting it
	w.refreshProgress()
	if w.result.err == nil && w.progressMotion.target == 1 && w.progressTimer != 0 {
		// Let the final verified step reach the end before showing the result.
		w.progressComplete = time.Now().Add(installerProgressEase + 80*time.Millisecond)
		return
	}
	w.busy = false
	w.showPage(installerPageResult)
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
		return wizard.colorEdit(wParam)
	case wmCtlColorBtn:
		installerSetBkMode.Call(wParam, transparent)
		installerSetTextColor.Call(wParam, installerRGB(226, 232, 240))
		return wizard.darkBrush
	case 0x0134: // WM_CTLCOLORLISTBOX
		installerSetBkColor.Call(wParam, installerRGB(27, 32, 43))
		return wizard.surfaceBrush
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
		} else if int(wParam) == controlProgressSteps {
			wizard.drawProgressSteps(item.device)
		} else if int(wParam) == controlProgressBar {
			wizard.drawProgressBar(item.device)
		} else if int(wParam) == controlProgressLog {
			wizard.drawProgressLog(item)
		} else {
			wizard.drawButton(int(wParam), item.device)
		}
		return 1
	case installerMessageShow:
		showInstallerWindow(hwnd)
		wizard.refreshEntireWindow()
		return 0
	case installerMessageRefresh:
		wizard.refreshPage()
		return 0
	case installerMessageProgress:
		wizard.refreshProgress()
		return 0
	case 0x0113: // WM_TIMER: time-based movement, independent of timer jitter.
		if wParam == 1 && wizard.page == installerPageProgress {
			if !wizard.progressComplete.IsZero() && !time.Now().Before(wizard.progressComplete) {
				wizard.busy = false
				wizard.showPage(installerPageResult)
			} else {
				installerInvalidateRect.Call(wizard.controls[controlProgressBar], 0, 0)
			}
		}
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
		installerUser32.NewProc("KillTimer").Call(hwnd, 1)
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
