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

	"synesis/bootstrap/internal/releasemanifest"
)

const bootstrapVersion = "0.1.0-dev.local"

func main() {
	if hasEmbeddedBundle() {
		if err := runEmbeddedCommand(os.Args[1:]); err != nil {
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
		if err := runInstallerMenu(); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
		return
	}
	switch os.Args[1] {
	case "version":
		fmt.Printf("SYNESIS_BOOTSTRAP_VERSION=%s\n", bootstrapVersion)
	case "install", "update":
		if err := runInstall(os.Args[1], os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "repair":
		if err := runRepair(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "uninstall":
		if err := runUninstall(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "register-protocol":
		if err := runRegisterProtocol(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "unregister-protocol":
		if err := runUnregisterProtocol(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "activate":
		if err := runActivation(os.Args[2:]); err != nil {
			var exitError *exec.ExitError
			if errors.As(err, &exitError) {
				os.Exit(exitError.ExitCode())
			}
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "doctor":
		if err := runDoctor(os.Args[2:]); err != nil {
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

// This development public key is replaced by the project's protected CI key
// before a public release. No private key belongs in this repository.
var manifestPublicKeyHex = "d75a9801d7b3e3c7e8c8f6f5e4d6f6e4e4b8a7a7c8d9e0f1a2b3c4d5e6f70809"

var manifestPublicKey = ed25519.PublicKey(mustHex(manifestPublicKeyHex))

// verifyManifestAuthenticity applies the release trust policy with this
// installer's pinned public key.
func verifyManifestAuthenticity(m releasemanifest.Manifest, data, signature []byte, acceptance bool) error {
	return releasemanifest.Verify(manifestPublicKey, m, data, signature, acceptance)
}
