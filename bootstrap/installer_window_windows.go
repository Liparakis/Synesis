//go:build windows

package main

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var installerWindows sync.Map

var installerWndProc = syscall.NewCallback(installerWindowProc)

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

func (w *installerWizard) installerControlID(hwnd uintptr) int {
	for id, control := range w.controls {
		if control == hwnd {
			return id
		}
	}
	return 0
}
