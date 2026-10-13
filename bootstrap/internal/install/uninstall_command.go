package install

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/daemonstop"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/uishortcut"
	"synesis/bootstrap/internal/protocol"
)

// unregisterProtocolForUninstall is injectable so disposable bootstrap tests
// never mutate or depend on the developer's real per-user registry. Production
// code retains the ownership-checked implementation by default.
var unregisterProtocolForUninstall = protocol.Unregister

// stopInstalledProcesses stops the daemon and everything else running from the
// installation. It is injectable so disposable tests never stop the
// developer's real processes.
var stopInstalledProcesses = daemonstop.StopAll

// removeUIShortcutForUninstall removes the desktop shortcut the wizard can add.
// It is injectable so disposable tests never touch the developer's desktop.
var removeUIShortcutForUninstall = func() error {
	desktop, err := uishortcut.Desktop()
	if err != nil {
		return nil // no desktop shortcut exists on this platform
	}
	return uishortcut.Remove(desktop)
}

// RunUninstall implements the uninstall command.
func RunUninstall(args []string, observers ...OperationProgress) error {
	flags := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	removeMetadata := flags.Bool("remove-metadata", false, "also remove installer-owned metadata and identity")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	if filepath.Base(paths.Root) != "Synesis" && *installDir == "" {
		return errors.New("refusing unexpected installation root")
	}
	ReportOperation(observers, "shell", "working", "Removing owned protocol and PATH integration")
	if runtime.GOOS == "windows" {
		if err := unregisterProtocolForUninstall(paths); err != nil {
			return err
		}
	}
	if err := pathUpdater(paths, false); err != nil {
		return err
	}
	if err := removeUIShortcutForUninstall(); err != nil {
		return err
	}
	ReportOperation(observers, "shell", "done", "Owned shell integration removed")
	ReportOperation(observers, "runtime", "working", "Removing application files")
	// A running daemon holds the application files open and would outlive them.
	if err := stopInstalledProcesses(paths.Root); err != nil {
		return err
	}
	if err := removeInstalledPayload(paths); err != nil {
		return err
	}
	ReportOperation(observers, "runtime", "done", "Application files removed")
	if *removeMetadata {
		ReportOperation(observers, "metadata", "working", "Removing installer-owned identity and state")
		if err := removeInstallerMetadata(paths); err != nil {
			return err
		}
		ReportOperation(observers, "metadata", "done", "Installer-owned metadata removed")
	} else {
		ReportOperation(observers, "metadata", "done", "Installer-owned metadata preserved")
	}
	ReportOperation(observers, "cleanup", "working", "Cleaning obsolete installer files")
	if err := installfs.RemoveInstallationTree(paths.Rollback); err != nil {
		return err
	}
	entries, err := os.ReadDir(filepath.Dir(paths.Root))
	if errors.Is(err, os.ErrNotExist) {
		ReportOperation(observers, "cleanup", "done", "Cleanup complete; project and workspace data preserved")
		fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true")
		return nil
	}
	if err != nil {
		return err
	}
	if err := removeEmptyInstallationRoot(paths.Root); err != nil {
		return err
	}
	prefix := filepath.Base(paths.Root) + ".staging-"
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), prefix) {
			if err := os.RemoveAll(filepath.Join(filepath.Dir(paths.Root), entry.Name())); err != nil {
				return err
			}
		}
	}
	fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true", "METADATA_REMOVED="+strconv.FormatBool(*removeMetadata))
	ReportOperation(observers, "cleanup", "done", "Cleanup complete; project and workspace data preserved")
	return nil
}

// removeInstallerMetadata removes only metadata owned by the installer. User
// project, repository, workspace, and other unrecognized entries remain in
// place even when the operator requests metadata removal.
func removeInstallerMetadata(paths layout.Paths) error {
	for _, path := range []string{
		paths.Admin,
		filepath.Join(paths.Root, "Link"),
	} {
		// A file can stay open for a moment after its process has gone, or
		// while a scanner reads it.
		err := installfs.RemoveInstallationTree(path)
		for attempt := 0; err != nil && attempt < 20; attempt++ {
			time.Sleep(150 * time.Millisecond)
			err = installfs.RemoveInstallationTree(path)
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// removeEmptyInstallationRoot removes the now-empty application root without
// touching user-owned data that may have been placed below that root.
func removeEmptyInstallationRoot(root string) error {
	entries, err := os.ReadDir(root)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if len(entries) == 0 {
		return os.Remove(root)
	}
	return nil
}

// removeInstalledPayload removes only application binaries and version
// payloads. The Link identity and bootstrap admin records are installer-owned
// metadata and remain available for a later reinstall unless the user opts in
// to --remove-metadata.
func removeInstalledPayload(paths layout.Paths) error {
	for _, path := range []string{
		paths.Bin,
		filepath.Join(paths.Root, "app"),
		filepath.Join(paths.Root, "runtime"),
		paths.Versions,
		paths.Current,
		paths.Previous,
		filepath.Join(paths.Root, "current"),
		filepath.Join(paths.Root, "VERSION"),
		filepath.Join(paths.Root, "README.md"),
		filepath.Join(paths.Root, "LICENSE"),
		filepath.Join(paths.Root, "manifest.json"),
	} {
		if err := installfs.RemoveInstallationTree(path); err != nil {
			return err
		}
	}
	return nil
}
