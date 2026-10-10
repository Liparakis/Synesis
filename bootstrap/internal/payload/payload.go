// Package payload holds the naming and integrity rules for versioned payload
// directories that the installer and the native launcher must agree on. The
// launcher re-checks the active pointer at every start, so both programs apply
// the same code rather than two copies kept in sync by hand.
package payload

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"runtime"
	"strings"
	"unicode"
)

// Digest returns the lowercase hex SHA-256 of data.
func Digest(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// PathWithin reports whether child is parent or lies below it, after both are
// made absolute.
func PathWithin(parent, child string) bool {
	parent, _ = filepath.Abs(parent)
	child, _ = filepath.Abs(child)
	relative, err := filepath.Rel(parent, child)
	return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) && !filepath.IsAbs(relative)
}

// ValidDirectory reports whether value is a single path-safe directory name
// for a versioned payload. It rejects separators, volumes and any "..".
func ValidDirectory(value string) bool {
	if value == "" || len(value) > 160 || filepath.IsAbs(value) || filepath.VolumeName(value) != "" {
		return false
	}
	for _, character := range value {
		if !(unicode.IsLetter(character) || unicode.IsDigit(character) || strings.ContainsRune(".-_", character)) {
			return false
		}
	}
	return value != "." && value != ".." && !strings.Contains(value, "..")
}

// InstallerName is the file name of the installer executable inside a bundle.
func InstallerName() string {
	if runtime.GOOS == "windows" {
		return "synesis-installer.exe"
	}
	return "synesis-installer"
}
