// Package layout resolves the on-disk layout of one Synesis installation root
// and the platform-specific launcher names inside it.
package layout

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
)

// Paths is the resolved on-disk layout for one installation root.
type Paths struct {
	Root, Bin, Launcher, Activation, Rollback, Versions, Current, Previous, Admin, Lock, Plans, Executions string
}

// Resolve resolves the on-disk layout for a Synesis installation.
// If explicit is empty, the root follows OS convention (LOCALAPPDATA on
// Windows, XDG_DATA_HOME or ~/.local/share on Linux, ~/Library/Application
// Support on macOS).
func Resolve(explicit string) (Paths, error) {
	root := explicit
	if root == "" {
		base, err := defaultInstallBase()
		if err != nil || base == "" {
			return Paths{}, errors.New("unable to determine installation root")
		}
		root = filepath.Join(base, "Synesis")
	}
	root, err := filepath.Abs(root)
	if err != nil || root == filepath.VolumeName(root)+string(filepath.Separator) {
		return Paths{}, errors.New("unsafe installation root")
	}

	launcherName := "synesis"
	if runtime.GOOS == "windows" {
		launcherName = "synesis.cmd"
	}
	bin := filepath.Join(root, "bin")
	admin := filepath.Join(root, "admin")
	return Paths{
		Root: root, Bin: bin, Launcher: filepath.Join(bin, launcherName),
		Activation: filepath.Join(bin, "synesis-activate.exe"), Rollback: root + ".rollback",
		Versions: filepath.Join(root, "versions"), Current: filepath.Join(root, "current.json"),
		Previous: filepath.Join(root, "previous.json"), Admin: admin,
		Lock: filepath.Join(admin, "update-lock.json"), Plans: filepath.Join(admin, "update-plans"),
		Executions: filepath.Join(admin, "update-executions"),
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

// LauncherName is the file name of the stable CLI launcher on this platform.
func LauncherName() string {
	if runtime.GOOS == "windows" {
		return "synesis.cmd"
	}
	return "synesis"
}

// MCPLauncherName is the file name of the native MCP launcher on this platform.
func MCPLauncherName() string {
	if runtime.GOOS == "windows" {
		return "synesis-mcp.exe"
	}
	return "synesis-mcp"
}
