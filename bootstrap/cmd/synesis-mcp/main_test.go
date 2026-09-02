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
