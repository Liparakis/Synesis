//go:build windows

package wizard

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/assets"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

var installerWindows sync.Map

var installerWndProc = syscall.NewCallback(installerWindowProc)

func (w *installerWizard) createWindow() error {
	instance, _, _ := win32.GetModuleHandle.Call(0)
	if instance == 0 {
		return errors.New("unable to locate the setup module")
	}
	w.instance = instance
	w.darkBrush, _, _ = win32.CreateSolidBrush.Call(win32.RGB(15, 18, 24))
	w.surfaceBrush, _, _ = win32.CreateSolidBrush.Call(win32.RGB(27, 32, 43))
	w.cardBrush, _, _ = win32.CreateSolidBrush.Call(win32.RGB(24, 29, 36))
	w.warningBrush, _, _ = win32.CreateSolidBrush.Call(win32.RGB(36, 31, 22))
	if w.darkBrush == 0 || w.surfaceBrush == 0 || w.cardBrush == 0 || w.warningBrush == 0 {
		return errors.New("unable to create the setup theme")
	}
	className, err := windows.UTF16PtrFromString(installerWindowClass)
	if err != nil {
		return err
	}
	w.windowIconLarge, w.windowIconSmall = loadInstallerIcons(instance)
	cursor, _, _ := win32.LoadCursor.Call(0, win32.IddArrow)
	class := win32.WndClassEx{
		CbSize:      uint32(unsafe.Sizeof(win32.WndClassEx{})),
		LpfnWndProc: installerWndProc,
		HInstance:   instance,
		// Keep the class icon empty so the uninstall page can genuinely suppress
		// the product mark via its page-specific WM_SETICON state.
		HIcon:         0,
		HCursor:       cursor,
		HbrBackground: w.darkBrush,
		LpszClassName: className,
		HIconSm:       0,
	}
	win32.RegisterClassEx.Call(uintptr(unsafe.Pointer(&class)))

	init := struct {
		dwSize uint32
		dwICC  uint32
	}{win32.CbSizeOfInitCommonControls, win32.IccProgressClass}
	win32.InitCommonControls.Call(uintptr(unsafe.Pointer(&init)))

	style := installerWindowStyle()
	windowWidth, windowHeight, scale := installerWindowMetrics()
	w.scale = scale
	windowRect := win32.Rect{Right: windowWidth, Bottom: windowHeight}
	win32.AdjustWindowRectEx.Call(
		uintptr(unsafe.Pointer(&windowRect)),
		uintptr(style),
		0,
		win32.WsExDlgModal,
	)
	width := windowRect.Right - windowRect.Left
	height := windowRect.Bottom - windowRect.Top
	title, err := windows.UTF16PtrFromString("Synesis Setup")
	if err != nil {
		return err
	}
	w.hwnd = win32.CreateWindow(
		installerWindowClass,
		windows.UTF16PtrToString(title),
		win32.WsExDlgModal,
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
	w.logoBitmap, w.logoWidth, w.logoHeight, _ = draw.CreateLogo(w.hwnd)
	w.art = make(map[string]draw.Art)
	for _, name := range []string{"brand", "ribbon", "card", "card-selected", "card-disabled", "primary", "secondary", "install", "link", "data"} {
		data, err := assets.Setup.ReadFile("setup/" + name + ".png")
		if err != nil {
			return err
		}
		bitmap, width, height, err := draw.CreateBitmap(w.hwnd, data)
		if err != nil {
			return err
		}
		w.art[name] = draw.Art{Bitmap: bitmap, Width: width, Height: height}
	}
	installerWindows.Store(w.hwnd, w)
	w.createControls()
	w.showPage(installerPageAction)
	return nil
}

func installerWindowStyle() uint32 {
	return uint32(win32.WsCaption | win32.WsSysMenu | win32.WsMinimizeBox | win32.WsClipChildren)
}

// installerWindowMetrics keeps the reference composition intact on a normal
// desktop and scales it down to the current work area on smaller displays.
func installerWindowMetrics() (width, height int32, scale float64) {
	scale = installerDesktopScale
	workWidth, _, _ := win32.GetSystemMetrics.Call(win32.SmCxWorkArea)
	workHeight, _, _ := win32.GetSystemMetrics.Call(win32.SmCyWorkArea)
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
	large, _, _ = win32.LoadImage.Call(instance, 1, 1, 32, 32, 0x00000040)
	small, _, _ = win32.LoadImage.Call(instance, 1, 1, 16, 16, 0x00000040)
	if large == 0 {
		large, _, _ = win32.LoadIcon.Call(0, win32.IdiApplication)
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
	result, _, _ := win32.DwmSetWindowAttr.Call(hwnd, 20, uintptr(unsafe.Pointer(&value)), uintptr(unsafe.Sizeof(value)))
	if result != 0 {
		win32.DwmSetWindowAttr.Call(hwnd, 19, uintptr(unsafe.Pointer(&value)), uintptr(unsafe.Sizeof(value)))
	}
	caption := uint32(win32.RGB(15, 18, 24))
	text := uint32(win32.RGB(226, 232, 240))
	border := uint32(win32.RGB(15, 18, 24))
	// These attributes are ignored on older Windows builds. On current Windows
	// they prevent the system accent colour from turning the setup title bar
	// purple while the rest of the installer is deliberately near-black.
	win32.DwmSetWindowAttr.Call(hwnd, 35, uintptr(unsafe.Pointer(&caption)), uintptr(unsafe.Sizeof(caption)))
	win32.DwmSetWindowAttr.Call(hwnd, 36, uintptr(unsafe.Pointer(&text)), uintptr(unsafe.Sizeof(text)))
	win32.DwmSetWindowAttr.Call(hwnd, 34, uintptr(unsafe.Pointer(&border)), uintptr(unsafe.Sizeof(border)))
}

// setPageIcon keeps every wizard page on the same native application shell.
func (w *installerWizard) setPageIcon(_ installerPage) {
	win32.SendMessage.Call(w.hwnd, win32.WmSetIcon, win32.IconBig, w.windowIconLarge)
	win32.SendMessage.Call(w.hwnd, win32.WmSetIcon, win32.IconSmall, w.windowIconSmall)
}

func (w *installerWizard) messageLoop() error {
	var message win32.Message
	for {
		result, _, callErr := win32.GetMessage.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(result) == -1 {
			return fmt.Errorf("Windows setup message loop failed: %v", callErr)
		}
		if result == 0 {
			return nil
		}
		win32.TranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		win32.DispatchMessage.Call(uintptr(unsafe.Pointer(&message)))
	}
}

func installerWindowProc(hwnd, message, wParam, lParam uintptr) uintptr {
	value, ok := installerWindows.Load(hwnd)
	if !ok {
		return win32.DefaultWindowProc(hwnd, message, wParam, lParam)
	}
	wizard := value.(*installerWizard)
	switch uint32(message) {
	case win32.WmPaint:
		drawInstallerChrome(wizard)
		return 0
	case win32.WmEraseBkgnd:
		return 1
	case win32.WmCtlColorStatic:
		return wizard.colorStatic(wParam, lParam)
	case win32.WmCtlColorEdit:
		return wizard.colorEdit(wParam)
	case win32.WmCtlColorBtn:
		win32.SetBkMode.Call(wParam, win32.Transparent)
		win32.SetTextColor.Call(wParam, win32.RGB(226, 232, 240))
		return wizard.darkBrush
	case 0x0134: // WM_CTLCOLORLISTBOX
		win32.SetBkColor.Call(wParam, win32.RGB(27, 32, 43))
		return wizard.surfaceBrush
	case win32.WmDrawItem:
		if lParam == 0 {
			return 0
		}
		// Copy the Windows-owned message data into Go-owned memory without
		// treating an integer callback parameter as a Go pointer.
		var item win32.DrawItem
		win32.CopyMemory.Call(uintptr(unsafe.Pointer(&item)), lParam, unsafe.Sizeof(item))
		if int(wParam) == controlMetadata {
			wizard.drawMetadataControl(item.Device)
		} else if int(wParam) == controlProgressSteps {
			wizard.drawProgressSteps(item.Device)
		} else if int(wParam) == controlProgressBar {
			wizard.drawProgressBar(item.Device)
		} else if int(wParam) == controlProgressLog {
			wizard.drawProgressLog(item)
		} else {
			wizard.drawButton(int(wParam), item.Device)
		}
		return 1
	case installerMessageShow:
		win32.Show(hwnd)
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
				win32.InvalidateRect.Call(wizard.controls[controlProgressBar], 0, 0)
			}
		}
		return 0
	case win32.WmCommand:
		wizard.handleCommand(uint16(wParam&0xffff), uint16((wParam>>16)&0xffff))
		return 0
	case installerMessageDone:
		wizard.handleDone()
		return 0
	case win32.WmClose:
		wizard.closeOrWarn()
		return 0
	case win32.WmDestroy:
		win32.User32.NewProc("KillTimer").Call(hwnd, 1)
		wizard.disposeFonts()
		wizard.disposeTheme()
		installerWindows.Delete(hwnd)
		win32.PostQuitMessage.Call(0)
		return 0
	default:
		return win32.DefaultWindowProc(hwnd, message, wParam, lParam)
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
