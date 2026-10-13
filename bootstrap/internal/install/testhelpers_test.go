package install

import (
	"archive/zip"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/releasemanifest"
)

func runBootstrapBinary(t *testing.T, binary string, args ...string) {
	t.Helper()
	runBootstrapBinaryOutput(t, binary, args...)
}

func runBootstrapBinaryOutput(t *testing.T, binary string, args ...string) []byte {
	t.Helper()
	output, err := exec.Command(binary, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("bootstrap %v failed: %v\n%s", args, err, output)
	}
	return output
}

func runInstalledBinary(t *testing.T, launcher string, args ...string) {
	runInstalledBinaryExit(t, launcher, 0, args...)
}

func runInstalledBinaryExit(t *testing.T, launcher string, expectedExit int, args ...string) {
	t.Helper()
	command := []string{launcher}
	if runtime.GOOS == "windows" {
		command = []string{"cmd.exe", "/d", "/c", "call", launcher}
	}
	command = append(command, args...)
	output, err := exec.Command(command[0], command[1:]...).CombinedOutput()
	if exitCode := commandExitCode(err); exitCode != expectedExit {
		t.Fatalf("installed launcher %v failed: %v\n%s", args, err, output)
	}
	if len(args) > 0 && args[0] == "version" && !strings.Contains(string(output), "SYNESIS_VERSION=") {
		t.Fatalf("installed launcher version output missing metadata: %s", output)
	}
}

func commandExitCode(err error) int {
	if err == nil {
		return 0
	}
	if exit, ok := err.(*exec.ExitError); ok {
		return exit.ExitCode()
	}
	return -1
}

func writeBundleArchive(t *testing.T, root, version string) string {
	return writeBundleArchiveWithProfile(t, root, version, "")
}

