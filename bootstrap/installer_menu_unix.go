//go:build !windows

package main

// runInstallerMenu keeps the existing console installer entry point on Unix.
func runInstallerMenu() error {
	return runConsoleInstallerMenu()
}
