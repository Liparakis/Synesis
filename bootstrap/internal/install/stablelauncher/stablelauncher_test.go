package stablelauncher

import (
	"os"
	"path/filepath"
	"testing"

	"synesis/bootstrap/internal/install/layout"
)

func TestStableCommandLauncherIsInstalledAndNeverFailsActivation(t *testing.T) {
	bin := t.TempDir()
	paths := layout.Paths{Bin: bin}
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
