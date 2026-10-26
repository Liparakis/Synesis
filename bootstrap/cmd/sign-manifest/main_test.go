package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

func TestRunSignsRequestedPaths(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv(privateKeyEnvVar, base64.StdEncoding.EncodeToString(privateKey))

	root := t.TempDir()
	manifestPath := filepath.Join(root, "candidate.json")
	signaturePath := filepath.Join(root, "candidate.json.sig")
	manifest := []byte("{\"schemaVersion\":1}\n")
	if err := os.WriteFile(manifestPath, manifest, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := run(manifestPath, signaturePath, hex.EncodeToString(publicKey)); err != nil {
		t.Fatal(err)
	}
	signatureText, err := os.ReadFile(signaturePath)
	if err != nil {
		t.Fatal(err)
	}
	signature, err := base64.StdEncoding.DecodeString(string(signatureText[:len(signatureText)-1]))
	if err != nil {
		t.Fatal(err)
	}
	if !ed25519.Verify(publicKey, manifest, signature) {
		t.Fatal("signature did not verify")
	}
}

func TestRunRefusesAKeyThatIsNotThePinnedOne(t *testing.T) {
	_, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pinned, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv(privateKeyEnvVar, base64.StdEncoding.EncodeToString(privateKey))
	root := t.TempDir()
	manifestPath := filepath.Join(root, "m.json")
	signaturePath := filepath.Join(root, "m.json.sig")
	if err := os.WriteFile(manifestPath, []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := run(manifestPath, signaturePath, hex.EncodeToString(pinned)); err == nil {
		t.Fatal("signed with a key that is not the pinned one")
	}
	if _, err := os.Stat(signaturePath); err == nil {
		t.Fatal("a signature was written for the wrong key")
	}
	if err := run(manifestPath, signaturePath, "not-hex"); err == nil {
		t.Fatal("malformed public key accepted")
	}
}
