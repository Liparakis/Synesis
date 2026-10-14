//go:build !windows

package linkfirewall

import (
	"errors"
	"os/exec"
)

func hide(*exec.Cmd) {}

// Allow is Windows-only: other platforms have no rule this installer owns.
func Allow(string) error {
	return errors.New("the Synesis Link firewall rule exists only on Windows")
}

// Remove has nothing to remove off Windows.
func Remove() error { return nil }
