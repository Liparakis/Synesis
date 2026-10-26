package install

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"

	"synesis/bootstrap/internal/releasemanifest"
)

func TestUnsignedDevelopmentManifestNeedsThePlaceholderTrustRoot(t *testing.T) {
	oldKey, oldProduction := manifestPublicKey, productionTrustRoot
	defer func() { manifestPublicKey, productionTrustRoot = oldKey, oldProduction }()
	value := releasemanifest.Manifest{SchemaVersion: 1, Version: "0.1.0-dev.1", DevelopmentOnly: true, Artifacts: map[string]releasemanifest.Artifact{"linux-x64": {URL: "x.tar.gz", Size: 3, SHA256: hex.EncodeToString(make([]byte, sha256.Size))}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}

	placeholder, err := hex.DecodeString(releasemanifest.PlaceholderTrustRootHex)
	if err != nil {
		t.Fatal(err)
	}
	SetTrustRoot(ed25519.PublicKey(placeholder))
	if err := verifyManifestAuthenticity(value, data, nil, false); err != nil {
		t.Fatalf("placeholder build lost the unsigned development path: %v", err)
	}

	real, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	SetTrustRoot(real)
	if err := verifyManifestAuthenticity(value, data, nil, false); err == nil {
		t.Fatal("unsigned development manifest accepted under a real trust root")
	}
	if err := verifyManifestAuthenticity(value, data, ed25519.Sign(private, data), false); err != nil {
		t.Fatalf("manifest signed by the real key rejected: %v", err)
	}
	wrong, wrongPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil || wrong == nil {
		t.Fatal(err)
	}
	if err := verifyManifestAuthenticity(value, data, ed25519.Sign(wrongPrivate, data), false); err == nil {
		t.Fatal("manifest signed by a different key accepted")
	}
}
