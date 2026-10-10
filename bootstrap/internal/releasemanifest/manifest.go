// Package release describes a Synesis release: the signed manifest that names
// one artifact per platform, the rules that decide whether a manifest may be
// trusted, and how manifests and artifacts are downloaded. It holds no
// installation state.
package releasemanifest

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"runtime"
	"strings"
	"unicode"
)

// Artifact describes one platform-specific download entry in a manifest.
type Artifact struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

// Manifest is the signed release descriptor fetched from SYNESIS_MANIFEST_URL.
type Manifest struct {
	SchemaVersion           int                 `json:"schemaVersion"`
	Channel                 string              `json:"channel"`
	Version                 string              `json:"version"`
	PublishedAt             string              `json:"publishedAt"`
	MinimumBootstrapVersion string              `json:"minimumBootstrapVersion"`
	DevelopmentOnly         bool                `json:"developmentOnly"`
	SigningKeyID            string              `json:"signingKeyId,omitempty"`
	Artifacts               map[string]Artifact `json:"artifacts"`
}

// Parse unmarshals and bounds-checks a manifest: pinned schema version, a
// sane artifact count, and well-formed hex SHA-256 digests.
func Parse(data []byte) (Manifest, error) {
	var value Manifest
	if err := json.Unmarshal(data, &value); err != nil {
		return value, err
	}
	if value.SchemaVersion != 1 || !ValidVersion(value.Version) || len(value.Artifacts) == 0 || len(value.Artifacts) > 6 {
		return value, errors.New("manifest bounds invalid")
	}
	for id, entry := range value.Artifacts {
		if entry.URL == "" || entry.Size <= 0 || len(entry.SHA256) != sha256.Size*2 {
			return value, fmt.Errorf("artifact %s invalid", id)
		}
		if _, err := hex.DecodeString(entry.SHA256); err != nil {
			return value, fmt.Errorf("artifact %s hash invalid", id)
		}
	}
	return value, nil
}

// ValidVersion rejects anything that isn't a plain, path-safe version
// string, since it is written to the stable bundle's VERSION file.
func ValidVersion(value string) bool {
	if len(value) == 0 || len(value) > 128 || filepath.IsAbs(value) || filepath.VolumeName(value) != "" {
		return false
	}
	for _, character := range value {
		if !(unicode.IsLetter(character) || unicode.IsDigit(character) || strings.ContainsRune(".-_", character)) {
			return false
		}
	}
	return value != "." && value != ".."
}

// PlatformID returns the manifest artifact key for the current OS/arch,
// e.g. "linux-x64", or "<os>-unsupported" when the arch has no mapping.
func PlatformID() string {
	arch := map[string]string{"amd64": "x64", "arm64": "arm64"}[runtime.GOARCH]
	if arch == "" {
		return runtime.GOOS + "-unsupported"
	}
	osID := map[string]string{"windows": "windows", "linux": "linux", "darwin": "macos"}[runtime.GOOS]
	if osID == "" {
		return runtime.GOOS + "-" + arch
	}
	return osID + "-" + arch
}

// MatchesSHA256 reports whether data hashes to the hex digest expected,
// ignoring case.
func MatchesSHA256(data []byte, expected string) bool {
	digest := sha256.Sum256(data)
	return strings.EqualFold(hex.EncodeToString(digest[:]), expected)
}
