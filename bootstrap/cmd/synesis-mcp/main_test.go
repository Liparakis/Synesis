// Tests installed and platform-bundle runtime layout resolution.
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestResolveLayoutForInstalledDistribution(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "lib"), 0o755); err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(root, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(bin, "synesis-mcp")
	if runtime.GOOS == "windows" {
		launcher += ".exe"
	}
	if err := os.WriteFile(launcher, []byte("launcher"), 0o755); err != nil {
		t.Fatal(err)
	}
	layout, err := resolveLayout(launcher)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(root, "lib", "*")
	if layout.classpath != want {
		t.Fatalf("classpath = %q, want %q", layout.classpath, want)
	}
}

func TestResolveLayoutForPlatformBundle(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "runtime", "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "app", "lib"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "runtime", "bin", javaName()), []byte("java"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "app", "synesis-cli.jar"), []byte("jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(root, "bin", "synesis-mcp")
	if err := os.MkdirAll(filepath.Dir(launcher), 0o755); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		launcher += ".exe"
	}
	if err := os.WriteFile(launcher, []byte("launcher"), 0o755); err != nil {
		t.Fatal(err)
	}
	layout, err := resolveLayout(launcher)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(root, "app", "synesis-cli.jar") + string(os.PathListSeparator) + filepath.Join(root, "app", "lib", "*")
	if layout.classpath != want {
		t.Fatalf("classpath = %q, want %q", layout.classpath, want)
	}
}

func TestResolveLayoutForActiveInstalledPayload(t *testing.T) {
	root := t.TempDir()
	payload := filepath.Join(root, "versions", "release-1")
	if err := os.MkdirAll(filepath.Join(payload, "runtime", "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(payload, "app", "lib"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(payload, "runtime", "bin", javaName()), []byte("java"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(payload, "app", "synesis-cli.jar"), []byte("active jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	manifest := []byte("active manifest")
	if err := os.WriteFile(filepath.Join(payload, "manifest.json"), manifest, 0o644); err != nil {
		t.Fatal(err)
	}
	pointer, err := json.Marshal(struct {
		SchemaVersion    int    `json:"schemaVersion"`
		Version          string `json:"version"`
		PayloadDirectory string `json:"payloadDirectory"`
		ManifestHash     string `json:"manifestHash"`
	}{1, "0.1.0", "release-1", digest(manifest)})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "current.json"), pointer, 0o644); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(root, "bin", "synesis-mcp")
	if err := os.MkdirAll(filepath.Dir(launcher), 0o755); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		launcher += ".exe"
	}
	if err := os.WriteFile(launcher, []byte("launcher"), 0o755); err != nil {
		t.Fatal(err)
	}

	layout, err := resolveLayout(launcher)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(payload, "app", "synesis-cli.jar") + string(os.PathListSeparator) + filepath.Join(payload, "app", "lib", "*")
	if layout.classpath != want {
		t.Fatalf("classpath = %q, want %q", layout.classpath, want)
	}
}

func TestResolveLayoutRejectsInvalidActivePayload(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "current.json"), []byte(`{"schemaVersion":1,"version":"0.1.0","payloadDirectory":"../escape","manifestHash":"bad"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	launcher := filepath.Join(root, "bin", "synesis-mcp")
	if err := os.MkdirAll(filepath.Dir(launcher), 0o755); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		launcher += ".exe"
	}
	if err := os.WriteFile(launcher, []byte("launcher"), 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveLayout(launcher); err == nil {
		t.Fatal("invalid active payload accepted")
	}
}

func TestCommandRoleFollowsTheInstalledName(t *testing.T) {
	for name, want := range map[string]bool{
		"synesis": true, "synesis.exe": true, "SYNESIS.EXE": true,
		"synesis-mcp": false, "synesis-mcp.exe": false, "synesis-installer.exe": false,
	} {
		if got := commandRole(filepath.Join("bin", name)); got != want {
			t.Fatalf("commandRole(%q) = %v, want %v", name, got, want)
		}
	}
}

func TestReleaseHealthIsOnlyRequiredForMaximumReleaseInstallations(t *testing.T) {
	payload := t.TempDir()
	if err := requireReleaseHealth(runtimeLayout{workingDirectory: payload}); err != nil {
		t.Fatalf("development layout must not be checked: %v", err)
	}
	if err := requireReleaseHealth(runtimeLayout{workingDirectory: payload, installRoot: t.TempDir(), protectionProfile: "standard"}); err != nil {
		t.Fatalf("standard profile must not be checked: %v", err)
	}
}

func TestMaximumReleaseFailsClosedWithoutAnAcceptingDoctor(t *testing.T) {
	payload := t.TempDir()
	install := t.TempDir()
	if err := requireReleaseHealth(runtimeLayout{workingDirectory: payload, installRoot: install, protectionProfile: maximumReleaseProfile}); err == nil {
		t.Fatal("a missing doctor must refuse the command")
	}
	// The payload marker selects the profile even when the pointer does not name it.
	if err := os.WriteFile(filepath.Join(payload, "PROTECTION_PROFILE"), []byte(maximumReleaseProfile+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := requireReleaseHealth(runtimeLayout{workingDirectory: payload, installRoot: install, protectionProfile: "standard"}); err == nil {
		t.Fatal("the payload profile marker must be honoured")
	}
	if err := os.MkdirAll(filepath.Join(payload, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(payload, "bin", installerName()), []byte("not an executable"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := requireReleaseHealth(runtimeLayout{workingDirectory: payload, installRoot: install, protectionProfile: maximumReleaseProfile}); err == nil {
		t.Fatal("a doctor that cannot run must refuse the command")
	}
}

func TestVersionedLauncherIsAnnouncedOnlyForAnInstalledPayload(t *testing.T) {
	t.Setenv("SYNESIS_LAUNCHER", "")
	payload := t.TempDir()
	if got := withVersionedLauncher(nil, runtimeLayout{workingDirectory: payload}); len(got) != 0 {
		t.Fatalf("development layout announced a launcher: %v", got)
	}
	got := withVersionedLauncher(nil, runtimeLayout{workingDirectory: payload, installRoot: t.TempDir()})
	name := "synesis"
	if runtime.GOOS == "windows" {
		name = "synesis.cmd"
	}
	if want := "SYNESIS_LAUNCHER=" + filepath.Join(payload, "bin", name); len(got) != 1 || got[0] != want {
		t.Fatalf("environment = %v, want [%s]", got, want)
	}
	t.Setenv("SYNESIS_LAUNCHER", "already-set")
	if got := withVersionedLauncher(nil, runtimeLayout{workingDirectory: payload, installRoot: t.TempDir()}); len(got) != 0 {
		t.Fatalf("an explicit launcher must be kept: %v", got)
	}
}
