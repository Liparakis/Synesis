//go:build windows

package wizard

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
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
	art               map[string]draw.Art
	bundle            string
	bundleVersion     string
	installedVersion  string
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
	progressMotion    draw.ProgressMotion
	progressEpoch     time.Time
	progressComplete  time.Time
	progressRaster    *draw.Raster
	progressTimer     uintptr
	smoothIcons       map[string]*draw.Raster
	backdrop          *draw.Raster
	backdropBrush     uintptr
	resultDetailsOpen bool
	executeOperation  func(string, string, installerAction, bool, install.OperationProgress) installerOperationResult
	titleFont         uintptr
	bodyFont          uintptr
	detailFont        uintptr
	productFont       uintptr
	buttonFont        uintptr
	cardTitleFont     uintptr
	cardDetailFont    uintptr
	stepDetailFont    uintptr
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

// Run opens the native Windows setup wizard for a local bundle.
// The self-extracting wrapper delegates into this same entry point after it
// has unpacked its verified bundle into a temporary directory.
func Run() error {
	// Win32 windows and their child controls are thread-affine. Keep the Go
	// goroutine on the creating OS thread for the entire message-loop lifetime.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	bundle, err := bundlecheck.RunningRoot()
	if err != nil {
		win32.ShowMessage(0, "Synesis Setup", err.Error(), win32.MbIconError)
		return nil
	}
	wizard, err := newInstallerWizard(bundle)
	if err != nil {
		win32.ShowMessage(0, "Synesis Setup", err.Error(), win32.MbIconError)
		return nil
	}
	if err := wizard.run(); err != nil {
		win32.ShowMessage(wizard.hwnd, "Synesis Setup", err.Error(), win32.MbIconError)
	}
	return nil
}

func newInstallerWizard(bundle string) (*installerWizard, error) {
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
		bundleVersion:    installerBundleVersion(bundle),
		installedVersion: installerInstalledVersion(paths),
		installRoot:      paths.Root,
		installed:        installfs.FileExists(paths.Root),
		action:           installerActionInstall,
		page:             installerPageAction,
		controls:         make(map[int]uintptr),
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
	version := strings.TrimSpace(string(data))
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
	if payload.PathWithin(w.bundle, paths.Root) || payload.PathWithin(paths.Root, w.bundle) {
		return "", errors.New("choose an installation folder outside the installer bundle")
	}
	if info, statErr := os.Stat(paths.Root); statErr == nil && !info.IsDir() {
		return "", errors.New("the installation path is not a folder")
	}
	return paths.Root, nil
}

func (w *installerWizard) handleCommand(id uint16, notification uint16) {
	if notification != win32.BnClicked || w.busy {
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
		win32.InvalidateRect.Call(w.controls[controlMetadata], 0, 1)
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
	selected, err := browseInstallerFolder(w.bundle)
	if errors.Is(err, errInstallerBrowseCancelled) {
		return
	}
	if err != nil {
		win32.ShowMessage(w.hwnd, "Choose Install Location", err.Error(), win32.MbIconError)
		return
	}
	win32.SetText(w.controls[controlInstallEdit], selected)
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

var errInstallerBrowseCancelled = errors.New("folder selection cancelled")
