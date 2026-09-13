//go:build windows

package main

import (
	"bytes"
	_ "embed"
	"errors"
	"fmt"
	"image/png"
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

const (
	installerWindowClass  = "SynesisInstallerWizard"
	installerWindowWidth  = 820
	installerWindowHeight = 560

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
	ssLeftNoWrap    = 0x0000000c
	ssEtchedHorz    = 0x00000010
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
	wmUser           = 0x0400
	bmGetCheck       = 0x00f0
	bmSetCheck       = 0x00f1
	bnClicked        = 0
	bstChecked       = 1
	swHide           = 0
	swShow           = 5

	mbOK              = 0x00000000
	mbIconError       = 0x00000010
	mbIconInformation = 0x00000040
	mbIconWarning     = 0x00000030

	transparent = 1
	opaque      = 2

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

	controlTitle = 1001 + iota
	controlDescription
	controlProductName
	controlProductDetails
	controlCapabilities
	controlActionGroup
	controlActionInstall
	controlActionUninstall
	controlActionPath
	controlInstallGroup
	controlInstallEdit
	controlBrowse
	controlInstallHint
	controlUninstallGroup
	controlUninstallPath
	controlMetadata
	controlUninstallWarning
	controlConfirmGroup
	controlConfirmSummary
	controlProgressStatus
	controlProgressBar
	controlResult
	controlFooter
	controlBack
	controlNext
	controlCancel
	controlFooterWarning
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
	installerRegisterClassEx    = installerUser32.NewProc("RegisterClassExW")
	installerAdjustWindowRectEx = installerUser32.NewProc("AdjustWindowRectEx")
	installerDefWindowProc      = installerUser32.NewProc("DefWindowProcW")
	installerDestroyWindow      = installerUser32.NewProc("DestroyWindow")
	installerDispatchMessage    = installerUser32.NewProc("DispatchMessageW")
	installerEnableWindow       = installerUser32.NewProc("EnableWindow")
	installerGetMessage         = installerUser32.NewProc("GetMessageW")
	installerGetModuleHandle    = installerKernel32.NewProc("GetModuleHandleW")
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
	installerBeginPaint         = installerUser32.NewProc("BeginPaint")
	installerEndPaint           = installerUser32.NewProc("EndPaint")
	installerFillRect           = installerUser32.NewProc("FillRect")
	installerFrameRect          = installerUser32.NewProc("FrameRect")
	installerGetClientRect      = installerUser32.NewProc("GetClientRect")
	installerGetWindowText      = installerUser32.NewProc("GetWindowTextW")
	installerGetWindowTextLen   = installerUser32.NewProc("GetWindowTextLengthW")
	installerLoadCursor         = installerUser32.NewProc("LoadCursorW")
	installerLoadIcon           = installerUser32.NewProc("LoadIconW")
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
	bundle         string
	installRoot    string
	installed      bool
	action         installerAction
	page           installerPage
	metadata       bool
	result         installerOperationResult
	hwnd           uintptr
	instance       uintptr
	controls       map[int]uintptr
	busy           bool
	mu             sync.Mutex
	titleFont      uintptr
	bodyFont       uintptr
	detailFont     uintptr
	productFont    uintptr
	buttonFont     uintptr
	cardTitleFont  uintptr
	cardDetailFont uintptr
	iconFont       uintptr
	logoBitmap     uintptr
	logoWidth      int32
	logoHeight     int32
	darkBrush      uintptr
	surfaceBrush   uintptr
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
	if w.darkBrush == 0 || w.surfaceBrush == 0 {
		return errors.New("unable to create the setup theme")
	}
	className, err := windows.UTF16PtrFromString(installerWindowClass)
	if err != nil {
		return err
	}
	icon, _, _ := installerLoadIcon.Call(0, idiApplication)
	cursor, _, _ := installerLoadCursor.Call(0, iddArrow)
	class := installerWndClassEx{
		cbSize:        uint32(unsafe.Sizeof(installerWndClassEx{})),
		lpfnWndProc:   installerWndProc,
		hInstance:     instance,
		hIcon:         icon,
		hCursor:       cursor,
		hbrBackground: w.darkBrush,
		lpszClassName: className,
		hIconSm:       icon,
	}
	installerRegisterClassEx.Call(uintptr(unsafe.Pointer(&class)))

	init := struct {
		dwSize uint32
		dwICC  uint32
	}{cbSizeOfInitCommonControls, iccProgressClass}
	installerInitCommonControls.Call(uintptr(unsafe.Pointer(&init)))

	style := installerWindowStyle()
	windowRect := installerRect{right: installerWindowWidth, bottom: installerWindowHeight}
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
	installerWindows.Store(w.hwnd, w)
	w.createControls()
	w.showPage(installerPageAction)
	return nil
}

func installerWindowStyle() uint32 {
	return uint32(wsCaption | wsSysMenu | wsMinimizeBox | wsClipChildren)
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

	w.controls[controlTitle] = w.addControl(controlTitle, "STATIC", "", static, 30, 25, 650, 42, 0)
	w.controls[controlDescription] = w.addControl(controlDescription, "STATIC", "", static, 30, 75, 650, 30, 0)
	w.controls[controlProductName] = w.addControl(controlProductName, "STATIC", "", static, 30, 112, 520, 30, 0)
	w.controls[controlProductDetails] = w.addControl(controlProductDetails, "STATIC", "", static, 30, 142, 560, 48, 0)
	w.controls[controlCapabilities] = w.addControl(controlCapabilities, "STATIC", "", static, 30, 202, 750, 88, 0)

	w.controls[controlActionGroup] = w.addControl(controlActionGroup, "STATIC", "Choose what to do", static, 30, 303, 760, 22, 0)
	w.controls[controlActionInstall] = w.addControl(controlActionInstall, "BUTTON", "Install or update Synesis", button|bsOwnerDraw|wsGroup, 30, 330, 760, 42, 0)
	w.controls[controlActionUninstall] = w.addControl(controlActionUninstall, "BUTTON", "Uninstall Synesis", button|bsOwnerDraw, 30, 378, 760, 42, 0)
	w.controls[controlActionPath] = w.addControl(controlActionPath, "STATIC", "", static|ssLeftNoWrap, 32, 426, 750, 14, 0)

	w.controls[controlInstallGroup] = w.addControl(controlInstallGroup, "STATIC", "Destination folder", static, 30, 140, 760, 26, 0)
	w.controls[controlInstallEdit] = w.addControl(controlInstallEdit, "EDIT", "", child|wsTabStop|esAutoHScroll, 52, 188, 570, 30, wsExClientEdge)
	w.controls[controlBrowse] = w.addControl(controlBrowse, "BUTTON", "Browse...", button|bsPushButton, 635, 188, 130, 30, 0)
	w.controls[controlInstallHint] = w.addControl(controlInstallHint, "STATIC", "", static, 52, 238, 710, 42, 0)

	w.controls[controlUninstallGroup] = w.addControl(controlUninstallGroup, "STATIC", "Uninstall options", static, 30, 140, 760, 26, 0)
	w.controls[controlUninstallPath] = w.addControl(controlUninstallPath, "STATIC", "", static, 52, 174, 710, 40, 0)
	w.controls[controlMetadata] = w.addControl(controlMetadata, "BUTTON", "Remove Synesis metadata (identity and installer state)", button|bsAutoCheckBox, 52, 224, 710, 28, 0)
	w.controls[controlUninstallWarning] = w.addControl(controlUninstallWarning, "STATIC", "", static, 52, 264, 710, 42, 0)

	w.controls[controlConfirmGroup] = w.addControl(controlConfirmGroup, "STATIC", "Review", static, 30, 140, 760, 26, 0)
	w.controls[controlConfirmSummary] = w.addControl(controlConfirmSummary, "STATIC", "", static, 52, 174, 710, 155, 0)

	w.controls[controlProgressStatus] = w.addControl(controlProgressStatus, "STATIC", "", static, 30, 175, 760, 55, 0)
	w.controls[controlProgressBar] = w.addControl(controlProgressBar, "msctls_progress32", "", child|pbsMarquee, 30, 245, 760, 28, 0)
	w.controls[controlResult] = w.addControl(controlResult, "STATIC", "", static, 30, 160, 760, 230, 0)
	w.controls[controlFooter] = w.addControl(controlFooter, "STATIC", "", uint32(wsChild|wsVisible|ssEtchedHorz), 30, 486, 760, 2, 0)
	w.controls[controlFooterWarning] = w.addControl(controlFooterWarning, "STATIC", "", static, 58, 503, 730, 42, 0)
	w.controls[controlBack] = w.addControl(controlBack, "BUTTON", "< Back", button|bsOwnerDraw, 470, 440, 90, 34, 0)
	w.controls[controlNext] = w.addControl(controlNext, "BUTTON", "Next >", button|bsOwnerDraw, 570, 440, 105, 34, 0)
	w.controls[controlCancel] = w.addControl(controlCancel, "BUTTON", "Cancel", button|bsOwnerDraw, 685, 440, 105, 34, 0)

	font, _, _ := installerGetStockObject.Call(defaultGuiFont)
	for _, control := range w.controls {
		installerSendMessage.Call(control, wmSetFont, font, 1)
	}
	w.titleFont = createInstallerFont(-30, fontWeightBold)
	w.bodyFont = createInstallerFont(-16, fontWeightNormal)
	w.detailFont = createInstallerFont(-15, fontWeightNormal)
	w.productFont = createInstallerFont(-19, fontWeightBold)
	w.buttonFont = createInstallerFont(-15, fontWeightNormal)
	w.cardTitleFont = createInstallerFont(-16, fontWeightBold)
	w.cardDetailFont = createInstallerFont(-13, fontWeightNormal)
	w.iconFont = createInstallerFont(-54, fontWeightBold)
	setInstallerFont(w.controls[controlTitle], w.titleFont)
	setInstallerFont(w.controls[controlDescription], w.bodyFont)
	setInstallerFont(w.controls[controlProductName], w.productFont)
	setInstallerFont(w.controls[controlProductDetails], w.detailFont)
	setInstallerFont(w.controls[controlCapabilities], w.bodyFont)
	setInstallerFont(w.controls[controlActionGroup], w.detailFont)
	setInstallerFont(w.controls[controlActionPath], w.cardDetailFont)
	setInstallerFont(w.controls[controlInstallGroup], w.detailFont)
	setInstallerFont(w.controls[controlUninstallGroup], w.detailFont)
	setInstallerFont(w.controls[controlConfirmGroup], w.detailFont)
	setInstallerFont(w.controls[controlFooterWarning], w.detailFont)
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
	return createInstallerWindow(className, title, exStyle, style, x, y, width, height, w.hwnd, uintptr(id), w.instance)
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
		0,
		0,
		uintptr(0),
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
	setInstallerText(w.controls[controlFooterWarning], "Only install software you trust. Setup changes Synesis files and may register the per-user synesis:// handler.")
	for id, control := range w.controls {
		if id == controlFooter || id == controlFooterWarning {
			setInstallerVisible(control, true)
			continue
		}
		setInstallerVisible(control, false)
	}
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
		setInstallerVisible(w.controls[controlActionGroup], true)
		setInstallerVisible(w.controls[controlActionInstall], true)
		setInstallerVisible(w.controls[controlActionUninstall], true)
		setInstallerVisible(w.controls[controlActionPath], true)
		setInstallerText(w.controls[controlProductName], "Synesis")
		setInstallerText(w.controls[controlProductDetails], "Local-first collaboration runtime\r\nVersion 0.1.0-dev.local · Synesis contributors")
		setInstallerText(w.controls[controlCapabilities], "Capabilities:\r\n• Install or repair the verified local runtime\r\n• Register the per-user synesis:// invitation handler\r\n• Preserve project, repository, and workspace data")
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
		setInstallerText(w.controls[controlInstallEdit], w.installRoot)
		setInstallerText(w.controls[controlInstallHint], "Projects, repositories, workspaces, and provider configuration are not installed or removed here.")
	case installerPageUninstall:
		title = "Uninstall Synesis"
		description = "Remove the Synesis application from the selected installation folder. User project data is never deleted by this installer."
		setInstallerVisible(w.controls[controlUninstallGroup], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerVisible(w.controls[controlMetadata], true)
		setInstallerVisible(w.controls[controlUninstallWarning], true)
		setInstallerText(w.controls[controlUninstallPath], "Installation folder:\r\n"+w.installRoot)
		setInstallerText(w.controls[controlUninstallWarning], "Unchecked: keep installer-owned identity and admin metadata.\r\nChecked: remove those records too.")
		setInstallerCheck(w.controls[controlMetadata], w.metadata)
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
		label := "Next >"
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
	installerInvalidateRect.Call(w.hwnd, 0, 1)
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

	brandLeft := client.right - 130
	brandTop := int32(24)
	brandRight := brandLeft + 92
	brandBottom := brandTop + 92
	brandBrush, _, _ := installerCreateSolidBrush.Call(installerRGB(36, 48, 72))
	installerFillRect.Call(hdc, uintptr(unsafe.Pointer(&installerRect{
		left: brandLeft, top: brandTop, right: brandRight, bottom: brandBottom,
	})), brandBrush)
	installerDeleteObject.Call(brandBrush)
	if w.logoBitmap != 0 {
		drawInstallerLogo(hdc, w.logoBitmap, w.logoWidth, w.logoHeight, brandLeft+4, brandTop+4, 84, 84)
	} else {
		oldFont, _, _ := installerSelectObject.Call(hdc, w.iconFont)
		installerSetBkMode.Call(hdc, transparent)
		installerSetTextColor.Call(hdc, installerRGB(255, 255, 255))
		logo, _ := windows.UTF16PtrFromString("S")
		logoRect := installerRect{left: brandLeft, top: brandTop + 4, right: brandRight, bottom: brandBottom}
		installerDrawText.Call(hdc, uintptr(unsafe.Pointer(logo)), 1, uintptr(unsafe.Pointer(&logoRect)), dtCenter|dtVCenter|dtSingleLine)
		installerSelectObject.Call(hdc, oldFont)
	}

	linePen, _, _ := installerCreatePen.Call(0, 1, installerRGB(48, 56, 70))
	oldPen, _, _ := installerSelectObject.Call(hdc, linePen)
	installerMoveToEx.Call(hdc, 24, 128, 0)
	installerLineTo.Call(hdc, uintptr(client.right-24), 128)
	installerSelectObject.Call(hdc, oldPen)
	installerDeleteObject.Call(linePen)

	warningBrush, _, _ := installerCreateSolidBrush.Call(installerRGB(245, 158, 11))
	warningRect := installerRect{left: 27, top: 507, right: 51, bottom: 531}
	oldBrush, _, _ := installerSelectObject.Call(hdc, warningBrush)
	installerRoundRect.Call(hdc, uintptr(warningRect.left), uintptr(warningRect.top), uintptr(warningRect.right), uintptr(warningRect.bottom), 6, 6)
	installerSelectObject.Call(hdc, oldBrush)
	installerDeleteObject.Call(warningBrush)
	oldFont, _, _ := installerSelectObject.Call(hdc, w.bodyFont)
	installerSetTextColor.Call(hdc, installerRGB(15, 18, 24))
	mark, _ := windows.UTF16PtrFromString("!")
	installerDrawText.Call(hdc, uintptr(unsafe.Pointer(mark)), 1, uintptr(unsafe.Pointer(&warningRect)), dtCenter|dtVCenter|dtSingleLine)
	installerSelectObject.Call(hdc, oldFont)
}

func createInstallerLogo(hwnd uintptr) (bitmap uintptr, width, height int32, err error) {
	image, decodeErr := png.Decode(bytes.NewReader(installerLogoPNG))
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
			data[index] = byte((blue*alpha + 0x7fff) / 0xffff)
			data[index+1] = byte((green*alpha + 0x7fff) / 0xffff)
			data[index+2] = byte((red*alpha + 0x7fff) / 0xffff)
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

func (w *installerWizard) drawButton(controlID int) {
	if controlID == controlActionInstall || controlID == controlActionUninstall {
		w.drawActionCard(controlID)
		return
	}
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	device, _, _ := installerGetDC.Call(control)
	if device == 0 {
		return
	}
	defer installerReleaseDC.Call(control, device)
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	primary := controlID == controlNext
	red, green, blue := byte(32), byte(38), byte(50)
	textRed, textGreen, textBlue := byte(226), byte(232), byte(240)
	if primary {
		red, green, blue = 0, 120, 212
		textRed, textGreen, textBlue = 255, 255, 255
	}
	brush, _, _ := installerCreateSolidBrush.Call(installerRGB(red, green, blue))
	pen, _, _ := installerCreatePen.Call(0, 1, installerRGB(subtractByte(red, 25), subtractByte(green, 25), subtractByte(blue, 25)))
	oldBrush, _, _ := installerSelectObject.Call(device, brush)
	oldPen, _, _ := installerSelectObject.Call(device, pen)
	installerRoundRect.Call(device, 0, 0, uintptr(rect.right), uintptr(rect.bottom), 8, 8)
	installerSelectObject.Call(device, oldBrush)
	installerSelectObject.Call(device, oldPen)
	installerDeleteObject.Call(brush)
	installerDeleteObject.Call(pen)

	oldFont, _, _ := installerSelectObject.Call(device, w.buttonFont)
	installerSetBkMode.Call(device, transparent)
	installerSetTextColor.Call(device, installerRGB(textRed, textGreen, textBlue))
	text := getInstallerText(control)
	installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16(text))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&rect)), dtCenter|dtVCenter|dtSingleLine|dtEndEllipsis)
	installerSelectObject.Call(device, oldFont)
}

