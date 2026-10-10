// Package payloadmanifest writes and verifies the manifest that authenticates
// the complete file set of one extracted versioned payload.
package payloadmanifest

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/payload"
)

// payloadFileEntry records one file in the extracted payload manifest.
type payloadFileEntry struct {
	Path   string `json:"path"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

// payloadManifest authenticates the complete file set of one extracted
// versioned payload.
type payloadManifest struct {
	SchemaVersion      int                `json:"schemaVersion"`
	Version            string             `json:"version"`
	SourceManifestHash string             `json:"sourceManifestHash"`
	Files              []payloadFileEntry `json:"files"`
}

// Verify checks every file below root against the payload manifest data.
func Verify(root, expectedVersion string, data []byte) error {
	var manifest payloadManifest
	if json.Unmarshal(data, &manifest) != nil || manifest.SchemaVersion != 1 || manifest.Version != expectedVersion {
		return errors.New("payload manifest invalid")
	}
	declared := make(map[string]payloadFileEntry, len(manifest.Files))
	for _, entry := range manifest.Files {
		name, err := bundlearchive.SafeName(entry.Path)
		if err != nil || filepath.ToSlash(name) != entry.Path || entry.Size < 0 || len(entry.SHA256) != sha256.Size*2 {
			return errors.New("payload manifest entry invalid")
		}
		if _, exists := declared[entry.Path]; exists {
			return errors.New("payload manifest duplicate entry")
		}
		declared[entry.Path] = entry
	}
	seen := make(map[string]bool, len(declared))
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		relative = filepath.ToSlash(relative)
		if relative == "manifest.json" {
			return nil
		}
		entry, ok := declared[relative]
		if !ok || !info.Mode().IsRegular() {
			return errors.New("payload file is undeclared")
		}
		bytes, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		if int64(len(bytes)) != entry.Size || payload.Digest(bytes) != entry.SHA256 {
			return errors.New("payload file hash mismatch")
		}
		seen[relative] = true
		return nil
	})
	if err != nil {
		return err
	}
	if len(seen) != len(declared) {
		return errors.New("payload manifest file missing")
	}
	return nil
}

// Write records the file set of bundle in its manifest.json.
func Write(bundle, version string, sourceManifest []byte) error {
	entries := make([]payloadFileEntry, 0)
	err := filepath.Walk(bundle, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() || filepath.Base(path) == "manifest.json" {
			return nil
		}
		if !info.Mode().IsRegular() {
			return errors.New("payload contains unsupported entry")
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(bundle, path)
		if err != nil {
			return err
		}
		entries = append(entries, payloadFileEntry{Path: filepath.ToSlash(relative), Size: int64(len(data)), SHA256: payload.Digest(data)})
		return nil
	})
	if err != nil {
		return err
	}
	// filepath.Walk is deterministic on supported Go versions; sorting is cheap
	// and keeps the manifest stable if that implementation detail changes.
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	manifest := payloadManifest{SchemaVersion: 1, Version: version, SourceManifestHash: payload.Digest(sourceManifest), Files: entries}
	data, _ := json.Marshal(manifest)
	return os.WriteFile(filepath.Join(bundle, "manifest.json"), data, 0o444)
}

// ReadProtectionProfile returns the validated PROTECTION_PROFILE of bundle.
func ReadProtectionProfile(bundle string) (string, error) {
	data, err := os.ReadFile(filepath.Join(bundle, "PROTECTION_PROFILE"))
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	profile := strings.TrimSpace(string(data))
	switch profile {
	case "", "protection-lite", "maximum-release":
		return profile, nil
	default:
		return "", fmt.Errorf("unsupported protection profile: %s", profile)
	}
}

// MakeImmutable marks the payload read-only.
func MakeImmutable(bundle string) error {
	return filepath.Walk(bundle, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return os.Chmod(path, 0o555)
		}
		if info.Mode()&0o111 != 0 || filepath.Base(path) == layout.LauncherName() {
			return os.Chmod(path, 0o555)
		}
		return os.Chmod(path, 0o444)
	})
}
