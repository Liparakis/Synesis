//go:build windows

// Command synesis-folder-picker opens the Windows Explorer folder picker and
// writes the selected filesystem path to standard output.
package main

import (
	"errors"
	"fmt"
	"os"

	"synesis/bootstrap/internal/folderpicker"
)

func main() {
	path, err := folderpicker.Pick(0, "Select Synesis project folder")
	if errors.Is(err, folderpicker.ErrCancelled) {
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Fprintln(os.Stdout, path)
}
