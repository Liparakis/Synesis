// Package protocol implements the synesis:// protocol commands: registration
// of the per-user handler and the native activation boundary it points at.
package protocol

import (
	"flag"
	"fmt"
	"os"

	"synesis/bootstrap/internal/install/layout"
)

// RunRegister implements the register-protocol command.
func RunRegister(args []string) error {
	flags := flag.NewFlagSet("register-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	if _, err := os.Stat(paths.Activation); err != nil {
		return fmt.Errorf("stable Synesis activation helper is missing: %w", err)
	}
	if err := Register(paths); err != nil {
		return err
	}
	fmt.Printf("PROTOCOL_REGISTRATION=SUCCESS\nLAUNCHER=%s\n", paths.Launcher)
	return nil
}

// RunUnregister implements the unregister-protocol command.
func RunUnregister(args []string) error {
	flags := flag.NewFlagSet("unregister-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	if err := Unregister(paths); err != nil {
		return err
	}
	fmt.Println("PROTOCOL_REGISTRATION=REMOVED")
	return nil
}
