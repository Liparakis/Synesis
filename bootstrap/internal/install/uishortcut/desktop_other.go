//go:build !windows

package uishortcut

import "errors"

// Desktop reports that the shortcut is a Windows feature.
func Desktop() (string, error) {
	return "", errors.New("the Synesis UI shortcut is available on Windows only")
}
