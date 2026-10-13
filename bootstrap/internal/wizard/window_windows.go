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
	w.windowBrush, _, _ = win32.CreateSolidBrush.Call(draw.Window.Ref())
	w.fieldBrush, _, _ = win32.CreateSolidBrush.Call(draw.Field.Ref())
	w.lineBrush, _, _ = win32.CreateSolidBrush.Call(draw.LineStrong.Ref())
	if w.windowBrush == 0 || w.fieldBrush == 0 || w.lineBrush == 0 {
		return errors.New("unable to create the setup theme")
	}
	w.windowIconLarge, w.windowIconSmall = loadInstallerIcons(instance)
	cursor, _, _ := win32.LoadCursor.Call(0, win32.IddArrow)
	class := win32.WndClassEx{
		CbSize:        uint32(unsafe.Sizeof(win32.WndClassEx{})),
		LpfnWndProc:   installerWndProc,
		HInstance:     instance,
		HCursor:       cursor,
		HbrBackground: w.windowBrush,
		LpszClassName: win32.MustUTF16(installerWindowClass),
	}
	win32.RegisterClassEx.Call(uintptr(unsafe.Pointer(&class)))

	style := installerWindowStyle()
	clientWidth, clientHeight, scale := installerWindowMetrics()
	w.scale = scale
	windowRect := win32.Rect{Right: clientWidth, Bottom: clientHeight}
	win32.AdjustWindowRectEx.Call(uintptr(unsafe.Pointer(&windowRect)), uintptr(style), 0, win32.WsExDlgModal)
	width := windowRect.Right - windowRect.Left
	height := windowRect.Bottom - windowRect.Top
	workWidth, _, _ := win32.GetSystemMetrics.Call(win32.SmCxWorkArea)
	workHeight, _, _ := win32.GetSystemMetrics.Call(win32.SmCyWorkArea)
	w.hwnd = win32.CreateWindow(installerWindowClass, "Synesis Setup", win32.WsExDlgModal, style,
		max(0, (int32(workWidth)-width)/2), max(0, (int32(workHeight)-height)/2), width, height, 0, 0, instance)
	if w.hwnd == 0 {
		return errors.New("unable to create the setup window")
	}
	setInstallerDarkTitleBar(w.hwnd)
	win32.SendMessage.Call(w.hwnd, win32.WmSetIcon, win32.IconBig, w.windowIconLarge)
	win32.SendMessage.Call(w.hwnd, win32.WmSetIcon, win32.IconSmall, w.windowIconSmall)
	// A missing mark leaves the wordmark on its own; setup still works.
	w.logo, _ = draw.NewLogo(w.scaled(installerLogoSize))
	installerWindows.Store(w.hwnd, w)
	w.createControls()
	w.showPage(installerPageAction)
	return nil
}

func installerWindowStyle() uint32 {
	return uint32(win32.WsCaption | win32.WsSysMenu | win32.WsMinimizeBox | win32.WsClipChildren)
}

// installerWindowMetrics sizes the client area for the display's scale, and
// shrinks it to the work area on a display too small to hold it.
func installerWindowMetrics() (width, height int32, scale float64) {
	scale = 1
	if dpi := win32.User32.NewProc("GetDpiForSystem"); dpi.Find() == nil {
		if value, _, _ := dpi.Call(); value > 0 {
			scale = float64(value) / 96
		}
	}
	workWidth, _, _ := win32.GetSystemMetrics.Call(win32.SmCxWorkArea)
	workHeight, _, _ := win32.GetSystemMetrics.Call(win32.SmCyWorkArea)
	if availableWidth, availableHeight := float64(workWidth)-48, float64(workHeight)-48; availableWidth > 0 && availableHeight > 0 {
		scale = math.Min(scale, math.Min(availableWidth/installerWindowWidth, availableHeight/installerWindowHeight))
	}
	width = int32(math.Round(installerWindowWidth * scale))
	height = int32(math.Round(installerWindowHeight * scale))
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

func (w *installerWizard) scaledRect(x, y, width, height int32) win32.Rect {
	return win32.Rect{Left: w.scaled(x), Top: w.scaled(y), Right: w.scaled(x + width), Bottom: w.scaled(y + height)}
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
	caption := uint32(draw.Window.Ref())
	text := uint32(draw.Copy.Ref())
	border := uint32(draw.Line.Ref())
	// These attributes are ignored on older Windows builds. On current Windows
	// they keep the system accent colour off the setup title bar, so the frame
	// and the page read as one surface.
	win32.DwmSetWindowAttr.Call(hwnd, 35, uintptr(unsafe.Pointer(&caption)), uintptr(unsafe.Sizeof(caption)))
	win32.DwmSetWindowAttr.Call(hwnd, 36, uintptr(unsafe.Pointer(&text)), uintptr(unsafe.Sizeof(text)))
	win32.DwmSetWindowAttr.Call(hwnd, 34, uintptr(unsafe.Pointer(&border)), uintptr(unsafe.Sizeof(border)))
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
		// The dialog keyboard interface gives the wizard Tab, Space, Enter and
		// Escape without making it a dialog resource.
		if handled, _, _ := win32.IsDialogMessage.Call(w.hwnd, uintptr(unsafe.Pointer(&message))); handled != 0 {
			continue
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
		wizard.paintBackdrop()
		return 0
	case win32.WmEraseBkgnd:
		return 1
	case win32.WmCtlColorStatic:
		return wizard.colorStatic(wParam, lParam)
	case win32.WmCtlColorEdit:
		return wizard.colorEdit(wParam)
	case win32.WmCtlColorBtn:
		return wizard.windowBrush
	case 0x0134: // WM_CTLCOLORLISTBOX
		win32.SetBkColor.Call(wParam, draw.Field.Ref())
		return wizard.fieldBrush
	case win32.WmDrawItem:
		if lParam == 0 {
			return 0
		}
		// Copy the Windows-owned message data into Go-owned memory without
		// treating an integer callback parameter as a Go pointer.
		var item win32.DrawItem
		win32.CopyMemory.Call(uintptr(unsafe.Pointer(&item)), lParam, unsafe.Sizeof(item))
		wizard.drawItem(int(wParam), item)
		return 1
	case installerMessageShow:
		win32.Show(hwnd)
		wizard.refreshPage()
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
		win32.KillTimer.Call(hwnd, 1)
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
