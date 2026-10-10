package install

import (
	"crypto/ed25519"

	"synesis/bootstrap/internal/releasemanifest"
)

// manifestPublicKey is the pinned release trust root. The program entry
// installs it with SetTrustRoot before any command runs.
var manifestPublicKey ed25519.PublicKey

// SetTrustRoot pins the public key that release manifests must verify against.
// A key of the wrong size is a build mistake, and it stops the program here
// rather than at the first signature check.
func SetTrustRoot(key ed25519.PublicKey) {
	if len(key) != ed25519.PublicKeySize {
		panic("install: the release trust root is not an Ed25519 public key")
	}
	manifestPublicKey = key
}

// verifyManifestAuthenticity applies the release trust policy with this
// installer's pinned public key.
func verifyManifestAuthenticity(m releasemanifest.Manifest, data, signature []byte, acceptance bool) error {
	return releasemanifest.Verify(manifestPublicKey, m, data, signature, acceptance)
}
