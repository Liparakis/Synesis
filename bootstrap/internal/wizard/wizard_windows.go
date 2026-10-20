//go:build windows

package wizard

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"synesis/bootstrap/internal/folderpicker"
	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/install/embedded"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/uishortcut"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/protocolreg"
	"synesis/bootstrap/internal/releasemanifest"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
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

type installerWizard struct {
	bundle           string
	bundleVersion    string
	installedVersion string
	installRoot      string
	installDraft     string
	scale            float64
	installed        bool
	action           installerAction
	page             installerPage
	metadata         bool
	shortcut         bool
	allowLink        bool
	result           installerOperationResult
	hwnd             uintptr
	instance         uintptr
	controls         map[int]uintptr
	busy             bool
	mu               sync.Mutex
	steps            []installerStep
	logEntries       []installerLogEntry
	logRendered      int
	operationStatus  string
	progressMotion   draw.ProgressMotion
	progressEpoch    time.Time
	progressComplete time.Time
	progressRaster   *draw.Raster
	progressTimer    uintptr
	smoothIcons      map[string]*draw.Raster
	logo             *draw.Raster
	// backdrop holds everything that is not a control: the rail, the panels
	// and the marks. Controls paint this surface behind their own content.
	backdrop          *draw.Raster
	backdropBrush     uintptr
	resultDetailsOpen bool
	// awaitBundle is set by the single-file installer, whose bundle is still
	// being unpacked while the wizard is already open. It blocks until the
	// bundle is usable.
	awaitBundle      func() (string, error)
	executeOperation func(string, string, installerAction, bool, install.OperationProgress) installerOperationResult
	titleFont        uintptr
	strongFont       uintptr
	bodyFont         uintptr
	detailFont       uintptr
	smallFont        uintptr
	wordmarkFont     uintptr
	logFont          uintptr
	windowIconLarge  uintptr
	windowIconSmall  uintptr
	windowBrush      uintptr
	fieldBrush       uintptr
	lineBrush        uintptr
}

// Run opens the native Windows setup wizard. Started from an extracted bundle
// it installs that bundle. Started as the single-file installer it opens at
// once and unpacks the appended bundle in the background, so the window never
// waits for the unpacking.
func Run() error {
	// Win32 windows and their child controls are thread-affine. Keep the Go
	// goroutine on the creating OS thread for the entire message-loop lifetime.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	// shortcut: the scale is read once from the primary display, so Windows
	// stretches the window on a monitor with another scale; handle
	// WM_DPICHANGED if that blur is reported.
	win32.User32.NewProc("SetProcessDPIAware").Call()
	var bundle, version string
	var unpacking *unpackedBundle
	if embedded.HasBundle() {
		version = plainVersion(embedded.BundleVersion())
		unpacking = startUnpacking(embedded.Extract)
		defer unpacking.remove()
	} else {
		var err error
		if bundle, err = bundlecheck.RunningRootShape(); err != nil {
			win32.ShowMessage(0, "Synesis Setup", err.Error(), win32.MbIconError)
			return nil
		}
		version = installerBundleVersion(bundle)
	}
	wizard, err := newInstallerWizard(bundle, version)
	if err != nil {
		win32.ShowMessage(0, "Synesis Setup", err.Error(), win32.MbIconError)
		return nil
	}
	if unpacking != nil {
		wizard.awaitBundle = unpacking.wait
	}
	if err := wizard.run(); err != nil {
		win32.ShowMessage(wizard.hwnd, "Synesis Setup", err.Error(), win32.MbIconError)
	}
	return nil
}

// unpackedBundle is a bundle being unpacked on another goroutine.
type unpackedBundle struct {
	done    chan struct{}
	bundle  string
	cleanup func()
	err     error
}

func startUnpacking(extract func() (string, func(), error)) *unpackedBundle {
	unpacking := &unpackedBundle{done: make(chan struct{})}
	go func() {
		defer close(unpacking.done)
		unpacking.bundle, unpacking.cleanup, unpacking.err = extract()
	}()
	return unpacking
}

