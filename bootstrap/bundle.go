package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// validateBundle checks that an extracted bundle has the expected shape
// and that its embedded launcher actually runs before it's ever activated.
func validateBundle(bundle string) error {
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
	launcher := filepath.Join(bundle, "bin", launcherName())
	if info, err := os.Stat(launcher); err != nil || !info.Mode().IsRegular() {
		return errors.New("bundle missing stable launcher")
	}
	mcpLauncher := filepath.Join(bundle, "bin", mcpLauncherName())
	if info, err := os.Stat(mcpLauncher); err != nil || !info.Mode().IsRegular() {
		return errors.New("bundle missing MCP launcher")
	}
	if runtime.GOOS == "windows" {
		installer := filepath.Join(bundle, "bin", "synesis-installer.exe")
		if info, err := os.Stat(installer); err != nil || !info.Mode().IsRegular() {
			return errors.New("bundle missing native activation helper")
		}
	}
	return runBundleVersion(bundle)
}

func launcherName() string {
	if runtime.GOOS == "windows" {
		return "synesis.cmd"
	}
	return "synesis"
}

func mcpLauncherName() string {
	if runtime.GOOS == "windows" {
		return "synesis-mcp.exe"
	}
	return "synesis-mcp"
}

func runBundleVersion(bundle string) error {
	output, err := runBundleCommand(bundle, "version")
	if err != nil || !bytes.Contains(output, []byte("SYNESIS_VERSION=")) {
		return errors.New("bundled synesis version check failed")
	}
	return nil
}

func runBundleDoctor(bundle string) error {
	output, err := runBundleCommand(bundle, "doctor")
	if err != nil || !bytes.Contains(output, []byte("DOCTOR")) {
		return errors.New("bundled synesis doctor check failed")
	}
	return nil
}

func validateBundleVersion(bundle, expected string) error {
	if err := validateBundle(bundle); err != nil {
		return err
	}
	data, err := os.ReadFile(filepath.Join(bundle, "VERSION"))
	if err != nil || strings.TrimSpace(string(data)) != expected {
		return errors.New("bundle VERSION does not match manifest")
	}
	return nil
}

func runBundleCommand(bundle string, args ...string) ([]byte, error) {
	launcher := filepath.Join(bundle, "bin", launcherName())
	command := []string{launcher}
	if runtime.GOOS == "windows" {
		command = []string{"cmd.exe", "/d", "/c", "call", launcher}
	}
	command = append(command, args...)
	return exec.Command(command[0], command[1:]...).CombinedOutput()
}

func runningBundleRoot() (string, error) {
	executable, err := os.Executable()
	if err != nil {
		return "", errors.New("unable to locate installer bundle")
	}
	executable, err = filepath.EvalSymlinks(executable)
	if err != nil {
		return "", errors.New("unable to resolve installer bundle")
	}
	bundle := filepath.Dir(filepath.Dir(executable))
	if err := validateBundle(bundle); err != nil {
		return "", fmt.Errorf("installer is not inside a valid Synesis bundle: %w", err)
	}
	return bundle, nil
}
