package install

import (
	"errors"
	"fmt"
	"path/filepath"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/linkfirewall"
)

// allowLinkProgram and removeLinkRuleForUninstall are injectable so disposable
// installation tests never ask for elevation or touch the real firewall.
var (
	allowLinkProgram           = linkfirewall.Allow
	removeLinkRuleForUninstall = linkfirewall.Remove
)

// AllowLink lets other computers open Link sessions to the installation at
// installDir: it adds the firewall rule for the active payload's Java
// launcher, which is the program that listens. The rule names that version's
// path, so it is added again after every install, repair, or update.
func AllowLink(installDir string) error {
	paths, err := layout.Resolve(installDir)
	if err != nil {
		return err
	}
	pointer, _, err := activepointer.ReadLoose(paths, paths.Current)
	if err != nil {
		return errors.New("no active Synesis installation to allow Link sessions to")
	}
	return allowLinkProgram(filepath.Join(paths.Versions, pointer.PayloadDirectory, "runtime", "bin", "java.exe"))
}

// reportAllowLink runs the optional firewall step of a finished command-line
// install or repair. The installation already succeeded, so a refused or
// failed rule is reported on its own line and does not fail the command.
func reportAllowLink(installDir string) {
	if err := AllowLink(installDir); err != nil {
		fmt.Printf("LINK_FIREWALL=NOT_ADDED\nLINK_FIREWALL_REASON=%s\n", err.Error())
		return
	}
	fmt.Println("LINK_FIREWALL=ADDED")
}
