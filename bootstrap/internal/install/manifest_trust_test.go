package install

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"synesis/bootstrap/internal/releasemanifest"
)

func TestAcceptanceTrustRootIsExplicitAndReleaseIsolated(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	value := releasemanifest.Manifest{SchemaVersion: 1, Version: "0.1.0-dev.10", DevelopmentOnly: true, SigningKeyID: releasemanifest.AcceptanceKeyID(public), Artifacts: map[string]releasemanifest.Artifact{"windows-x64": {URL: "x.zip", Size: 3, SHA256: strings.Repeat("0", sha256.Size*2)}}}
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	signature := ed25519.Sign(private, data)
	// The install package has no built-in trust root: the program entry pins
	// one, so the release trust root is stood in for by a key that is not the
	// acceptance signer's.
	releasePublic, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	oldKey := manifestPublicKey
	manifestPublicKey = releasePublic
	defer func() { manifestPublicKey = oldKey }()
	t.Setenv("SYNESIS_ACCEPTANCE_MANIFEST_PUBLIC_KEY_B64", base64.StdEncoding.EncodeToString(public))
	if err := verifyManifestAuthenticity(value, data, signature, true); err != nil {
		t.Fatalf("acceptance signature rejected: %v", err)
	}
	tampered := append([]byte(nil), data...)
	tampered[len(tampered)-2] ^= 1
	if err := verifyManifestAuthenticity(value, tampered, signature, true); err == nil {
		t.Fatal("tampered acceptance manifest accepted")
	}
	otherPublic, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("SYNESIS_ACCEPTANCE_MANIFEST_PUBLIC_KEY_B64", base64.StdEncoding.EncodeToString(otherPublic))
	if err := verifyManifestAuthenticity(value, data, signature, true); err == nil {
		t.Fatal("wrong acceptance trust root accepted")
	}
	if err := verifyManifestAuthenticity(value, data, signature, false); err == nil {
		t.Fatal("acceptance key weakened release trust")
	}
	unsigned := value
	if err := verifyManifestAuthenticity(unsigned, data, nil, true); err == nil {
		t.Fatal("unsigned acceptance manifest accepted")
	}
}
