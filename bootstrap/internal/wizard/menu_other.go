//go:build !windows

package wizard

// Run keeps the existing console installer entry point on Unix.
func Run() error {
	return runConsoleInstallerMenu()
}
