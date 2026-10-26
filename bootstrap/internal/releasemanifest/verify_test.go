package releasemanifest

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"
)

func signedManifest(t *testing.T, developmentOnly bool) (Manifest, []byte, ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	value := Manifest{SchemaVersion: 1, Version: "1.2.3", DevelopmentOnly: developmentOnly, Artifacts: map[string]Artifact{"linux-x64": {URL: "x.tar.gz", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return value, data, public, private
}

func TestVerifyAcceptsOnlyTheTrustRootsSignature(t *testing.T) {
	value, data, public, private := signedManifest(t, false)
	if err := Verify(public, value, data, ed25519.Sign(private, data), false); err != nil {
		t.Fatalf("valid signature rejected: %v", err)
	}
	other, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(other, value, data, ed25519.Sign(private, data), false); err == nil {
		t.Fatal("signature from another key accepted")
	}
	tampered := append([]byte(nil), data...)
	tampered[len(tampered)-2] ^= 1
	if err := Verify(public, value, tampered, ed25519.Sign(private, data), false); err == nil {
		t.Fatal("tampered manifest accepted")
	}
	if err := Verify(public, value, data, nil, false); err == nil {
		t.Fatal("unsigned release manifest accepted")
	}
}

func TestPlaceholderTrustRootIsAValidKeySize(t *testing.T) {
	key, err := hex.DecodeString(PlaceholderTrustRootHex)
	if err != nil || len(key) != ed25519.PublicKeySize {
		t.Fatalf("placeholder is not a 32-byte hex key: %v", err)
	}
}
