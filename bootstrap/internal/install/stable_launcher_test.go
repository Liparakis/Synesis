package install

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/layout"
)

func TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchiveWithProfile(t, root, "0.1.0", "maximum-release")
	manifest := writeDevelopmentManifest(t, root, "0.1.0", archive)
	installRoot := filepath.Join(root, "install")
	if err := RunInstall("install", []string{"--manifest", fileURL(manifest), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	pointer, _, err := activepointer.Read(paths, paths.Current)
	if err != nil {
		t.Fatal(err)
	}
	if pointer == nil || pointer.ProtectionProfile != "maximum-release" {
		t.Fatalf("maximum protection profile was not recorded in the active pointer: %#v", pointer)
	}
	launcherPath := filepath.Join(paths.Bin, "synesis-launcher.ps1")
	if runtime.GOOS != "windows" {
		launcherPath = paths.Launcher
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
	if err := RunInstall("install", []string{"--manifest", fileURL(manifest), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(paths.Bin, "synesis-launcher.ps1")
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
