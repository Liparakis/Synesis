package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestAggregateReleaseCandidate(t *testing.T) {
	version := "0.1.0-dev.10"
	root := t.TempDir()
	input := filepath.Join(root, "input")
	repo := filepath.Join(root, "repo")
	output := filepath.Join(root, "output")
	makeFixture(t, input, repo, version)
	if err := aggregate(options{input: input, output: output, repoRoot: repo, version: version, publicKeyHex: defaultManifestPublicKeyHex}); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{
		"install/install.ps1", "install/install.sh", "manifest.json", "manifest.json.sig", "checksums.txt", "VERSION", "README.md",
		"bootstrap/synesis-bootstrap-windows-x64.exe", "bootstrap/synesis-bootstrap-macos-arm64",
		"bundles/synesis-0.1.0-dev.10-windows-x64.zip", "bundles/synesis-0.1.0-dev.10-macos-arm64.tar.gz",
	} {
		if _, err := os.Stat(filepath.Join(output, filepath.FromSlash(path))); err != nil {
			t.Fatalf("missing %s: %v", path, err)
		}
	}
}

func TestAggregateRejectsInvalidInputs(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(string, string, string)
	}{
		{"missing bundle", func(input, _, version string) {
			os.Remove(filepath.Join(input, "internal-bundle-macos-arm64", "synesis-"+version+"-macos-arm64.tar.gz"))
		}},
		{"missing bootstrapper", func(input, _, version string) {
			os.Remove(filepath.Join(input, "internal-bootstrap-macos-arm64", "synesis-bootstrap-"+version+"-macos-arm64"))
		}},
		{"checksum mismatch", func(input, _, version string) {
			os.WriteFile(filepath.Join(input, "internal-bundle-windows-x64", "synesis-"+version+"-windows-x64.zip"), []byte("changed"), 0o644)
		}},
		{"invalid signature", func(input, _, _ string) {
			os.WriteFile(filepath.Join(input, "internal-release-manifest", "manifest.json.sig"), []byte(base64.StdEncoding.EncodeToString(make([]byte, ed25519.SignatureSize))), 0o644)
		}},
		{"duplicate platform", func(input, _, version string) {
			path := filepath.Join(input, "duplicate")
			os.MkdirAll(path, 0o755)
			os.WriteFile(filepath.Join(path, "synesis-"+version+"-windows-x64.zip"), []byte("duplicate"), 0o644)
		}},
		{"unexpected file", func(input, _, _ string) {
			os.WriteFile(filepath.Join(input, "unexpected.txt"), []byte("unexpected"), 0o644)
		}},
		{"unsafe archive", func(input, _, version string) {
			writeZip(t, filepath.Join(input, "internal-bundle-windows-x64", "synesis-"+version+"-windows-x64.zip"), "../escape", []byte("x"))
			refreshManifest(t, input, version)
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			input, repo := filepath.Join(root, "input"), filepath.Join(root, "repo")
			makeFixture(t, input, repo, "0.1.0-dev.10")
			test.mutate(input, repo, "0.1.0-dev.10")
			if err := aggregate(options{input: input, output: filepath.Join(root, "output"), repoRoot: repo, version: "0.1.0-dev.10", publicKeyHex: defaultManifestPublicKeyHex}); err == nil {
				t.Fatal("expected validation failure")
			}
		})
	}
}

