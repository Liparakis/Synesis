// Package uishortcut manages the optional "Synesis UI" shortcut: an internet
// shortcut to the synesis://ui address, which the registered link handler
// turns into the browser UI.
package uishortcut

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

const (
	fileName = "Synesis UI.url"
	address  = "URL=synesis://ui"
)

// Create writes the shortcut into directory, with the icon of iconFile.
func Create(directory, iconFile string) error {
	content := "[InternetShortcut]\r\n" + address + "\r\nIconFile=" + iconFile + "\r\nIconIndex=0\r\n"
	return os.WriteFile(filepath.Join(directory, fileName), []byte(content), 0o644)
}

// Exists reports whether directory holds the shortcut this package writes.
func Exists(directory string) bool {
	data, err := os.ReadFile(filepath.Join(directory, fileName))
	return err == nil && strings.Contains(string(data), address)
}

// Remove deletes the shortcut from directory. A file of the same name that
// points somewhere else is not ours and is left alone.
func Remove(directory string) error {
	if !Exists(directory) {
		return nil
	}
	if err := os.Remove(filepath.Join(directory, fileName)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
