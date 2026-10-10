//go:build windows

package protocol

import (
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/protocolreg"
)

// Register installs the per-user synesis:// handler for the installation.
func Register(paths layout.Paths) error {
	return protocolreg.Register(protocolreg.UserStore{}, paths.Root, paths.Activation)
}

// Unregister removes the synesis:// handler if this installation owns it.
func Unregister(paths layout.Paths) error {
	return protocolreg.Unregister(protocolreg.UserStore{}, paths.Root)
}