func writeBundleArchiveWithProfile(t *testing.T, root, version, profile string) string {
	t.Helper()
	path := filepath.Join(root, "bundle-"+version+".zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	addZipFile(t, writer, "runtime/release", []byte("runtime"), 0o644)
	addZipFile(t, writer, "VERSION", []byte(version+"\n"), 0o644)
	if runtime.GOOS == "windows" {
		addZipFile(t, writer, "bin/synesis.cmd", []byte("@echo off\r\nif \"%1\"==\"version\" echo SYNESIS_VERSION="+version+"\r\nif \"%1\"==\"doctor\" echo DOCTOR=PASS\r\r\nif \"%1\"==\"echo-arg\" echo ARG=%2\r\n"), 0o644)
		addZipFile(t, writer, "bin/synesis-installer.exe", []byte("activation-helper-fixture"), 0o644)
	} else {
		addZipFile(t, writer, "bin/synesis", []byte("#!/bin/sh\nif [ \"$1\" = version ]; then echo SYNESIS_VERSION="+version+"; fi\nif [ \"$1\" = doctor ]; then echo DOCTOR=PASS; fi\n"), 0o755)
	}
	if profile != "" {
		addZipFile(t, writer, "PROTECTION_PROFILE", []byte(profile+"\n"), 0o644)
	}
	addZipFile(t, writer, filepath.ToSlash(filepath.Join("bin", layout.MCPLauncherName())), []byte("MCP_LAUNCHER="+version+"\n"), 0o755)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func writeDoctorFailureBundleArchive(t *testing.T, root, version string) string {
	t.Helper()
	path := filepath.Join(root, "bundle-doctor-failure-"+version+".zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	addZipFile(t, writer, "runtime/release", []byte("runtime"), 0o644)
	addZipFile(t, writer, "VERSION", []byte(version+"\n"), 0o644)
	if runtime.GOOS == "windows" {
		addZipFile(t, writer, "bin/synesis.cmd", []byte("@echo off\r\nif \"%1\"==\"version\" echo SYNESIS_VERSION="+version+"\r\nif \"%1\"==\"doctor\" exit /b 1\r\n"), 0o644)
	} else {
		addZipFile(t, writer, "bin/synesis", []byte("#!/bin/sh\nif [ \"$1\" = version ]; then echo SYNESIS_VERSION="+version+"; fi\nif [ \"$1\" = doctor ]; then exit 1; fi\n"), 0o755)
	}
	addZipFile(t, writer, filepath.ToSlash(filepath.Join("bin", layout.MCPLauncherName())), []byte("MCP_LAUNCHER="+version+"\n"), 0o755)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func writeBadBundleArchive(t *testing.T, root, version string) string {
	t.Helper()
	path := filepath.Join(root, "bundle-"+version+".zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	addZipFile(t, writer, "VERSION", []byte(version+"\n"), 0o644)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func addZipFile(t *testing.T, writer *zip.Writer, name string, data []byte, mode os.FileMode) {
	t.Helper()
	header := &zip.FileHeader{Name: name, Method: zip.Store}
	header.SetMode(mode)
	entry, err := writer.CreateHeader(header)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write(data); err != nil {
		t.Fatal(err)
	}
}

func writeSignedManifest(t *testing.T, root, version, archive string, private ed25519.PrivateKey) string {
	t.Helper()
	archiveData, err := os.ReadFile(archive)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(archiveData)
	value := releasemanifest.Manifest{SchemaVersion: 1, Channel: "test", Version: version, DevelopmentOnly: false, Artifacts: map[string]releasemanifest.Artifact{releasemanifest.PlatformID(): {URL: filepath.Base(archive), SHA256: hex.EncodeToString(digest[:]), Size: int64(len(archiveData))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(root, "manifest-"+version+".json")
	if err := os.WriteFile(manifestPath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(manifestPath+".sig", []byte(base64.StdEncoding.EncodeToString(ed25519.Sign(private, data))), 0o644); err != nil {
		t.Fatal(err)
	}
	return manifestPath
}

func writeDevelopmentManifest(t *testing.T, root, version, archive string) string {
	t.Helper()
	archiveData, err := os.ReadFile(archive)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(archiveData)
	value := releasemanifest.Manifest{SchemaVersion: 1, Channel: "test", Version: version, DevelopmentOnly: true, Artifacts: map[string]releasemanifest.Artifact{releasemanifest.PlatformID(): {URL: filepath.Base(archive), SHA256: hex.EncodeToString(digest[:]), Size: int64(len(archiveData))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "development-"+version+".json")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func fileURL(path string) string {
	slashPath := "/" + strings.TrimLeft(filepath.ToSlash(path), "/")
	return (&url.URL{Scheme: "file", Path: slashPath}).String()
}

func assertStableVersion(t *testing.T, paths layout.Paths, expected string) {
	t.Helper()
	pointer, _, err := activepointer.Read(paths, paths.Current)
	if err != nil || pointer == nil || pointer.Version != expected {
		actual := ""
		if pointer != nil {
			actual = pointer.Version
		}
		t.Fatalf("stable version = %q, want %q", actual, expected)
	}
}

func assertNoLegacyLayout(t *testing.T, paths layout.Paths) {
	t.Helper()
	if !installfs.FileExists(paths.Versions) || !installfs.FileExists(paths.Current) || installfs.FileExists(filepath.Join(paths.Root, "current")) {
		t.Fatal("versioned layout missing or legacy pointer remains under stable root")
	}
}

func withoutPathMutation(t *testing.T) {
	t.Helper()
	// Migration planning must not inspect the developer's real provider
	// configuration while these disposable installation fixtures run.
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	previous := pathUpdater
	pathUpdater = func(layout.Paths, bool) error { return nil }
	previousUnregister := unregisterProtocolForUninstall
	unregisterProtocolForUninstall = func(layout.Paths) error { return nil }
	previousStop, previousShortcut, previousStopAll := stopDaemon, removeUIShortcutForUninstall, stopInstalledProcesses
	stopDaemon = func(string) error { return nil }
	stopInstalledProcesses = func(string) error { return nil }
	removeUIShortcutForUninstall = func() error { return nil }
	t.Cleanup(func() {
		pathUpdater = previous
		unregisterProtocolForUninstall = previousUnregister
		stopDaemon, removeUIShortcutForUninstall, stopInstalledProcesses = previousStop, previousShortcut, previousStopAll
	})
}

func runPreparedUpdate(t *testing.T, manifest, installRoot string) error {
	t.Helper()
	if err := RunInstall("update", []string{"--prepare", "--manifest", manifest, "--install-dir", installRoot}); err != nil {
		return err
	}
	paths, err := layout.Resolve(installRoot)
	if err != nil {
		return err
	}
	entries, err := os.ReadDir(paths.Plans)
	if err != nil {
		return err
	}
	var newest string
	var newestAt time.Time
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".plan.json") {
			info, infoErr := entry.Info()
			if infoErr != nil || info.ModTime().After(newestAt) {
				newest = strings.TrimSuffix(entry.Name(), ".plan.json")
				if infoErr == nil {
					newestAt = info.ModTime()
				}
			}
		}
	}
	if newest != "" {
		return RunInstall("update", []string{"--execute", "--plan", newest, "--install-dir", installRoot})
	}
	return errors.New("prepared plan missing")
}
