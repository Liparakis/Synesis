package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

// runActivation is the native protocol-handler boundary. It accepts exactly
// one OS-provided argument and launches the active Java CLI without a shell.
// URI semantics and validation remain entirely inside the CLI daemon.
func runActivation(args []string) error {
	if runtime.GOOS != "windows" {
		return errors.New("native Synesis activation is supported only on Windows")
	}
	if len(args) != 1 || args[0] == "" {
		return errors.New("activation requires exactly one URI argument")
	}
	executable, err := os.Executable()
	if err != nil {
		return errors.New("unable to locate installed activation helper")
	}
	executable, err = filepath.EvalSymlinks(executable)
	if err != nil {
		return errors.New("unable to resolve installed activation helper")
	}
	root := filepath.Dir(filepath.Dir(executable))
	paths, err := installationPaths(root)
	if err != nil {
		return err
	}
	pointer, _, err := readPointer(paths, paths.current)
	if err != nil || pointer == nil {
		return errors.New("installed Synesis pointer is unavailable")
	}
	payload := filepath.Join(paths.versions, pointer.PayloadDirectory)
	java := filepath.Join(payload, "runtime", "bin", "java.exe")
	classpath := filepath.Join(payload, "app", "synesis-cli.jar") + string(os.PathListSeparator) +
		filepath.Join(payload, "app", "lib", "*")
	command := exec.Command(java, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
		"org.synesis.cli.SynesisCli", "open", args[0])
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Stdin = os.Stdin
	command.Env = append(os.Environ(), "SYNESIS_LAUNCHER="+
		filepath.Join(payload, "bin", "synesis.cmd"))
	if err := command.Run(); err != nil {
		return fmt.Errorf("activation command failed: %w", err)
	}
	return nil
}
