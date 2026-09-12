//go:build !windows

package main

import "errors"

func registerProtocol(installPaths) error {
	return errors.New("native Synesis protocol registration is supported only on Windows")
}

func unregisterProtocol(installPaths) error {
	return errors.New("native Synesis protocol registration is supported only on Windows")
}
