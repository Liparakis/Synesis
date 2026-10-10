//go:build !windows

package protocol

import (
	"errors"

	"synesis/bootstrap/internal/install/layout"
)

// Register installs the per-user synesis:// handler; only Windows supports it.
func Register(layout.Paths) error {
	return errors.New("native Synesis protocol registration is supported only on Windows")
}

// Unregister removes the installation-owned synesis:// handler; only Windows supports it.
func Unregister(layout.Paths) error {
	return errors.New("native Synesis protocol registration is supported only on Windows")
}
