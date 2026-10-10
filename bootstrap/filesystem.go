package main

import (
	"os"
	"path/filepath"
)

func atomicWrite(path string, data []byte) error {
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

func fileExists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// removePayload makes a partially hardened payload removable before cleanup.
func removePayload(bundle string) error {
	if err := filepath.Walk(bundle, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return os.Chmod(path, 0o755)
		}
		mode := os.FileMode(0o644)
		if info.Mode()&0o111 != 0 || filepath.Base(path) == launcherName() {
			mode = 0o755
		}
		return os.Chmod(path, mode)
	}); err != nil {
		return err
	}
	return os.RemoveAll(bundle)
}

// removeInstallationTree makes immutable installation directories removable.
func removeInstallationTree(root string) error {
	if !fileExists(root) {
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
