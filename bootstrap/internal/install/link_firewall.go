package install

import (
	"errors"
	"fmt"
	"path/filepath"
	"strings"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/linkfirewall"
)

// allowLinkProgram, removeLinkRuleForUninstall and linkRulePrograms are
// injectable so disposable installation tests never ask for elevation or read
// or change the real firewall.
var (
	allowLinkProgram           = linkfirewall.Allow
	removeLinkRuleForUninstall = linkfirewall.Remove
	linkRulePrograms           = linkfirewall.Programs
)

// AllowLink lets other computers open Link sessions to the installation at
// installDir: it adds the firewall rule for the active payload's Java
// launcher, which is the program that listens. The rule names that version's
// path, so it is added again after every install, repair, or update.
func AllowLink(installDir string) error {
	program, err := activeJava(installDir)
	if err != nil {
		return err
	}
	return allowLinkProgram(program)
}

func activeJava(installDir string) (string, error) {
	paths, err := layout.Resolve(installDir)
	if err != nil {
		return "", err
	}
	pointer, _, err := activepointer.ReadLoose(paths, paths.Current)
	if err != nil {
		return "", errors.New("no active Synesis installation to allow Link sessions to")
	}
	return filepath.Join(paths.Versions, pointer.PayloadDirectory, "runtime", "bin", "java.exe"), nil
}

// reportLinkFirewall runs the firewall step of a finished command-line
// install, repair, update or rollback. The installation already succeeded, so
// nothing here fails the command. With allow it replaces the rule. Without it,
// a rule that names another version's runtime is reported but left alone:
// changing it asks for administrator approval, which the command line asks
// for only when told to and an unattended update cannot answer (ADR-0096).
func reportLinkFirewall(installDir string, allow bool) {
	if allow {
		if err := AllowLink(installDir); err != nil {
			fmt.Printf("LINK_FIREWALL=NOT_ADDED\nLINK_FIREWALL_REASON=%s\n", err.Error())
			return
		}
		fmt.Println("LINK_FIREWALL=ADDED")
		return
	}
	paths, err := layout.Resolve(installDir)
	if err != nil {
		return
	}
	program, err := activeJava(installDir)
	if err != nil {
		return
	}
	programs, exists := linkRulePrograms()
	if !exists || len(programs) == 0 {
		return
	}
	// Compared from the versions folder on: netsh prints the folders above in
	// the console's code page, or as short 8.3 names, but a version folder's
	// name is ASCII.
	tail := strings.ToLower(strings.TrimPrefix(program, paths.Versions))
	for _, named := range programs {
		if strings.HasSuffix(strings.ToLower(named), tail) {
			return
		}
	}
	fmt.Printf("LINK_FIREWALL=STALE\nLINK_FIREWALL_RULE_PROGRAM=%s\n", programs[0])
	// Not %q: it would double every backslash of the path.
	fmt.Printf("WARNING=other computers cannot connect to this one: the Windows Firewall rule \"%s\" names another version's java.exe\n", linkfirewall.RuleName)
	fmt.Printf("LINK_FIREWALL_FIX=run install or repair again with --allow-link, or in an administrator terminal: netsh advfirewall firewall set rule name=\"%s\" new program=\"%s\"\n", linkfirewall.RuleName, program)
}
