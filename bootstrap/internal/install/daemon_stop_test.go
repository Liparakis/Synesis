package install

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
)

// A daemon of the replaced build must not keep serving after an update, and an
// uninstall must not leave one running over removed files.
func TestInstallRepairAndUninstallStopTheDaemonOfTheInstallation(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	installRoot := filepath.Join(root, "install")
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	var stopped []string
	var stopFailure error
	stopDaemon = func(root string) error {
		// By the time the daemon is stopped, the next command must find the new build.
		stopped = append(stopped, root+" active="+map[bool]string{true: "yes", false: "no"}[installfs.FileExists(paths.Current)])
		return stopFailure
	}
	stopInstalledProcesses = func(root string) error { return stopDaemon(root) }
	shortcutRemoved := false
	removeUIShortcutForUninstall = func() error {
		shortcutRemoved = true
		return nil
	}

	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if err := RunRepair([]string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if want := []string{paths.Root + " active=yes", paths.Root + " active=yes"}; strings.Join(stopped, "|") != strings.Join(want, "|") {
		t.Fatalf("daemon stops after install and repair = %v", stopped)
	}

	// A daemon that will not stop is reported, and does not undo an activation that succeeded.
	stopFailure = errors.New("daemon fixture would not stop")
	var events []string
	if err := RunRepair([]string{"--bundle", archive, "--install-dir", installRoot}, func(stage, status, _ string) { events = append(events, stage+":"+status) }); err != nil {
		t.Fatalf("a daemon that would not stop failed the repair: %v", err)
	}
	if !strings.Contains(strings.Join(events, ","), "runtime:warning,runtime:done") {
		t.Fatalf("a daemon that would not stop went unreported: %v", events)
	}

	// Uninstall refuses to remove files a daemon still runs from.
	if err := RunUninstall([]string{"--install-dir", installRoot}); err == nil {
		t.Fatal("uninstall removed the files of a daemon that would not stop")
	}
	if !installfs.FileExists(paths.Current) {
		t.Fatal("a refused uninstall removed application files")
	}
	stopFailure = nil
	stopped, shortcutRemoved = nil, false
	if err := RunUninstall([]string{"--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if len(stopped) != 1 || !shortcutRemoved || installfs.FileExists(paths.Current) {
		t.Fatalf("uninstall: daemon stops = %v, shortcut removed = %v", stopped, shortcutRemoved)
	}
}
