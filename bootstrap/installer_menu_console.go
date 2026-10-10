package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

// runConsoleInstallerMenu provides the non-GUI double-click entry point used
// by Unix bundles. It deliberately operates only on the bundle containing the
// running installer, so installation never needs a network manifest and never
// installs a provider as a side effect.
func runConsoleInstallerMenu() error {
	bundle, err := runningBundleRoot()
	if err != nil {
		return err
	}
	reader := bufio.NewReader(os.Stdin)
	fmt.Println("Synesis Installer")
	fmt.Println("1) Install")
	fmt.Println("2) Repair")
	fmt.Println("3) Uninstall")
	fmt.Println("4) Exit")
	fmt.Print("Choose an option: ")
	choice, err := reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	switch strings.TrimSpace(choice) {
	case "1":
		return runInstall("install", []string{"--bundle", bundle})
	case "2":
		return runRepair([]string{"--bundle", bundle})
	case "3":
		fmt.Print("Also remove Synesis metadata (identity and installer state)? [y/N]: ")
		answer, readErr := reader.ReadString('\n')
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return readErr
		}
		args := []string{}
		if strings.EqualFold(strings.TrimSpace(answer), "y") || strings.EqualFold(strings.TrimSpace(answer), "yes") {
			args = append(args, "--remove-metadata")
		}
		return runUninstall(args)
	case "4", "":
		return nil
	default:
		return errors.New("invalid installer choice")
	}
}
