package install

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/releasemanifest"
)

func TestBootstrapInstallUpdateRollbackDoctorAndUninstall(t *testing.T) {
	withoutPathMutation(t)
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	oldKey := manifestPublicKey
	manifestPublicKey = public
	defer func() { manifestPublicKey = oldKey }()

	fixture := t.TempDir()
	installRoot := filepath.Join(fixture, "install root")
	project := filepath.Join(fixture, "user project")
	if err := os.MkdirAll(project, 0o755); err != nil {
		t.Fatal(err)
	}
	projectMarker := filepath.Join(project, ".synesis")
	if err := os.WriteFile(projectMarker, []byte("preserve"), 0o644); err != nil {
		t.Fatal(err)
	}

	v1 := writeBundleArchive(t, fixture, "0.1.0")
	v2 := writeBundleArchive(t, fixture, "0.2.0")
	bad := writeBadBundleArchive(t, fixture, "0.3.0")
	m1 := writeSignedManifest(t, fixture, "0.1.0", v1, private)
	m2 := writeSignedManifest(t, fixture, "0.2.0", v2, private)
	m3 := writeSignedManifest(t, fixture, "0.3.0", bad, private)

	if err := RunInstall("install", []string{"--manifest", fileURL(m1), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.1.0")
	assertNoLegacyLayout(t, paths)
	runInstalledBinary(t, paths.Launcher, "version")
	if err := RunDoctor([]string{"--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if err := runPreparedUpdate(t, fileURL(m2), installRoot); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.2.0")
	assertNoLegacyLayout(t, paths)
	if err := runPreparedUpdate(t, fileURL(m3), installRoot); err == nil {
		t.Fatal("invalid update activated")
	}
	assertStableVersion(t, paths, "0.2.0")
	if installfs.FileExists(paths.Rollback) {
		t.Fatal("rollback directory retained after successful update")
	}
	if err := RunInstall("update", []string{"--rollback", "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.1.0")
	if err := runPreparedUpdate(t, fileURL(m2), installRoot); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.2.0")
	if err := os.MkdirAll(paths.Rollback, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(paths.Root+".staging-leftover", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := RunUninstall([]string{"--install-dir", installRoot, "--remove-metadata"}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(projectMarker); err != nil {
		t.Fatal("uninstall removed user project data")
	}
	if installfs.FileExists(paths.Root) || installfs.FileExists(paths.Rollback) {
		t.Fatal("uninstall retained installation state")
	}
}

func TestOperationProgressReflectsBackendBoundaries(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	installRoot := filepath.Join(root, "install")
	var events []string
	observer := func(stage, status, detail string) {
		if detail == "" {
			t.Fatal("empty operation event")
		}
		events = append(events, stage+":"+status)
		if stage == "runtime" && status == "done" {
			paths, err := layout.Resolve(installRoot)
			if err != nil {
				t.Fatal(err)
			}
			assertStableVersion(t, paths, "0.1.0")
		}
	}
	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}, observer); err != nil {
		t.Fatal(err)
	}
	want := "verify:working,verify:done,runtime:working,runtime:done,shell:working,shell:done"
	if strings.Join(events, ",") != want {
		t.Fatalf("install boundaries = %v", events)
	}
	events = nil
	if err := RunRepair([]string{"--bundle", archive, "--install-dir", installRoot}, observer); err != nil {
		t.Fatal(err)
	}
	if strings.Join(events, ",") != want {
		t.Fatalf("repair boundaries = %v", events)
	}
	pathUpdater = func(layout.Paths, bool) error { return errors.New("PATH fixture failure") }
	events = nil
	if err := RunRepair([]string{"--bundle", archive, "--install-dir", installRoot}, observer); err == nil {
		t.Fatal("PATH failure hidden")
	}
	if strings.Join(events, ",") != strings.TrimSuffix(want, ",shell:done") {
		t.Fatalf("failed PATH reported success: %v", events)
	}
	pathUpdater = func(layout.Paths, bool) error { return nil }
	for _, remove := range []bool{false, true} {
		events = nil
		args := []string{"--install-dir", installRoot}
		if remove {
			args = append(args, "--remove-metadata")
		}
		if err := RunUninstall(args, func(stage, status, detail string) { events = append(events, stage+":"+status) }); err != nil {
			t.Fatal(err)
		}
		want := "shell:working,shell:done,runtime:working,runtime:done,metadata:done,cleanup:working,cleanup:done"
		if remove {
			want = strings.Replace(want, "metadata:done", "metadata:working,metadata:done", 1)
		}
		if strings.Join(events, ",") != want {
			t.Fatalf("uninstall boundaries = %v", events)
		}
	}
	events = nil
	if err := RunInstall("install", []string{"--bundle", filepath.Join(root, "missing"), "--install-dir", installRoot}, observer); err == nil {
		t.Fatal("missing bundle accepted")
	}
	if strings.Join(events, ",") != "verify:working" {
		t.Fatalf("invalid bundle reported success: %v", events)
	}
}

func TestLocalBundleInstallRepairAndMetadataToggle(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	extracted := filepath.Join(root, "extracted")
	if err := os.MkdirAll(extracted, 0o755); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(archive); err != nil {
		t.Fatal(err)
	} else if err := bundlearchive.Extract(bytes.NewReader(data), extracted); err != nil {
		t.Fatal(err)
	} else if _, _, _, err := loadLocalBundle(bundlearchive.Flatten(extracted)); err != nil {
		t.Fatalf("local bundle directory rejected: %v", err)
	}
	installRoot := filepath.Join(root, "install")
	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	mcpLauncher := filepath.Join(paths.Bin, layout.MCPLauncherName())
	if data, err := os.ReadFile(mcpLauncher); err != nil || string(data) != "MCP_LAUNCHER=0.1.0\n" {
		t.Fatalf("installed MCP launcher = %q, err=%v", string(data), err)
	}
	if err := os.WriteFile(mcpLauncher, []byte("stale launcher"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "Link"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "Link", "identity.pub"), []byte("keep"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := RunRepair([]string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(mcpLauncher); err != nil || string(data) != "MCP_LAUNCHER=0.1.0\n" {
		t.Fatalf("repaired MCP launcher = %q, err=%v", string(data), err)
	}
	if err := RunUninstall([]string{"--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if !installfs.FileExists(paths.Root) || !installfs.FileExists(filepath.Join(paths.Root, "Link", "identity.pub")) {
		t.Fatal("default uninstall did not preserve installer metadata")
	}
	if installfs.FileExists(paths.Bin) || installfs.FileExists(paths.Versions) {
		t.Fatal("default uninstall retained application payload")
	}
	if err := RunUninstall([]string{"--install-dir", installRoot, "--remove-metadata"}); err != nil {
		t.Fatal(err)
	}
	if installfs.FileExists(paths.Root) {
		t.Fatal("metadata removal retained installation root")
	}
}

func TestMetadataRemovalPreservesUserDataInsideInstallationRoot(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	installRoot := filepath.Join(root, "Synesis")
	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	userFiles := map[string]string{
		filepath.Join(paths.Root, "projects", "keep.txt"):     "project",
		filepath.Join(paths.Root, "repositories", "keep.txt"): "repository",
		filepath.Join(paths.Root, "workspaces", "keep.txt"):   "workspace",
	}
	for path, contents := range userFiles {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(contents), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	identity := filepath.Join(paths.Root, "Link", "identity.pub")
	if err := os.MkdirAll(filepath.Dir(identity), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(identity, []byte("installer-owned"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := RunUninstall([]string{"--install-dir", installRoot, "--remove-metadata"}); err != nil {
		t.Fatal(err)
	}
	if installfs.FileExists(paths.Bin) || installfs.FileExists(paths.Versions) || installfs.FileExists(filepath.Join(paths.Root, "Link")) {
		t.Fatal("metadata uninstall retained application payload or installer identity")
	}
	for path, contents := range userFiles {
		data, readErr := os.ReadFile(path)
		if readErr != nil || string(data) != contents {
			t.Fatalf("metadata uninstall removed user data %s: err=%v contents=%q", path, readErr, string(data))
		}
	}
}

func TestLegacyLayoutMigration(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	installRoot := filepath.Join(root, "Synesis with spaces")
	t.Cleanup(func() {
		_ = installfs.RemoveInstallationTree(installRoot)
		_ = installfs.RemoveInstallationTree(installRoot + ".rollback")
	})
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "versions", "0.1.0", "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "current"), []byte("0.1.0\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "bin", layout.LauncherName()), []byte("legacy proxy"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "Link"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "Link", "identity.pub"), []byte("preserve"), 0o644); err != nil {
		t.Fatal(err)
	}
	archive := writeBundleArchive(t, root, "0.2.0")
	manifest := writeDevelopmentManifest(t, root, "0.2.0", archive)
	if err := runPreparedUpdate(t, fileURL(manifest), installRoot); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.2.0")
	assertNoLegacyLayout(t, paths)
	if _, err := os.Stat(filepath.Join(paths.Root, "Link", "identity.pub")); err != nil {
		t.Fatalf("Link profile was not preserved: %v", err)
	}
}

func TestLegacyMigrationFailureRestoresLegacyLayout(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	installRoot := filepath.Join(root, "legacy")
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "versions", "0.1.0", "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(paths.Root, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "current"), []byte("0.1.0\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(paths.Root, "bin", layout.LauncherName()), []byte("legacy proxy"), 0o644); err != nil {
		t.Fatal(err)
	}
	archive := writeDoctorFailureBundleArchive(t, root, "0.2.0")
	manifest := writeDevelopmentManifest(t, root, "0.2.0", archive)
	if err := runPreparedUpdate(t, fileURL(manifest), installRoot); err == nil {
		t.Fatal("failed legacy migration activated")
	}
	if !installfs.FileExists(filepath.Join(paths.Root, "current")) || !installfs.FileExists(filepath.Join(paths.Root, "versions")) {
		t.Fatal("legacy installation was not restored")
	}
}

func TestFailedActivationRestoresPreviousStableBundle(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	installRoot := filepath.Join(root, "install")
	t.Cleanup(func() {
		_ = installfs.RemoveInstallationTree(installRoot)
		_ = installfs.RemoveInstallationTree(installRoot + ".rollback")
	})
	oldArchive := writeBundleArchive(t, root, "0.1.0")
	oldManifest := writeDevelopmentManifest(t, root, "0.1.0", oldArchive)
	if err := RunInstall("install", []string{"--manifest", fileURL(oldManifest), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	badArchive := writeDoctorFailureBundleArchive(t, root, "0.2.0")
	badManifest := writeDevelopmentManifest(t, root, "0.2.0", badArchive)
	if err := runPreparedUpdate(t, fileURL(badManifest), installRoot); err == nil {
		t.Fatal("doctor failure activated")
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.1.0")
	if installfs.FileExists(paths.Rollback) {
		t.Fatal("rollback directory retained after failed activation")
	}
}

func TestPreparedVersionedUpdateRetainsPayloadAndRollsBack(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	installRoot := filepath.Join(root, "install")
	t.Cleanup(func() {
		_ = installfs.RemoveInstallationTree(installRoot)
		_ = installfs.RemoveInstallationTree(installRoot + ".rollback")
	})
	v1 := writeBundleArchive(t, root, "0.1.0")
	v2 := writeBundleArchive(t, root, "0.2.0")
	m1 := writeDevelopmentManifest(t, root, "0.1.0", v1)
	m2 := writeDevelopmentManifest(t, root, "0.2.0", v2)
	if err := RunInstall("install", []string{"--manifest", fileURL(m1), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	if err := RunInstall("update", []string{"--prepare", "--manifest", fileURL(m2), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.1.0")
	entries, err := os.ReadDir(paths.Plans)
	if err != nil {
		t.Fatal(err)
	}
	planID := ""
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".plan.json") {
			planID = strings.TrimSuffix(entry.Name(), ".plan.json")
			break
		}
	}
	if planID == "" {
		t.Fatal("prepared plan was not persisted")
	}
	if err := RunInstall("update", []string{"--execute", "--plan", planID, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.2.0")
	previous, _, err := activepointer.Read(paths, paths.Previous)
	if err != nil || previous == nil || !installfs.FileExists(filepath.Join(paths.Versions, previous.PayloadDirectory)) {
		t.Fatal("previous immutable payload was not retained")
	}
	versionDirs, err := os.ReadDir(paths.Versions)
	if err != nil || len(versionDirs) != 2 {
		t.Fatalf("retained payload count = %d, err=%v", len(versionDirs), err)
	}
	if err := RunInstall("update", []string{"--rollback", "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	assertStableVersion(t, paths, "0.1.0")
}

func TestRejectsInvalidSignatureAndArtifactMismatch(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	otherPublic, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	oldKey := manifestPublicKey
	manifestPublicKey = otherPublic
	defer func() { manifestPublicKey = oldKey }()
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	manifestPath := writeSignedManifest(t, root, "0.1.0", archive, private)
	if err := RunInstall("install", []string{"--manifest", fileURL(manifestPath), "--install-dir", filepath.Join(root, "install")}); err == nil {
		t.Fatal("invalid manifest signature accepted")
	}
	manifestPublicKey = public
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	var value releasemanifest.Manifest
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatal(err)
	}
	value.Artifacts[releasemanifest.PlatformID()] = releasemanifest.Artifact{URL: filepath.Base(archive), SHA256: strings.Repeat("0", sha256.Size*2), Size: value.Artifacts[releasemanifest.PlatformID()].Size}
	data, err = json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(manifestPath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(manifestPath+".sig", []byte(base64.StdEncoding.EncodeToString(ed25519.Sign(private, data))), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := RunInstall("install", []string{"--manifest", fileURL(manifestPath), "--install-dir", filepath.Join(root, "install")}); err == nil {
		t.Fatal("artifact checksum mismatch accepted")
	}
}
