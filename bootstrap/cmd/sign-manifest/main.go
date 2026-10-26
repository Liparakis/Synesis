// Command manifest-signer signs manifest.json with an Ed25519 private key
// and writes the base64-encoded signature to manifest.json.sig.
//
// The private key is supplied via the SYNESIS_MANIFEST_PRIVATE_KEY_B64
// environment variable as a base64-encoded 64-byte Ed25519 private key.
//
// With --public-key-hex the signature is checked against that key before it is
// kept, so a release never carries a manifest no installer can verify.
package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"

	"synesis/bootstrap/internal/releasemanifest"
)

const (
	privateKeyEnvVar     = "SYNESIS_MANIFEST_PRIVATE_KEY_B64"
	defaultManifestPath  = "manifest.json"
	defaultSignaturePath = "manifest.json.sig"
)

func main() {
	manifestPath := flag.String("manifest", defaultManifestPath, "manifest path")
	signaturePath := flag.String("signature", defaultSignaturePath, "detached signature path")
	publicKeyHex := flag.String("public-key-hex", "", "hex public key the signature must verify against")
	flag.Parse()
	if err := run(*manifestPath, *signaturePath, *publicKeyHex); err != nil {
		log.Fatal(err)
	}
}

// run signs manifestPath and writes the result to signaturePath. When
// publicKeyHex is set, nothing is written unless the signature verifies
// against it.
func run(manifestPath, signaturePath, publicKeyHex string) error {
	key, err := loadPrivateKey()
	if err != nil {
		return err
	}

	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return fmt.Errorf("read %s: %w", manifestPath, err)
	}

	signature := ed25519.Sign(key, data)
	if publicKeyHex != "" {
		public, err := hex.DecodeString(publicKeyHex)
		if err != nil || len(public) != ed25519.PublicKeySize {
			return errors.New("--public-key-hex is not a 32-byte hex Ed25519 public key")
		}
		if !ed25519.Verify(public, data, signature) {
			return errors.New("the signing key does not match the public key pinned in the installer")
		}
	}
	if err := os.WriteFile(signaturePath, []byte(releasemanifest.EncodeSignature(signature)), 0o644); err != nil {
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
