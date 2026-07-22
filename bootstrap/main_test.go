package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestPlatformIDIsSupported(t *testing.T) {
	id := platformID()
	if id != "windows-x64" && id != "windows-arm64" && id != "linux-x64" && id != "linux-arm64" && id != "macos-x64" && id != "macos-arm64" {
		t.Fatalf("unexpected platform %q", id)
	}
}

func TestManifestSignatureAndBounds(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	value := manifest{SchemaVersion: 1, Version: "0.1.0-dev.1", Artifacts: map[string]artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if !ed25519.Verify(public, data, ed25519.Sign(private, data)) {
		t.Fatal("signature rejected")
	}
	if _, err := parseManifest(data); err != nil {
		t.Fatal(err)
	}
	if base64.StdEncoding.EncodeToString(ed25519.Sign(private, data)) == "" {
		t.Fatal("empty signature")
	}
}

func TestBootstrapInstallUpdateRollbackDoctorAndUninstall(t *testing.T) {
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

	if err := runInstall("install", []string{"--manifest", fileURL(m1), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	paths, err := installationPaths(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	assertCurrentVersion(t, paths, "0.1.0")
	if err := runDoctor([]string{"--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if err := runInstall("update", []string{"--manifest", fileURL(m2), "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	assertCurrentVersion(t, paths, "0.2.0")
	if _, err := os.Stat(filepath.Join(paths.versions, "0.1.0")); err != nil {
		t.Fatal("previous version was not retained for rollback")
	}
	if err := runInstall("update", []string{"--manifest", fileURL(m3), "--install-dir", installRoot}); err == nil {
		t.Fatal("invalid update activated")
	}
	assertCurrentVersion(t, paths, "0.2.0")
	if err := runUninstall([]string{"--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(projectMarker); err != nil {
		t.Fatal("uninstall removed user project data")
	}
}

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
	runBootstrapBinary(t, binary, "update", "--manifest", fileURL(m2), "--install-dir", installRoot)
	runBootstrapBinary(t, binary, "doctor", "--install-dir", installRoot)
	runBootstrapBinary(t, binary, "uninstall", "--install-dir", installRoot)
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
	if err := runInstall("install", []string{"--manifest", fileURL(manifestPath), "--install-dir", filepath.Join(root, "install")}); err == nil {
		t.Fatal("invalid manifest signature accepted")
	}
	manifestPublicKey = public
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	var value manifest
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatal(err)
	}
	value.Artifacts[platformID()] = artifact{URL: filepath.Base(archive), SHA256: strings.Repeat("0", sha256.Size*2), Size: value.Artifacts[platformID()].Size}
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
	if err := runInstall("install", []string{"--manifest", fileURL(manifestPath), "--install-dir", filepath.Join(root, "install")}); err == nil {
		t.Fatal("artifact checksum mismatch accepted")
	}
}

func runBootstrapBinary(t *testing.T, binary string, args ...string) {
	t.Helper()
	if output, err := exec.Command(binary, args...).CombinedOutput(); err != nil {
		t.Fatalf("bootstrap %v failed: %v\n%s", args, err, output)
	}
}

func writeBundleArchive(t *testing.T, root, version string) string {
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
		addZipFile(t, writer, "bin/synesis.cmd", []byte("@echo off\r\necho SYNESIS_VERSION="+version+"\r\n"), 0o644)
	} else {
		addZipFile(t, writer, "bin/synesis", []byte("#!/bin/sh\necho SYNESIS_VERSION="+version+"\n"), 0o755)
	}
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
	value := manifest{SchemaVersion: 1, Channel: "test", Version: version, DevelopmentOnly: false, Artifacts: map[string]artifact{platformID(): {URL: filepath.Base(archive), SHA256: hex.EncodeToString(digest[:]), Size: int64(len(archiveData))}}}
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
	value := manifest{SchemaVersion: 1, Channel: "test", Version: version, DevelopmentOnly: true, Artifacts: map[string]artifact{platformID(): {URL: filepath.Base(archive), SHA256: hex.EncodeToString(digest[:]), Size: int64(len(archiveData))}}}
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

func assertCurrentVersion(t *testing.T, paths installPaths, expected string) {
	t.Helper()
	data, err := os.ReadFile(paths.current)
	if err != nil || strings.TrimSpace(string(data)) != expected {
		t.Fatalf("current version = %q, want %q", strings.TrimSpace(string(data)), expected)
	}
}

func TestRejectsArchiveTraversal(t *testing.T) {
	buffer := new(bytes.Buffer)
	writer := zip.NewWriter(buffer)
	entry, err := writer.Create("../escape")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("bad"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractArchive(bytes.NewReader(buffer.Bytes()), t.TempDir()); err == nil {
		t.Fatal("traversal accepted")
	}
	buffer.Reset()
	writer = zip.NewWriter(buffer)
	absolute := "/escape"
	if runtime.GOOS == "windows" {
		absolute = "C:/escape"
	}
	entry, err = writer.Create(absolute)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("bad"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := extractArchive(bytes.NewReader(buffer.Bytes()), t.TempDir()); err == nil {
		t.Fatal("absolute path accepted")
	}
}

func TestTarGzPreservesExecutableLauncherSafely(t *testing.T) {
	archive := new(bytes.Buffer)
	gzipWriter := gzip.NewWriter(archive)
	tarWriter := tar.NewWriter(gzipWriter)
	data := []byte("#!/bin/sh\necho SYNESIS_VERSION=fixture\n")
	if err := tarWriter.WriteHeader(&tar.Header{Name: "bin/synesis", Mode: 0o755, Size: int64(len(data))}); err != nil {
		t.Fatal(err)
	}
	if _, err := tarWriter.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := tarWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gzipWriter.Close(); err != nil {
		t.Fatal(err)
	}
	destination := t.TempDir()
	if err := extractArchive(bytes.NewReader(archive.Bytes()), destination); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(destination, "bin", "synesis"))
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o111 == 0 {
		t.Fatal("executable bit was not preserved")
	}
}

func TestVersionComparison(t *testing.T) {
	if compareVersions("0.1.0-dev.2", "0.1.0-dev.10") >= 0 {
		t.Fatal("numeric prerelease ordering broken")
	}
	if compareVersions("0.1.0", "0.1.0-dev.10") <= 0 {
		t.Fatal("stable ordering broken")
	}
}

func TestManifestRejectsUnsafeVersion(t *testing.T) {
	value := manifest{SchemaVersion: 1, Version: "../escape", Artifacts: map[string]artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseManifest(data); err == nil {
		t.Fatal("unsafe version accepted")
	}
}

func TestUnsignedFileManifestIsDevelopmentOnly(t *testing.T) {
	root := t.TempDir()
	data, err := json.Marshal(manifest{SchemaVersion: 1, Version: "0.1.0-dev.1", DevelopmentOnly: true, Artifacts: map[string]artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "manifest.json")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, signature, err := fetchManifest(fileURL(path)); err != nil || len(signature) != 0 {
		t.Fatalf("development manifest was not accepted unsigned: %v", err)
	}
}
