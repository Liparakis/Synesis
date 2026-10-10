//go:build windows

package main

import "synesis/bootstrap/internal/protocolreg"

func registerProtocol(paths installPaths) error {
	return protocolreg.Register(protocolreg.UserStore{}, paths.root, paths.activation)
}

func unregisterProtocol(paths installPaths) error {
	return protocolreg.Unregister(protocolreg.UserStore{}, paths.root)
}