func (u *unpackedBundle) wait() (string, error) {
	<-u.done
	return u.bundle, u.err
}

// remove waits for the unpacking to end before deleting what it wrote, so a
// window closed early leaves nothing half-written behind.
func (u *unpackedBundle) remove() {
	<-u.done
	u.cleanup()
}

func newInstallerWizard(bundle, bundleVersion string) (*installerWizard, error) {
	defaultPaths, err := layout.Resolve("")
	if err != nil {
		return nil, err
	}
	root := discoverWizardInstallRoot(defaultPaths.Root)
	paths, err := layout.Resolve(root)
	if err != nil {
		return nil, err
	}
	return &installerWizard{
		bundle:           bundle,
		bundleVersion:    bundleVersion,
		installedVersion: installerInstalledVersion(paths),
		installRoot:      paths.Root,
		installed:        installfs.FileExists(paths.Root),
		// Offered by default on a first install; afterwards the choice follows
		// what is on the desktop.
		shortcut: !installfs.FileExists(paths.Root) || desktopShortcutExists(),
		// Ticked by default (ADR-0103, decision 3): without the rule another
		// computer cannot dial this one. It still ends in an administrator prompt
		// the person approves, and it runs after every install because the rule
		// names the runtime of one version and every install replaces that runtime.
		allowLink: true,
		action:   installerActionInstall,
		page:     installerPageAction,
		controls: make(map[int]uintptr),
	}, nil
}

// installerBundleVersion reads the version a bundle declares about itself. It is
// display text only: activation verifies the bundle through its manifest, never
// through this file.
func installerBundleVersion(bundle string) string {
	data, err := os.ReadFile(filepath.Join(bundle, "VERSION"))
	if err != nil {
		return ""
	}
	return plainVersion(string(data))
}

// plainVersion keeps a declared version only when it is a plain version: the
// value is painted into the window.
func plainVersion(declared string) string {
	version := strings.TrimSpace(declared)
	if !releasemanifest.ValidVersion(version) {
		return ""
	}
	return version
}

// installerInstalledVersion reports the active version of an existing
// installation, or "" when none can be read. A missing or damaged pointer only
// removes the version from the copy; install and repair handle it themselves.
func installerInstalledVersion(paths layout.Paths) string {
	pointer, _, err := activepointer.ReadLoose(paths, paths.Current)
	if err != nil || pointer == nil {
		return ""
	}
	return pointer.Version
}

// discoverWizardInstallRoot uses only the installer-owned registry marker to
// find a custom installation. It never scans arbitrary directories.
func discoverWizardInstallRoot(defaultRoot string) string {
	store := protocolreg.UserStore{}
	managed, err := store.Get(protocolreg.RootKey, protocolreg.ManagedName)
	if err != nil || managed != "1" {
		return defaultRoot
	}
	root, err := store.Get(protocolreg.RootKey, protocolreg.InstallName)
	if err != nil {
		return defaultRoot
	}
	paths, err := layout.Resolve(root)
	if err != nil || !installfs.FileExists(paths.Root) {
		return defaultRoot
	}
	return paths.Root
}

func (w *installerWizard) run() error {
	if err := w.createWindow(); err != nil {
		return err
	}
	defer installerWindows.Delete(w.hwnd)
	win32.PostMessage.Call(w.hwnd, installerMessageShow, 0, 0)
	return w.messageLoop()
}

