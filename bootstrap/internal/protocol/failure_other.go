//go:build !windows

package protocol

// showFailure does nothing here: activation is a Windows feature.
func showFailure(string) {}
