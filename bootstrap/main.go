// Command synesis-bootstrap is the self-updating installer for the Synesis
// CLI. It downloads a signed release manifest, verifies and extracts the
// platform-specific artifact, and manages one stable installation root.
package main

import (
	"crypto/ed25519"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"

	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/install/embedded"
	"synesis/bootstrap/internal/install/linkfirewall"
	"synesis/bootstrap/internal/protocol"
	"synesis/bootstrap/internal/releasemanifest"
	"synesis/bootstrap/internal/wizard"
)

const bootstrapVersion = "0.1.0-dev.local"

func main() {
	install.SetTrustRoot(ed25519.PublicKey(mustHex(manifestPublicKeyHex)))
	// The Windows wizard hosts an appended bundle itself, so its window opens
	// before the bundle is unpacked. Every other start unpacks first and hands
	// over to the installer inside the bundle.
	hostsBundle := runtime.GOOS == "windows" && len(os.Args) < 2
	// The installer starts itself elevated with this command. It needs nothing
	// from the bundle, so it runs here and not after an unpacking hand-over.
	if len(os.Args) >= 2 && os.Args[1] == "link-firewall" {
		if err := linkfirewall.RunElevated(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
		return
	}
	if embedded.HasBundle() && !hostsBundle {
		if err := embedded.RunCommand(os.Args[1:]); err != nil {
			var exitError *exec.ExitError
			if errors.As(err, &exitError) {
				os.Exit(exitError.ExitCode())
			}
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
		return
	}
	if len(os.Args) < 2 {
		if err := wizard.Run(); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
		return
	}
	switch os.Args[1] {
	case "version":
		fmt.Printf("SYNESIS_BOOTSTRAP_VERSION=%s\n", bootstrapVersion)
	case "install", "update":
		if err := install.RunInstall(os.Args[1], os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "repair":
		if err := install.RunRepair(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "uninstall":
		if err := install.RunUninstall(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "register-protocol":
		if err := protocol.RunRegister(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "unregister-protocol":
		if err := protocol.RunUnregister(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "activate":
		if err := protocol.RunActivation(os.Args[2:]); err != nil {
			var exitError *exec.ExitError
			if errors.As(err, &exitError) {
				os.Exit(exitError.ExitCode())
			}
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "doctor":
		if err := install.RunDoctor(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Println("synesis-installer [install|repair|uninstall|register-protocol|unregister-protocol|doctor|version]")
}

func mustHex(value string) []byte {
	result, err := hex.DecodeString(value)
	if err != nil {
		panic(err)
	}
	return result
}

// The release trust root, hex. It is the development placeholder until the owner
// pins the project release public key (docs/installation/packages.md). While the
// placeholder is pinned the unsigned developmentOnly file:// path still works;
// with any other key every manifest must be signed. No private key belongs here.
var manifestPublicKeyHex = releasemanifest.PlaceholderTrustRootHex
