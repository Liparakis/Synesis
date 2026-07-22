package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"os"
)

func main() {
	keyBytes, err := base64.StdEncoding.DecodeString(os.Getenv("SYNESIS_MANIFEST_PRIVATE_KEY_B64"))
	if err != nil || len(keyBytes) != ed25519.PrivateKeySize {
		panic(errors.New("SYNESIS_MANIFEST_PRIVATE_KEY_B64 must contain one Ed25519 private key"))
	}
	data, err := os.ReadFile("manifest.json")
	if err != nil {
		panic(err)
	}
	signature := ed25519.Sign(ed25519.PrivateKey(keyBytes), data)
	if err := os.WriteFile("manifest.json.sig", []byte(base64.StdEncoding.EncodeToString(signature)+"\n"), 0o644); err != nil {
		panic(err)
	}
}