func (w *installerWizard) goBack() {
	if w.busy {
		return
	}
	switch w.page {
	case installerPageInstall, installerPageUninstall:
		if w.page == installerPageInstall {
			// Preserve an edited path even when returning before validation.
			w.installDraft = win32.GetText(w.controls[controlInstallEdit])
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
			win32.ShowMessage(w.hwnd, "Choose Install Location", err.Error(), win32.MbIconError)
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
	value := strings.TrimSpace(win32.GetText(w.controls[controlInstallEdit]))
	if value == "" {
		return "", errors.New("choose an installation folder")
	}
	paths, err := layout.Resolve(value)
	if err != nil {
		return "", err
	}
	if err := installRootOutsideBundle(w.bundle, paths.Root); err != nil {
		return "", err
	}
	if info, statErr := os.Stat(paths.Root); statErr == nil && !info.IsDir() {
		return "", errors.New("the installation path is not a folder")
	}
	return paths.Root, nil
}

// allowLinkSessions is injectable so wizard tests never change the real
// firewall or ask for elevation.
var allowLinkSessions = install.AllowLink

func desktopShortcutExists() bool {
	desktop, err := uishortcut.Desktop()
	return err == nil && uishortcut.Exists(desktop)
}

// addDesktopShortcut writes the Synesis UI shortcut for an installation and
// returns what to tell the user when it could not.
func addDesktopShortcut(root string) string {
	paths, err := layout.Resolve(root)
	if err == nil {
		var desktop string
		if desktop, err = uishortcut.Desktop(); err == nil {
			err = uishortcut.Create(desktop, paths.Activation)
		}
	}
	if err != nil {
		return "The Synesis UI desktop shortcut could not be created: " + err.Error()
	}
	return ""
}

// installRootOutsideBundle keeps an installation from overwriting, or being
// built from inside, the bundle it is installed from. A bundle that is still
// being unpacked is unknown here and is checked again once it is.
func installRootOutsideBundle(bundle, root string) error {
	if bundle != "" && (payload.PathWithin(bundle, root) || payload.PathWithin(root, bundle)) {
		return errors.New("choose an installation folder outside the installer bundle")
	}
	return nil
}

func (w *installerWizard) handleCommand(id uint16, notification uint16) {
	if notification != win32.BnClicked || w.busy {
		return
	}
	switch int(id) {
	case idOK:
		focused, _, _ := win32.GetFocus.Call()
		if target := w.enterCommand(w.installerControlID(focused)); target != idOK {
			w.handleCommand(uint16(target), win32.BnClicked)
		} else if w.page != installerPageUninstall {
			w.goNext()
		}
	case idCancel:
		w.closeOrWarn()
	case controlResultDetailsToggle:
		w.toggleResultDetails()
	case controlActionInstall:
		w.selectAction(installerActionInstall)
	case controlActionUninstall:
		if w.installed {
			w.selectAction(installerActionUninstall)
		}
	case controlMetadata:
		w.metadata = !w.metadata
		win32.InvalidateRect.Call(w.controls[controlMetadata], 0, 0)
	case controlShortcut:
		w.shortcut = !w.shortcut
		win32.InvalidateRect.Call(w.controls[controlShortcut], 0, 0)
	case controlAllowLink:
		w.allowLink = !w.allowLink
		win32.InvalidateRect.Call(w.controls[controlAllowLink], 0, 0)
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

func (w *installerWizard) browse() {
	selected, err := folderpicker.Pick(w.hwnd, "Choose where to install Synesis")
	if errors.Is(err, folderpicker.ErrCancelled) {
		return
	}
	if err != nil {
		win32.ShowMessage(w.hwnd, "Choose Install Location", err.Error(), win32.MbIconError)
		return
	}
	win32.SetText(w.controls[controlInstallEdit], selected)
}

// enterCommand is the command Enter stands for while the given control has the
// focus. The wizard's buttons are owner-drawn, which the dialog keyboard
// interface does not recognise as push buttons: it reports Enter as idOK
// wherever the focus is. A focused button must act as itself; anywhere else
// Enter moves forward, except on the uninstall page, whose destructive button
// has to be chosen.
func (w *installerWizard) enterCommand(focused int) int {
	switch focused {
	case controlBack, controlNext, controlCancel, controlBrowse, controlResultDetailsToggle:
		return focused
	}
	return idOK
}

// The identifiers the dialog keyboard interface sends for Enter and Escape.
const (
	idOK     = 1
	idCancel = 2
)
