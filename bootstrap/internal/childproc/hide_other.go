//go:build !windows

// Package childproc holds how the installer starts console helpers.
package childproc

import "os/exec"

// Hide does nothing here: only Windows opens a console window for a child.
func Hide(*exec.Cmd) {}
