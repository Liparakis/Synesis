//go:build !windows

package daemonstop

// Elsewhere the daemon is only asked to stop. Its program file is not looked
// up, so a daemon that does not answer is never ended directly.
func processImage(int) string { return "" }

func processIDs() []int { return nil }

func processRunning(int) bool { return false }

func terminate(int) bool { return false }