func TestAggregateVerifiesSignatureAndChecksumsAreDeterministic(t *testing.T) {
	version := "0.1.0-dev.10"
	root := t.TempDir()
	input, repo := filepath.Join(root, "input"), filepath.Join(root, "repo")
	makeFixture(t, input, repo, version)
	manifestPath := filepath.Join(input, "internal-release-manifest", "manifest.json")
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signature := base64.StdEncoding.EncodeToString(ed25519.Sign(private, data)) + "\n"
	if err := os.WriteFile(filepath.Join(input, "internal-release-manifest", "manifest.json.sig"), []byte(signature), 0o644); err != nil {
		t.Fatal(err)
	}
	key := hex.EncodeToString(public)
	first := filepath.Join(root, "first")
	if err := aggregate(options{input: input, output: first, repoRoot: repo, version: version, requireSignature: true, publicKeyHex: key}); err != nil {
		t.Fatal(err)
	}
	checksums, err := os.ReadFile(filepath.Join(first, "checksums.txt"))
	if err != nil {
		t.Fatal(err)
	}
	second := filepath.Join(root, "second")
	if err := aggregate(options{input: input, output: second, repoRoot: repo, version: version, requireSignature: true, publicKeyHex: key}); err != nil {
		t.Fatal(err)
	}
	repeated, err := os.ReadFile(filepath.Join(second, "checksums.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(checksums, repeated) {
		t.Fatal("checksums are not deterministic")
	}
}

func makeFixture(t *testing.T, input, repo, version string) {
	t.Helper()
	os.MkdirAll(filepath.Join(repo, "install"), 0o755)
	os.WriteFile(filepath.Join(repo, "install", "install.ps1"), []byte("placeholder"), 0o644)
	os.WriteFile(filepath.Join(repo, "install", "install.sh"), []byte("placeholder"), 0o644)
	bundles := map[string]artifact{}
	for _, p := range expectedPlatforms {
		bundleName := fmt.Sprintf("synesis-%s-%s%s", version, p.name, p.bundleSuffix)
		bundleDir := filepath.Join(input, "internal-bundle-"+p.name)
		os.MkdirAll(bundleDir, 0o755)
		bundlePath := filepath.Join(bundleDir, bundleName)
		if p.bundleSuffix == ".zip" {
			writeZip(t, bundlePath, "app/bin/synesis", []byte(p.name))
		} else {
			writeTarGz(t, bundlePath, "app/bin/synesis", []byte(p.name))
		}
		sum, size, err := digestFile(bundlePath)
		if err != nil {
			t.Fatal(err)
		}
		bundles[p.name] = artifact{URL: bundleName, SHA256: sum, Size: size}
		bootstrapName := fmt.Sprintf("synesis-bootstrap-%s-%s%s", version, p.name, p.bootstrapSuffix)
		bootstrapDir := filepath.Join(input, "internal-bootstrap-"+p.name)
		os.MkdirAll(bootstrapDir, 0o755)
		os.WriteFile(filepath.Join(bootstrapDir, bootstrapName), []byte(p.name), 0o644)
	}
	data, err := json.Marshal(manifest{SchemaVersion: 1, Version: version, DevelopmentOnly: true, Artifacts: bundles})
	if err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Join(input, "internal-release-manifest"), 0o755)
	os.WriteFile(filepath.Join(input, "internal-release-manifest", "manifest.json"), append(data, '\n'), 0o644)
	os.WriteFile(filepath.Join(input, "internal-release-manifest", "manifest.json.sig"), nil, 0o644)
	os.WriteFile(filepath.Join(input, "internal-release-manifest", "checksums.txt"), []byte("old\n"), 0o644)
}

func refreshManifest(t *testing.T, input, version string) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(input, "internal-release-manifest", "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var m manifest
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(input, "internal-bundle-windows-x64", "synesis-"+version+"-windows-x64.zip")
	sum, size, err := digestFile(path)
	if err != nil {
		t.Fatal(err)
	}
	m.Artifacts["windows-x64"] = artifact{URL: filepath.Base(path), SHA256: sum, Size: size}
	data, err = json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(input, "internal-release-manifest", "manifest.json"), append(data, '\n'), 0o644)
}

func writeZip(t *testing.T, path, name string, data []byte) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entry, err := archive.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := bytes.NewReader(data).WriteTo(entry); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

func writeTarGz(t *testing.T, path, name string, data []byte) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	compressed := gzip.NewWriter(file)
	archive := tar.NewWriter(compressed)
	if err := archive.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(data))}); err != nil {
		t.Fatal(err)
	}
	if _, err := bytes.NewReader(data).WriteTo(archive); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := compressed.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}
