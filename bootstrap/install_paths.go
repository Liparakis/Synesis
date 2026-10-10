package main

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
)

// installPaths is the resolved on-disk layout for one installation root.
type installPaths struct {
	root, bin, launcher, activation, rollback, versions, current, previous, admin, lock, plans, executions string
}

// installationPaths resolves the on-disk layout for a Synesis installation.
// If explicit is empty, the root follows OS convention (LOCALAPPDATA on
// Windows, XDG_DATA_HOME or ~/.local/share on Linux, ~/Library/Application
// Support on macOS).
func installationPaths(explicit string) (installPaths, error) {
	root := explicit
	if root == "" {
		base, err := defaultInstallBase()
		if err != nil || base == "" {
			return installPaths{}, errors.New("unable to determine installation root")
		}
		root = filepath.Join(base, "Synesis")
	}
	root, err := filepath.Abs(root)
	if err != nil || root == filepath.VolumeName(root)+string(filepath.Separator) {
		return installPaths{}, errors.New("unsafe installation root")
	}

	launcherName := "synesis"
	if runtime.GOOS == "windows" {
		launcherName = "synesis.cmd"
	}
	bin := filepath.Join(root, "bin")
	admin := filepath.Join(root, "admin")
	return installPaths{
		root: root, bin: bin, launcher: filepath.Join(bin, launcherName),
		activation: filepath.Join(bin, "synesis-activate.exe"), rollback: root + ".rollback",
		versions: filepath.Join(root, "versions"), current: filepath.Join(root, "current.json"),
		previous: filepath.Join(root, "previous.json"), admin: admin,
		lock: filepath.Join(admin, "update-lock.json"), plans: filepath.Join(admin, "update-plans"),
		executions: filepath.Join(admin, "update-executions"),
	}, nil
}

// defaultInstallBase returns the OS-conventional parent directory for
// application data when --install-dir wasn't given explicitly.
func defaultInstallBase() (string, error) {
	switch runtime.GOOS {
	case "windows":
		if base := os.Getenv("LOCALAPPDATA"); base != "" {
			return base, nil
		}
		return os.UserConfigDir()
	case "linux":
		if base := os.Getenv("XDG_DATA_HOME"); base != "" {
			return base, nil
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, ".local", "share"), nil
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, "Library", "Application Support"), nil
	default:
		return os.UserConfigDir()
	}
}
