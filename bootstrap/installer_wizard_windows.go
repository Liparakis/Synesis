//go:build windows

package main

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

	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/protocolreg"
	"synesis/bootstrap/internal/releasemanifest"
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
	art               map[string]installerArt
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
		bundle:           bundle,
		bundleVersion:    installerBundleVersion(bundle),
		installedVersion: installerInstalledVersion(paths),
		installRoot:      paths.root,
		installed:        fileExists(paths.root),
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
func installerInstalledVersion(paths installPaths) string {
	pointer, _, err := readLoosePointer(paths, paths.current)
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
	if payload.PathWithin(w.bundle, paths.root) || payload.PathWithin(paths.root, w.bundle) {
		return "", errors.New("choose an installation folder outside the installer bundle")
	}
	if info, statErr := os.Stat(paths.root); statErr == nil && !info.IsDir() {
		return "", errors.New("the installation path is not a folder")
	}
	return paths.root, nil
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
