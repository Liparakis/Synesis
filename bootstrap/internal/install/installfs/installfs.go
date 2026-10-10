// Package installfs holds the file-system primitives the installer relies on:
// atomic replacement and removal of read-only installation trees.
package installfs

import (
	"os"
	"path/filepath"

	"synesis/bootstrap/internal/install/layout"
)

// AtomicWrite replaces path with data so readers never observe a partial file.
func AtomicWrite(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".synesis-atomic-")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

// FileExists reports whether anything, including a dangling link, exists at path.
func FileExists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// RemovePayload makes a partially hardened payload removable before cleanup.
func RemovePayload(bundle string) error {
	if err := filepath.Walk(bundle, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return os.Chmod(path, 0o755)
		}
		mode := os.FileMode(0o644)
		if info.Mode()&0o111 != 0 || filepath.Base(path) == layout.LauncherName() {
			mode = 0o755
		}
		return os.Chmod(path, mode)
	}); err != nil {
		return err
	}
	return os.RemoveAll(bundle)
}

// RemoveInstallationTree makes immutable installation directories removable.
func RemoveInstallationTree(root string) error {
	if !FileExists(root) {
		return nil
	}
	if err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return os.Chmod(path, 0o755)
		}
		return os.Chmod(path, info.Mode().Perm()|0o200)
	}); err != nil {
		return err
	}
	return os.RemoveAll(root)
}
