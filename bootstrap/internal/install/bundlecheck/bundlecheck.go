// Package bundlecheck validates an extracted Synesis bundle and runs its
// launcher for the version and doctor checks.
package bundlecheck

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"synesis/bootstrap/internal/childproc"
	"synesis/bootstrap/internal/install/layout"
)

// Validate checks that an extracted bundle has the expected shape
// and that its embedded launcher actually runs before it's ever activated.
func Validate(bundle string) error {
	if err := validateShape(bundle); err != nil {
		return err
	}
	return runBundleVersion(bundle)
}

func validateShape(bundle string) error {
	for _, relative := range []string{"bin", "runtime"} {
		info, err := os.Stat(filepath.Join(bundle, relative))
		if err != nil || !info.IsDir() {
			return fmt.Errorf("bundle missing %s", relative)
		}
	}
	version, err := os.Stat(filepath.Join(bundle, "VERSION"))
	if err != nil || !version.Mode().IsRegular() {
		return errors.New("bundle missing VERSION")
	}
	launcher := filepath.Join(bundle, "bin", layout.LauncherName())
	if info, err := os.Stat(launcher); err != nil || !info.Mode().IsRegular() {
		return errors.New("bundle missing stable launcher")
	}
	mcpLauncher := filepath.Join(bundle, "bin", layout.MCPLauncherName())
	if info, err := os.Stat(mcpLauncher); err != nil || !info.Mode().IsRegular() {
		return errors.New("bundle missing MCP launcher")
	}
	if runtime.GOOS == "windows" {
		installer := filepath.Join(bundle, "bin", "synesis-installer.exe")
		if info, err := os.Stat(installer); err != nil || !info.Mode().IsRegular() {
			return errors.New("bundle missing native activation helper")
		}
	}
	return nil
}

func runBundleVersion(bundle string) error {
	output, err := runBundleCommand(bundle, "version")
	if err != nil || !bytes.Contains(output, []byte("SYNESIS_VERSION=")) {
		return errors.New("bundled synesis version check failed")
	}
	return nil
}

// RunDoctor runs the bundle's own doctor command and checks its report.
func RunDoctor(bundle string) error {
	output, err := runBundleCommand(bundle, "doctor")
	if err != nil || !bytes.Contains(output, []byte("DOCTOR")) {
		return errors.New("bundled synesis doctor check failed")
	}
	return nil
}

// ValidateVersion validates the bundle and checks that its VERSION is expected.
func ValidateVersion(bundle, expected string) error {
	if err := Validate(bundle); err != nil {
		return err
	}
	data, err := os.ReadFile(filepath.Join(bundle, "VERSION"))
	if err != nil || strings.TrimSpace(string(data)) != expected {
		return errors.New("bundle VERSION does not match manifest")
	}
	return nil
}

func runBundleCommand(bundle string, args ...string) ([]byte, error) {
	launcher := filepath.Join(bundle, "bin", layout.LauncherName())
	command := []string{launcher}
	if runtime.GOOS == "windows" {
		command = []string{"cmd.exe", "/d", "/c", "call", launcher}
	}
	command = append(command, args...)
	process := exec.Command(command[0], command[1:]...)
	childproc.Hide(process)
	return process.CombinedOutput()
}

// RunningRoot returns the validated bundle that contains the running installer.
func RunningRoot() (string, error) {
	return runningRoot(Validate)
}

// RunningRootShape is RunningRoot without starting the bundled launcher, which
// costs a runtime start. It is for a caller that only offers the bundle to an
// operation: install and repair validate the bundle in full before activating it.
func RunningRootShape() (string, error) {
	return runningRoot(validateShape)
}

func runningRoot(validate func(string) error) (string, error) {
	executable, err := os.Executable()
	if err != nil {
		return "", errors.New("unable to locate installer bundle")
	}
	executable, err = filepath.EvalSymlinks(executable)
	if err != nil {
		return "", errors.New("unable to resolve installer bundle")
	}
	bundle := filepath.Dir(filepath.Dir(executable))
	if err := validate(bundle); err != nil {
		return "", fmt.Errorf("installer is not inside a valid Synesis bundle: %w", err)
	}
	return bundle, nil
}
