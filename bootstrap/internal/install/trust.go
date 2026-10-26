package install

import (
	"bytes"
	"crypto/ed25519"
	"encoding/hex"
	"errors"

	"synesis/bootstrap/internal/releasemanifest"
)

// manifestPublicKey is the pinned release trust root. The program entry
// installs it with SetTrustRoot before any command runs.
var manifestPublicKey ed25519.PublicKey

// productionTrustRoot is true once SetTrustRoot pinned a key other than the
// development placeholder. From then on an unsigned manifest is refused even
// when it declares itself developmentOnly: the unsigned file:// bypass exists
// only for builds that still carry the placeholder.
var productionTrustRoot bool

// SetTrustRoot pins the public key that release manifests must verify against.
// A key of the wrong size is a build mistake, and it stops the program here
// rather than at the first signature check.
func SetTrustRoot(key ed25519.PublicKey) {
	if len(key) != ed25519.PublicKeySize {
		panic("install: the release trust root is not an Ed25519 public key")
	}
	manifestPublicKey = key
	placeholder, _ := hex.DecodeString(releasemanifest.PlaceholderTrustRootHex)
	productionTrustRoot = !bytes.Equal(key, placeholder)
}

// verifyManifestAuthenticity applies the release trust policy with this
// installer's pinned public key.
func verifyManifestAuthenticity(m releasemanifest.Manifest, data, signature []byte, acceptance bool) error {
	if productionTrustRoot && !acceptance && len(signature) == 0 {
		return errors.New("manifest signature or contents invalid")
	}
	return releasemanifest.Verify(manifestPublicKey, m, data, signature, acceptance)
}
