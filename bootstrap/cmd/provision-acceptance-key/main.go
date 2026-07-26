// Command provision-acceptance-key creates a user-scoped Ed25519 key for the
// explicit local Synesis acceptance signing profile.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

func main() {
	path := flag.String("path", "", "private-key path")
	flag.Parse()
	if err := provision(*path); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func provision(explicit string) error {
	path := explicit
	if path == "" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			return fmt.Errorf("LOCALAPPDATA is required on %s", runtime.GOOS)
		}
		path = filepath.Join(base, "Synesis", "secrets", "acceptance-manifest-signing.key")
	}
	path, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("acceptance key already exists: %s", path)
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	encoded := base64.StdEncoding.EncodeToString(private) + "\n"
	if err := os.WriteFile(path, []byte(encoded), 0o600); err != nil {
		return err
	}
	fingerprint := sha256.Sum256(public)
	fmt.Printf("ACCEPTANCE_KEY_PATH=%s\nPUBLIC_KEY_B64=%s\nPUBLIC_KEY_ID=%x\n", path, base64.StdEncoding.EncodeToString(public), fingerprint[:])
	return nil
}
