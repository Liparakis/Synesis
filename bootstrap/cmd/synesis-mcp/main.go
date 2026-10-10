// Command synesis-mcp is the native launcher for the Synesis Java runtime. It
// starts the runtime without a shell wrapper. Installed as synesis-mcp it is
// the stdio launcher used by provider MCP registrations; the same binary
// installed as synesis is the command people type, where it additionally
// applies the checks the script launcher performs before running a command.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"

	"synesis/bootstrap/internal/payload"
)

const synesisMainClass = "org.synesis.cli.SynesisCli"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "SYNESIS_MCP_LAUNCH_ERROR="+err.Error())
		os.Exit(1)
	}
}

func run(args []string) error {
	executable, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve launcher: %w", err)
	}
	layout, err := resolveLayout(executable)
	if err != nil {
		return err
	}
	java, err := resolveJava(layout.javaPath)
	if err != nil {
		return err
	}
	environment := os.Environ()
	if commandRole(executable) {
		if err := requireReleaseHealth(layout); err != nil {
			return err
		}
		environment = withVersionedLauncher(environment, layout)
	}
	commandArgs := []string{"--enable-native-access=ALL-UNNAMED", "-cp", layout.classpath, synesisMainClass}
	commandArgs = append(commandArgs, args...)
	command := exec.Command(java, commandArgs...)
	command.Stdin = os.Stdin
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Env = environment
	// Preserve the provider's project working directory. The launcher layout
	// supplies the runtime/classpath, but must never change the caller's cwd:
	// Synesis uses that cwd (or MCP roots) to resolve the active local project.
	// This is essential when one installed launcher serves multiple projects.
	if cwd, cwdErr := os.Getwd(); cwdErr == nil {
		command.Dir = cwd
	} else {
		command.Dir = layout.workingDirectory
	}

	if err := command.Start(); err != nil {
		return fmt.Errorf("start Java runtime: %w", err)
	}
	forwardSignals(command)
	if err := command.Wait(); err != nil {
		var exitError *exec.ExitError
		if errors.As(err, &exitError) {
			os.Exit(exitError.ExitCode())
		}
		return fmt.Errorf("wait for Java runtime: %w", err)
	}
	return nil
}

type runtimeLayout struct {
	classpath        string
	workingDirectory string
	javaPath         string
	// installRoot and protectionProfile are set only when an active version
	// pointer selected the payload; a development layout has neither.
	installRoot       string
	protectionProfile string
}

const maximumReleaseProfile = "maximum-release"

// commandRole reports whether this binary was installed as the command people
// type rather than as the provider-facing MCP launcher.
func commandRole(executable string) bool {
	name := strings.ToLower(filepath.Base(executable))
	return strings.TrimSuffix(name, ".exe") == "synesis"
}

// requireReleaseHealth refuses to run a maximum-release installation that its
// own doctor does not accept. The script launcher this binary replaces makes
// the same check before every command, so it must not be lost by going native.
func requireReleaseHealth(layout runtimeLayout) error {
	if layout.installRoot == "" {
		return nil
	}
	profile := layout.protectionProfile
	if profile != maximumReleaseProfile {
		data, err := os.ReadFile(filepath.Join(layout.workingDirectory, "PROTECTION_PROFILE"))
		if err == nil {
			profile = strings.TrimSpace(string(data))
		} else if !errors.Is(err, os.ErrNotExist) {
			return errors.New("protection profile unreadable")
		}
	}
	if profile != maximumReleaseProfile {
		return nil
	}
	doctor := filepath.Join(layout.workingDirectory, "bin", payload.InstallerName())
	if !fileExists(doctor) {
		return errors.New("installation doctor missing")
	}
	check := exec.Command(doctor, "doctor", "--install-dir", layout.installRoot)
	check.Stderr = os.Stderr
	if err := check.Run(); err != nil {
		return errors.New("installation doctor rejected this installation")
	}
	return nil
}

// withVersionedLauncher tells the runtime which versioned launcher started it,
// exactly as the versioned launcher scripts do, unless the caller already did.
func withVersionedLauncher(environment []string, layout runtimeLayout) []string {
	if layout.installRoot == "" || os.Getenv("SYNESIS_LAUNCHER") != "" {
		return environment
	}
	name := "synesis"
	if runtime.GOOS == "windows" {
		name = "synesis.cmd"
	}
	return append(environment, "SYNESIS_LAUNCHER="+filepath.Join(layout.workingDirectory, "bin", name))
}

