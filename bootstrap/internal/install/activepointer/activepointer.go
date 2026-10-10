// Package activepointer reads and atomically replaces the pointers that name
// the active and previous verified payloads of an installation.
package activepointer

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"

	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/payloadmanifest"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

// Pointer is the atomically replaced pointer to the currently active
// verified payload.
type Pointer struct {
	SchemaVersion     int    `json:"schemaVersion"`
	Version           string `json:"version"`
	PayloadDirectory  string `json:"payloadDirectory"`
	ManifestHash      string `json:"manifestHash"`
	ProtectionProfile string `json:"protectionProfile,omitempty"`
	ActivatedAt       string `json:"activatedAt"`
	PreviousVersion   string `json:"previousVersion,omitempty"`
}

// Read loads and fully verifies the pointer at path, including its payload
// manifest and launcher. A missing pointer file yields nil.
func Read(paths layout.Paths, path string) (*Pointer, string, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, "", nil
	}
	if err != nil {
		return nil, "", err
	}
	var pointer Pointer
	if err := json.Unmarshal(data, &pointer); err != nil || pointer.SchemaVersion != 1 || !releasemanifest.ValidVersion(pointer.Version) || !payload.ValidDirectory(pointer.PayloadDirectory) || len(pointer.ManifestHash) != sha256.Size*2 {
		return nil, "", errors.New("active pointer invalid")
	}
	if _, err := hex.DecodeString(pointer.ManifestHash); err != nil {
		return nil, "", errors.New("active pointer hash invalid")
	}
	root := filepath.Join(paths.Versions, pointer.PayloadDirectory)
	inside, err := filepath.Abs(root)
	if err != nil || !payload.PathWithin(paths.Versions, inside) {
		return nil, "", errors.New("active pointer payload path invalid")
	}
	manifestPath := filepath.Join(root, "manifest.json")
	manifestData, err := os.ReadFile(manifestPath)
	if err != nil || payload.Digest(manifestData) != pointer.ManifestHash {
		return nil, "", errors.New("active payload manifest invalid")
	}
	if err := payloadmanifest.Verify(root, pointer.Version, manifestData); err != nil {
		return nil, "", err
	}
	if _, err := os.Stat(filepath.Join(root, "bin", layout.LauncherName())); err != nil {
		return nil, "", errors.New("active payload missing launcher")
	}
	return &pointer, payload.Digest(data), nil
}

// ReadLoose loads a structurally valid pointer without verifying its payload.
func ReadLoose(paths layout.Paths, path string) (*Pointer, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, "", err
	}
	var pointer Pointer
	if err := json.Unmarshal(data, &pointer); err != nil || pointer.SchemaVersion != 1 || !releasemanifest.ValidVersion(pointer.Version) || !payload.ValidDirectory(pointer.PayloadDirectory) {
		return nil, "", errors.New("active pointer invalid")
	}
	root := filepath.Join(paths.Versions, pointer.PayloadDirectory)
	inside, err := filepath.Abs(root)
	if err != nil || !payload.PathWithin(paths.Versions, inside) {
		return nil, "", errors.New("active pointer payload path invalid")
	}
	return &pointer, payload.Digest(data), nil
}

// Write atomically replaces the pointer at path, failing when expectedHash is
// set and the stored pointer no longer matches it.
func Write(paths layout.Paths, path string, pointer Pointer, expectedHash string) error {
	if expectedHash != "" {
		_, actual, err := Read(paths, path)
		if err != nil || actual != expectedHash {
			return errors.New("pointer changed during update")
		}
	}
	data, err := json.Marshal(pointer)
	if err != nil {
		return err
	}
	return installfs.AtomicWrite(path, data)
}
