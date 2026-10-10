package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"

	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

// activePointer is the atomically replaced pointer to the currently active
// verified payload.
type activePointer struct {
	SchemaVersion     int    `json:"schemaVersion"`
	Version           string `json:"version"`
	PayloadDirectory  string `json:"payloadDirectory"`
	ManifestHash      string `json:"manifestHash"`
	ProtectionProfile string `json:"protectionProfile,omitempty"`
	ActivatedAt       string `json:"activatedAt"`
	PreviousVersion   string `json:"previousVersion,omitempty"`
}

func readPointer(paths installPaths, path string) (*activePointer, string, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, "", nil
	}
	if err != nil {
		return nil, "", err
	}
	var pointer activePointer
	if err := json.Unmarshal(data, &pointer); err != nil || pointer.SchemaVersion != 1 || !releasemanifest.ValidVersion(pointer.Version) || !payload.ValidDirectory(pointer.PayloadDirectory) || len(pointer.ManifestHash) != sha256.Size*2 {
		return nil, "", errors.New("active pointer invalid")
	}
	if _, err := hex.DecodeString(pointer.ManifestHash); err != nil {
		return nil, "", errors.New("active pointer hash invalid")
	}
	root := filepath.Join(paths.versions, pointer.PayloadDirectory)
	inside, err := filepath.Abs(root)
	if err != nil || !payload.PathWithin(paths.versions, inside) {
		return nil, "", errors.New("active pointer payload path invalid")
	}
	manifestPath := filepath.Join(root, "manifest.json")
	manifestData, err := os.ReadFile(manifestPath)
	if err != nil || payload.Digest(manifestData) != pointer.ManifestHash {
		return nil, "", errors.New("active payload manifest invalid")
	}
	if err := verifyPayloadManifest(root, pointer.Version, manifestData); err != nil {
		return nil, "", err
	}
	if _, err := os.Stat(filepath.Join(root, "bin", launcherName())); err != nil {
		return nil, "", errors.New("active payload missing launcher")
	}
	return &pointer, payload.Digest(data), nil
}

func readLoosePointer(paths installPaths, path string) (*activePointer, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, "", err
	}
	var pointer activePointer
	if err := json.Unmarshal(data, &pointer); err != nil || pointer.SchemaVersion != 1 || !releasemanifest.ValidVersion(pointer.Version) || !payload.ValidDirectory(pointer.PayloadDirectory) {
		return nil, "", errors.New("active pointer invalid")
	}
	root := filepath.Join(paths.versions, pointer.PayloadDirectory)
	inside, err := filepath.Abs(root)
	if err != nil || !payload.PathWithin(paths.versions, inside) {
		return nil, "", errors.New("active pointer payload path invalid")
	}
	return &pointer, payload.Digest(data), nil
}

func writePointer(paths installPaths, path string, pointer activePointer, expectedHash string) error {
	if expectedHash != "" {
		_, actual, err := readPointer(paths, path)
		if err != nil || actual != expectedHash {
			return errors.New("pointer changed during update")
		}
	}
	data, err := json.Marshal(pointer)
	if err != nil {
		return err
	}
	return atomicWrite(path, data)
}