func (w *installerWizard) drawActionCard(controlID int) {
	control := w.controls[controlID]
	if control == 0 {
		return
	}
	device, _, _ := installerGetDC.Call(control)
	if device == 0 {
		return
	}
	defer installerReleaseDC.Call(control, device)
	rect := installerRect{}
	installerGetClientRect.Call(control, uintptr(unsafe.Pointer(&rect)))
	selected := (controlID == controlActionInstall && w.action == installerActionInstall) ||
		(controlID == controlActionUninstall && w.action == installerActionUninstall)
	enabled := controlID != controlActionUninstall || w.installed
	fillRed, fillGreen, fillBlue := byte(24), byte(30), byte(40)
	borderRed, borderGreen, borderBlue := byte(54), byte(65), byte(83)
	textRed, textGreen, textBlue := byte(226), byte(232), byte(240)
	detailRed, detailGreen, detailBlue := byte(148), byte(163), byte(184)
	if selected {
		fillRed, fillGreen, fillBlue = 17, 49, 79
		borderRed, borderGreen, borderBlue = 0, 145, 255
	}
	if !enabled {
		fillRed, fillGreen, fillBlue = 21, 24, 31
		borderRed, borderGreen, borderBlue = 40, 46, 58
		textRed, textGreen, textBlue = 100, 116, 139
		detailRed, detailGreen, detailBlue = 71, 85, 105
	}
	brush, _, _ := installerCreateSolidBrush.Call(installerRGB(fillRed, fillGreen, fillBlue))
	pen, _, _ := installerCreatePen.Call(0, 1, installerRGB(borderRed, borderGreen, borderBlue))
	oldBrush, _, _ := installerSelectObject.Call(device, brush)
	oldPen, _, _ := installerSelectObject.Call(device, pen)
	installerRoundRect.Call(device, 0, 0, uintptr(rect.right), uintptr(rect.bottom), 10, 10)
	installerSelectObject.Call(device, oldBrush)
	installerSelectObject.Call(device, oldPen)
	installerDeleteObject.Call(brush)
	installerDeleteObject.Call(pen)
	if selected {
		accent, _, _ := installerCreateSolidBrush.Call(installerRGB(0, 145, 255))
		installerFillRect.Call(device, uintptr(unsafe.Pointer(&installerRect{left: 0, top: 9, right: 4, bottom: rect.bottom - 9})), accent)
		installerDeleteObject.Call(accent)
	}
	titleRect := installerRect{left: 22, top: 5, right: rect.right - 92, bottom: 24}
	detailRect := installerRect{left: 22, top: 22, right: rect.right - 92, bottom: rect.bottom - 4}
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
	if selected {
		selectionRect := installerRect{left: rect.right - 78, top: 12, right: rect.right - 18, bottom: rect.bottom - 8}
		oldFont, _, _ = installerSelectObject.Call(device, w.cardDetailFont)
		installerSetTextColor.Call(device, installerRGB(125, 211, 252))
		installerDrawText.Call(device, uintptr(unsafe.Pointer(mustInstallerUTF16("Selected"))), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&selectionRect)), dtCenter|dtVCenter|dtSingleLine)
		installerSelectObject.Call(device, oldFont)
	}
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
	switch w.installerControlID(hwnd) {
	case controlProductName:
		red, green, blue = 96, 190, 255
	case controlProductDetails, controlFooterWarning, controlActionPath, controlInstallHint, controlUninstallWarning:
		red, green, blue = 148, 163, 184
	case controlTitle:
		red, green, blue = 248, 250, 252
	}
	installerSetTextColor.Call(device, installerRGB(red, green, blue))
	return w.darkBrush
}

func (w *installerWizard) disposeFonts() {
	for _, font := range []uintptr{w.titleFont, w.bodyFont, w.detailFont, w.productFont, w.buttonFont, w.cardTitleFont, w.cardDetailFont, w.iconFont} {
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
}

func (w *installerWizard) disposeTheme() {
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
}

func (w *installerWizard) goBack() {
	switch w.page {
	case installerPageInstall, installerPageUninstall:
		if w.page == installerPageUninstall {
			w.metadata = installerButtonChecked(w.controls[controlMetadata])
		}
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
		w.metadata = installerButtonChecked(w.controls[controlMetadata])
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
		wizard.drawButton(int(wParam))
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
