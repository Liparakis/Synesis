package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

func runUninstall(args []string, observers ...operationProgress) error {
	flags := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	removeMetadata := flags.Bool("remove-metadata", false, "also remove installer-owned metadata and identity")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if filepath.Base(paths.root) != "Synesis" && *installDir == "" {
		return errors.New("refusing unexpected installation root")
	}
	reportOperation(observers, "shell", "working", "Removing owned protocol and PATH integration")
	if runtime.GOOS == "windows" {
		if err := unregisterProtocolForUninstall(paths); err != nil {
			return err
		}
	}
	if err := pathUpdater(paths, false); err != nil {
		return err
	}
	reportOperation(observers, "shell", "done", "Owned shell integration removed")
	reportOperation(observers, "runtime", "working", "Removing application files")
	if err := removeInstalledPayload(paths); err != nil {
		return err
	}
	reportOperation(observers, "runtime", "done", "Application files removed")
	if *removeMetadata {
		reportOperation(observers, "metadata", "working", "Removing installer-owned identity and state")
		if err := removeInstallerMetadata(paths); err != nil {
			return err
		}
		reportOperation(observers, "metadata", "done", "Installer-owned metadata removed")
	} else {
		reportOperation(observers, "metadata", "done", "Installer-owned metadata preserved")
	}
	reportOperation(observers, "cleanup", "working", "Cleaning obsolete installer files")
	if err := removeInstallationTree(paths.rollback); err != nil {
		return err
	}
	entries, err := os.ReadDir(filepath.Dir(paths.root))
	if errors.Is(err, os.ErrNotExist) {
		reportOperation(observers, "cleanup", "done", "Cleanup complete; project and workspace data preserved")
		fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true")
		return nil
	}
	if err != nil {
		return err
	}
	if err := removeEmptyInstallationRoot(paths.root); err != nil {
		return err
	}
	prefix := filepath.Base(paths.root) + ".staging-"
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), prefix) {
			if err := os.RemoveAll(filepath.Join(filepath.Dir(paths.root), entry.Name())); err != nil {
				return err
			}
		}
	}
	fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true", "METADATA_REMOVED="+strconv.FormatBool(*removeMetadata))
	reportOperation(observers, "cleanup", "done", "Cleanup complete; project and workspace data preserved")
	return nil
}

// removeInstallerMetadata removes only metadata owned by the installer. User
// project, repository, workspace, and other unrecognized entries remain in
// place even when the operator requests metadata removal.
func removeInstallerMetadata(paths installPaths) error {
	for _, path := range []string{
		paths.admin,
		filepath.Join(paths.root, "Link"),
	} {
		if err := removeInstallationTree(path); err != nil {
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
func removeInstalledPayload(paths installPaths) error {
	for _, path := range []string{
		paths.bin,
		filepath.Join(paths.root, "app"),
		filepath.Join(paths.root, "runtime"),
		paths.versions,
		paths.current,
		paths.previous,
		filepath.Join(paths.root, "current"),
		filepath.Join(paths.root, "VERSION"),
		filepath.Join(paths.root, "README.md"),
		filepath.Join(paths.root, "LICENSE"),
		filepath.Join(paths.root, "manifest.json"),
	} {
		if err := removeInstallationTree(path); err != nil {
			return err
		}
	}
	return nil
}
