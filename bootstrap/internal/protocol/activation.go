package protocol

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"synesis/bootstrap/internal/childproc"
	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/layout"
)

// RunActivation is the native protocol-handler boundary. It accepts exactly
// one OS-provided argument and launches the active Java CLI without a shell.
// URI semantics and validation remain entirely inside the CLI daemon.
func RunActivation(args []string) error {
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
	paths, err := layout.Resolve(root)
	if err != nil {
		return err
	}
	pointer, _, err := activepointer.Read(paths, paths.Current)
	if err != nil || pointer == nil {
		return errors.New("installed Synesis pointer is unavailable")
	}
	payload := filepath.Join(paths.Versions, pointer.PayloadDirectory)
	java := filepath.Join(payload, "runtime", "bin", "java.exe")
	classpath := filepath.Join(payload, "app", "synesis-cli.jar") + string(os.PathListSeparator) +
		filepath.Join(payload, "app", "lib", "*")
	javaArgs := []string{"--enable-native-access=ALL-UNNAMED"}
	if crashFile := layout.CrashFileOption(); crashFile != "" {
		javaArgs = append(javaArgs, crashFile)
	}
	javaArgs = append(javaArgs, "-cp", classpath, "org.synesis.cli.SynesisCli", "open", args[0])
	command := exec.Command(java, javaArgs...)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Stdin = os.Stdin
	childproc.Hide(command)
	command.Env = append(os.Environ(), "SYNESIS_LAUNCHER="+
		filepath.Join(payload, "bin", "synesis.cmd"))
	if err := command.Run(); err != nil {
		showFailure("Synesis could not open this link.\n\nRun \"synesis ui\" in a terminal to see the reason.")
		return fmt.Errorf("activation command failed: %w", err)
	}
	return nil
}
