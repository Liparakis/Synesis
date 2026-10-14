// Package linkfirewall manages the one Windows Firewall rule that lets another
// computer open a Synesis Link session to this one. Windows drops unsolicited
// inbound UDP unless a rule names the listening program, and changing a rule
// needs administrator rights, which a per-user installation does not have: the
// installer therefore starts itself again elevated, once, to run the commands
// in this package.
package linkfirewall

import (
	"errors"
	"flag"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// RuleName is the display name of the rule. A rule of this name is ours:
// adding the rule replaces it and uninstalling removes it.
const RuleName = "Synesis Link"

// netsh runs one netsh command and returns its combined output. A variable so
// tests can observe the commands without touching the firewall.
var netsh = func(args ...string) ([]byte, error) {
	command := exec.Command("netsh", args...)
	hide(command)
	return command.CombinedOutput()
}

func deleteArgs() []string {
	return []string{"advfirewall", "firewall", "delete", "rule", "name=" + RuleName}
}

func addArgs(program string) []string {
	return []string{"advfirewall", "firewall", "add", "rule", "name=" + RuleName,
		"dir=in", "action=allow", "protocol=UDP", "profile=any", "program=" + program}
}

// Exists reports whether a rule named RuleName is present. Reading the rules
// needs no elevation.
func Exists() bool {
	_, err := netsh("advfirewall", "firewall", "show", "rule", "name="+RuleName)
	return err == nil
}

// RunElevated implements the "link-firewall" command the installer starts
// itself with after the user approves elevation: "allow --program <path>"
// replaces the rule with one for that program, and "remove" deletes it.
func RunElevated(args []string) error {
	if len(args) == 0 {
		return errors.New("link-firewall requires allow or remove")
	}
	switch args[0] {
	case "remove":
		if !Exists() {
			return nil
		}
		return run(deleteArgs())
	case "allow":
		flags := flag.NewFlagSet("link-firewall allow", flag.ContinueOnError)
		program := flags.String("program", "", "program that listens for Link sessions")
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		// The elevated process acts on a path chosen by an unelevated one, so it
		// accepts only what the installer would name: an existing Java launcher.
		info, err := os.Stat(*program)
		if !filepath.IsAbs(*program) || !strings.EqualFold(filepath.Base(*program), "java.exe") || err != nil || info.IsDir() {
			return errors.New("link-firewall allow requires the installed runtime's java.exe")
		}
		// A leftover rule names the runtime of an earlier version.
		_, _ = netsh(deleteArgs()...)
		return run(addArgs(*program))
	default:
		return errors.New("link-firewall requires allow or remove")
	}
}

func run(args []string) error {
	if output, err := netsh(args...); err != nil {
		return errors.New("netsh failed: " + strings.TrimSpace(string(output)))
	}
	return nil
}
