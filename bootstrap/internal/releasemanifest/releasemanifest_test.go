package releasemanifest

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPlatformIDIsSupported(t *testing.T) {
	id := PlatformID()
	if id != "windows-x64" && id != "windows-arm64" && id != "linux-x64" && id != "linux-arm64" && id != "macos-x64" && id != "macos-arm64" {
		t.Fatalf("unexpected platform %q", id)
	}
}

func TestManifestSignatureAndBounds(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	value := Manifest{SchemaVersion: 1, Version: "0.1.0-dev.1", Artifacts: map[string]Artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if !ed25519.Verify(public, data, ed25519.Sign(private, data)) {
		t.Fatal("signature rejected")
	}
	if _, err := Parse(data); err != nil {
		t.Fatal(err)
	}
	if base64.StdEncoding.EncodeToString(ed25519.Sign(private, data)) == "" {
		t.Fatal("empty signature")
	}
}

func TestManifestRejectsUnsafeVersion(t *testing.T) {
	value := Manifest{SchemaVersion: 1, Version: "../escape", Artifacts: map[string]Artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(data); err == nil {
		t.Fatal("unsafe version accepted")
	}
}

func TestUnsignedFileManifestIsDevelopmentOnly(t *testing.T) {
	root := t.TempDir()
	data, err := json.Marshal(Manifest{SchemaVersion: 1, Version: "0.1.0-dev.1", DevelopmentOnly: true, Artifacts: map[string]Artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "manifest.json")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, signature, err := FetchManifest(fileURL(path)); err != nil || len(signature) != 0 {
		t.Fatalf("development manifest was not accepted unsigned: %v", err)
	}
}

func TestVersionComparison(t *testing.T) {
	if CompareVersions("0.1.0-dev.2", "0.1.0-dev.10") >= 0 {
		t.Fatal("numeric prerelease ordering broken")
	}
	if CompareVersions("0.1.0", "0.1.0-dev.10") <= 0 {
		t.Fatal("stable ordering broken")
	}
}

func fileURL(path string) string {
	slashPath := "/" + strings.TrimLeft(filepath.ToSlash(path), "/")
	return (&url.URL{Scheme: "file", Path: slashPath}).String()
}
