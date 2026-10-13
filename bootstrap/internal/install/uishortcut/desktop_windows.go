//go:build windows

package uishortcut

import "golang.org/x/sys/windows"

// Desktop returns the user's desktop folder, wherever Windows keeps it.
func Desktop() (string, error) {
	return windows.KnownFolderPath(windows.FOLDERID_Desktop, 0)
}
