//go:build windows

// Package childproc holds how the installer starts console helpers.
package childproc

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"
)

// Hide keeps a console child, and anything it starts, from opening a console
// window. The Windows installer is a windowless program: without this, Windows
// gives every console helper it runs a visible console of its own. Redirected
// output still reaches the caller.
func Hide(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
}
