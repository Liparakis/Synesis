package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMigrationPlanReferencesRemainScoped(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	refs, err := collectPreparedMigrations(installPaths{launcher: filepath.Join(t.TempDir(), "bin", "synesis")})
	if err != nil {
		t.Fatal(err)
	}
	if refs.Project.State != "NO_PROJECT" {
		t.Fatalf("project scope unexpectedly discovered: %#v", refs.Project)
	}
	if len(refs.Providers) != 2 || refs.Providers[0].State != "MISSING" || refs.Providers[1].State != "MISSING" {
		t.Fatalf("provider references were not classified independently: %#v", refs.Providers)
	}
}

func TestMigrationPlanningRejectsMalformedProviderBeforeActivation(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	path := filepath.Join(home, ".codex", "mcp.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("{malformed"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, err := collectPreparedMigrations(installPaths{launcher: filepath.Join(t.TempDir(), "synesis")})
	if err == nil {
		t.Fatal("malformed provider configuration was accepted for update planning")
	}
}

func TestStableLauncherCommandRejectsVersionSpecificPayload(t *testing.T) {
	if stableLauncherCommand(filepath.Join("root", "versions", "v1", "bin", "synesis")) {
		t.Fatal("version-specific launcher accepted")
	}
	if !stableLauncherCommand(filepath.Join("root", "bin", "synesis")) {
		t.Fatal("stable launcher rejected")
	}
}
