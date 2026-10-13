//go:build windows

package protocol

import "golang.org/x/sys/windows"

// showFailure tells the user that a link did nothing. The activation helper is
// a windowless program started by the browser or a shortcut, so a failure it
// only printed would be invisible.
func showFailure(message string) {
	text, _ := windows.UTF16PtrFromString(message)
	caption, _ := windows.UTF16PtrFromString("Synesis")
	_, _ = windows.MessageBox(0, text, caption, windows.MB_OK|windows.MB_ICONWARNING)
}
