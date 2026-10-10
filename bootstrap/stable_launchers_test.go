package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchiveWithProfile(t, root, "0.1.0", "maximum-release")
	manifest := writeDevelopmentManifest(t, root, "0.1.0", archive)
	installRoot := filepath.Join(root, "install")
	if err := runInstall("install", []string{"--manifest", fileURL(manifest), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := installationPaths(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	pointer, _, err := readPointer(paths, paths.current)
	if err != nil {
		t.Fatal(err)
	}
	if pointer == nil || pointer.ProtectionProfile != "maximum-release" {
		t.Fatalf("maximum protection profile was not recorded in the active pointer: %#v", pointer)
	}
	launcherPath := filepath.Join(paths.bin, "synesis-launcher.ps1")
	if runtime.GOOS != "windows" {
		launcherPath = paths.launcher
	}
	launcher, err := os.ReadFile(launcherPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{"maximum-release", "doctor", "--install-dir"} {
		if !strings.Contains(string(launcher), required) {
			t.Fatalf("stable launcher missing maximum runtime integrity gate %q", required)
		}
	}
}

func TestWindowsStableLauncherPreservesAmpersandArgument(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows stable launcher forwarding is platform-specific")
	}
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	manifest := writeDevelopmentManifest(t, root, "0.1.0", archive)
	installRoot := filepath.Join(root, "install")
	if err := runInstall("install", []string{"--manifest", fileURL(manifest), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := installationPaths(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(paths.bin, "synesis-launcher.ps1")
	output, err := exec.Command(
		"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launcher,
		"echo-arg", "foo&bar").CombinedOutput()
	if err != nil {
		t.Fatalf("stable launcher rejected a quoted ampersand argument: %v launcher=%s\n%s", err, launcher, output)
	}
	if !strings.Contains(string(output), "ARG=\"foo&bar\"") {
		t.Fatalf("stable launcher did not preserve the ampersand argument: %s", output)
	}
}

func TestStableCommandLauncherIsInstalledAndNeverFailsActivation(t *testing.T) {
	bin := t.TempDir()
	paths := installPaths{bin: bin}
	target := filepath.Join(bin, "synesis.exe")
	if err := syncStableCommandLauncher(paths, []byte("launcher-one")); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(target); err != nil || string(data) != "launcher-one" {
		t.Fatalf("launcher = %q, %v", data, err)
	}
	if err := syncStableCommandLauncher(paths, []byte("launcher-two")); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(target); string(data) != "launcher-two" {
		t.Fatalf("launcher was not updated: %q", data)
	}
	// Only a launcher that already exists and works may be left unreplaced.
	// Something unreadable in its place is not a launcher, so the failure to
	// install one there must surface.
	if err := os.Remove(target); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(target, "held"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := syncStableCommandLauncher(paths, []byte("launcher-three")); err == nil {
		t.Fatal("a first installation that cannot be written must be reported")
	}
}
