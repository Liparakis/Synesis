package main

import (
	"flag"
	"fmt"
	"os"
)

// unregisterProtocolForUninstall is injectable so disposable bootstrap tests
// never mutate or depend on the developer's real per-user registry. Production
// code retains the ownership-checked implementation by default.
var unregisterProtocolForUninstall = unregisterProtocol

func runRegisterProtocol(args []string) error {
	flags := flag.NewFlagSet("register-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if _, err := os.Stat(paths.activation); err != nil {
		return fmt.Errorf("stable Synesis activation helper is missing: %w", err)
	}
	if err := registerProtocol(paths); err != nil {
		return err
	}
	fmt.Printf("PROTOCOL_REGISTRATION=SUCCESS\nLAUNCHER=%s\n", paths.launcher)
	return nil
}

func runUnregisterProtocol(args []string) error {
	flags := flag.NewFlagSet("unregister-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if err := unregisterProtocol(paths); err != nil {
		return err
	}
	fmt.Println("PROTOCOL_REGISTRATION=REMOVED")
	return nil
}