func resolveLayout(executable string) (runtimeLayout, error) {
	bin := filepath.Dir(executable)
	root := filepath.Dir(bin)
	activeRoot, profile, err := resolveActivePayload(root)
	if err != nil {
		return runtimeLayout{}, err
	}
	installRoot := ""
	if activeRoot != root {
		installRoot = root
	}
	root = activeRoot
	if runtimeClasspath := filepath.Join(root, "runtime", "bin", javaName()); fileExists(runtimeClasspath) {
		appJar := filepath.Join(root, "app", "synesis-cli.jar")
		libGlob := filepath.Join(root, "app", "lib", "*")
		if !fileExists(appJar) {
			return runtimeLayout{}, fmt.Errorf("bundled CLI jar missing: %s", appJar)
		}
		return runtimeLayout{classpath: appJar + string(os.PathListSeparator) + libGlob,
			workingDirectory: root, javaPath: runtimeClasspath, installRoot: installRoot, protectionProfile: profile}, nil
	}
	libGlob := filepath.Join(root, "lib", "*")
	if !directoryExists(filepath.Join(root, "lib")) {
		return runtimeLayout{}, fmt.Errorf("Synesis lib directory missing: %s", filepath.Join(root, "lib"))
	}
	return runtimeLayout{classpath: libGlob, workingDirectory: root, installRoot: installRoot, protectionProfile: profile}, nil
}

// resolveActivePayload makes the provider-facing launcher follow the same
// verified version pointer as the stable CLI launcher. The native MCP binary
// is intentionally kept stable so provider configuration does not change on
// every update, but the Java runtime it starts must come from the active
// versioned payload.
func resolveActivePayload(root string) (string, string, error) {
	pointerPath := filepath.Join(root, "current.json")
	data, err := os.ReadFile(pointerPath)
	if errors.Is(err, os.ErrNotExist) {
		return root, "", nil
	}
	if err != nil {
		return "", "", fmt.Errorf("read active pointer: %w", err)
	}
	var pointer struct {
		SchemaVersion     int    `json:"schemaVersion"`
		Version           string `json:"version"`
		PayloadDirectory  string `json:"payloadDirectory"`
		ManifestHash      string `json:"manifestHash"`
		ProtectionProfile string `json:"protectionProfile"`
	}
	if err := json.Unmarshal(data, &pointer); err != nil ||
		pointer.SchemaVersion != 1 || pointer.Version == "" ||
		!payload.ValidDirectory(pointer.PayloadDirectory) || pointer.ManifestHash == "" {
		return "", "", errors.New("active pointer invalid")
	}
	versionsRoot := filepath.Join(root, "versions")
	payloadRoot := filepath.Join(versionsRoot, pointer.PayloadDirectory)
	if !payload.PathWithin(versionsRoot, payloadRoot) || !directoryExists(payloadRoot) {
		return "", "", errors.New("active pointer payload invalid")
	}
	manifestPath := filepath.Join(payloadRoot, "manifest.json")
	manifest, err := os.ReadFile(manifestPath)
	if err != nil || payload.Digest(manifest) != pointer.ManifestHash {
		return "", "", errors.New("active payload manifest invalid")
	}
	return payloadRoot, pointer.ProtectionProfile, nil
}

func resolveJava(bundled string) (string, error) {
	if bundled != "" && fileExists(bundled) {
		return bundled, nil
	}
	if configured := os.Getenv("SYNESIS_JAVA"); configured != "" {
		if fileExists(configured) {
			return configured, nil
		}
		return "", fmt.Errorf("SYNESIS_JAVA does not exist: %s", configured)
	}
	if home := os.Getenv("JAVA_HOME"); home != "" {
		candidate := filepath.Join(home, "bin", javaName())
		if fileExists(candidate) {
			return candidate, nil
		}
	}
	if java, err := exec.LookPath(javaName()); err == nil {
		return java, nil
	}
	return "", errors.New("Java 25 runtime not found; set JAVA_HOME or SYNESIS_JAVA")
}

func forwardSignals(command *exec.Cmd) {
	channel := make(chan os.Signal, 2)
	signal.Notify(channel, os.Interrupt, syscall.SIGTERM)
	go func() {
		for received := range channel {
			_ = command.Process.Signal(received)
		}
	}()
}

func javaName() string {
	if runtime.GOOS == "windows" {
		return "java.exe"
	}
	return "java"
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func directoryExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}
