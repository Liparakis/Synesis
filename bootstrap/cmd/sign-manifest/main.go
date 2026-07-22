// Command manifest-signer signs manifest.json with an Ed25519 private key
// and writes the base64-encoded signature to manifest.json.sig.
//
// The private key is supplied via the SYNESIS_MANIFEST_PRIVATE_KEY_B64
// environment variable as a base64-encoded 64-byte Ed25519 private key.
package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
	"log"
	"os"
)

const (
	privateKeyEnvVar = "SYNESIS_MANIFEST_PRIVATE_KEY_B64"
	manifestPath     = "manifest.json"
	signaturePath    = "manifest.json.sig"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

// run signs manifestPath and writes the result to signaturePath.
func run() error {
	key, err := loadPrivateKey()
	if err != nil {
		return err
	}

	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return fmt.Errorf("read %s: %w", manifestPath, err)
	}

	sig := base64.StdEncoding.EncodeToString(ed25519.Sign(key, data)) + "\n"
	if err := os.WriteFile(signaturePath, []byte(sig), 0o644); err != nil {
		return fmt.Errorf("write %s: %w", signaturePath, err)
	}
	return nil
}

// loadPrivateKey reads and decodes the Ed25519 private key from the
// SYNESIS_MANIFEST_PRIVATE_KEY_B64 environment variable.
func loadPrivateKey() (ed25519.PrivateKey, error) {
	encoded := os.Getenv(privateKeyEnvVar)
	keyBytes, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(keyBytes) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("%s must contain one base64-encoded Ed25519 private key (%d bytes)",
			privateKeyEnvVar, ed25519.PrivateKeySize)
	}
	return keyBytes, nil
}
