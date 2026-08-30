// Tests installed and platform-bundle runtime layout resolution.
package main

import (
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
