package install

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/layout"
)

func TestNativeBootstrapProcess(t *testing.T) {
	binary := os.Getenv("SYNESIS_BOOTSTRAP_BIN")
	if binary == "" {
		t.Skip("SYNESIS_BOOTSTRAP_BIN not set")
	}
	root := t.TempDir()
	v1 := writeBundleArchive(t, root, "0.1.0")
	v2 := writeBundleArchive(t, root, "0.2.0")
	m1 := writeDevelopmentManifest(t, root, "0.1.0", v1)
	m2 := writeDevelopmentManifest(t, root, "0.2.0", v2)
	installRoot := filepath.Join(root, "install root")
	runBootstrapBinary(t, binary, "install", "--manifest", fileURL(m1), "--install-dir", installRoot)
	output := runBootstrapBinaryOutput(t, binary, "update", "--prepare", "--manifest", fileURL(m2), "--install-dir", installRoot)
	planID := ""
	for _, line := range strings.Split(string(output), "\n") {
		if strings.HasPrefix(line, "PLAN_ID=") {
			planID = strings.TrimPrefix(line, "PLAN_ID=")
		}
	}
	if planID == "" {
		t.Fatal("native prepared update did not return a plan")
	}
	runBootstrapBinary(t, binary, "update", "--execute", "--plan", planID, "--install-dir", installRoot)
	runBootstrapBinary(t, binary, "doctor", "--install-dir", installRoot)
	runBootstrapBinary(t, binary, "uninstall", "--install-dir", installRoot)
}

func TestNativeRealBundleInstallation(t *testing.T) {
	binary := os.Getenv("SYNESIS_BOOTSTRAP_BIN")
	archive := os.Getenv("SYNESIS_REAL_BUNDLE_ARCHIVE")
	if binary == "" || archive == "" {
		t.Skip("SYNESIS_BOOTSTRAP_BIN and SYNESIS_REAL_BUNDLE_ARCHIVE not set")
	}
	root := t.TempDir()
	localArchive := filepath.Join(root, filepath.Base(archive))
	archiveData, err := os.ReadFile(archive)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(localArchive, archiveData, 0o644); err != nil {
		t.Fatal(err)
	}
	manifest := writeDevelopmentManifest(t, root, "0.1.0-dev.local", localArchive)
	installRoot := filepath.Join(root, "install root")
	project := filepath.Join(root, "user project")
	if err := os.MkdirAll(filepath.Join(project, ".claude"), 0o755); err != nil {
		t.Fatal(err)
	}
	unrelated := filepath.Join(project, ".claude", "settings.json")
	unrelatedData := []byte(`{"hooks":{"unrelated":"keep"}}`)
	if err := os.WriteFile(unrelated, unrelatedData, 0o644); err != nil {
		t.Fatal(err)
	}

	runBootstrapBinary(t, binary, "install", "--manifest", fileURL(manifest), "--install-dir", installRoot)
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	runInstalledBinary(t, paths.Launcher, "version")
	runInstalledBinary(t, paths.Launcher, "init", "--project", project)
	runInstalledBinary(t, paths.Launcher, "provider", "list", "--project", project)
	runInstalledBinary(t, paths.Launcher, "provider", "install", "claude", "--project", project)
	runInstalledBinaryExit(t, paths.Launcher, 0, "provider", "status", "claude", "--project", project)
	runInstalledBinary(t, paths.Launcher, "provider", "uninstall", "claude", "--project", project)
	runInstalledBinary(t, paths.Launcher, "doctor", "--project", project)
	if actual, err := os.ReadFile(unrelated); err != nil || string(actual) != string(unrelatedData) {
		t.Fatalf("unrelated provider configuration changed: %v", err)
	}
	if _, err := os.Stat(filepath.Join(project, ".synesis", "project.json")); err != nil {
		t.Fatalf("project initialization missing: %v", err)
	}
	runBootstrapBinary(t, binary, "doctor", "--install-dir", installRoot)
	runBootstrapBinary(t, binary, "uninstall", "--install-dir", installRoot)
	if _, err := os.Stat(project); err != nil {
		t.Fatalf("uninstall removed user project: %v", err)
	}
}
