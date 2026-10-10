package releasemanifest

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"os"
	"strings"

	"synesis/bootstrap/internal/payload"
)

// AcceptancePublicKeyEnv names the variable that carries the explicit local
// acceptance trust root. It never weakens the release trust root.
const AcceptancePublicKeyEnv = "SYNESIS_ACCEPTANCE_MANIFEST_PUBLIC_KEY_B64"

// Verify enforces the project's trust policy for a downloaded manifest: it
// must carry a valid Ed25519 signature from trustRoot, unless it has no
// signature at all and is explicitly self-declared developmentOnly. A nil
// signature can only reach this function via the one bypass path in
// FetchManifest (local file:// URL with no .sig present), so this is the
// single place that decides whether that bypass is actually honored.
//
// With acceptance set, the manifest must instead be a signed development
// bundle whose key is the one named by AcceptancePublicKeyEnv.
func Verify(trustRoot ed25519.PublicKey, m Manifest, data, signature []byte, acceptance bool) error {
	unsigned := len(signature) == 0
	if acceptance {
		if !m.DevelopmentOnly || unsigned {
			return errors.New("acceptance manifest must be signed development bundle")
		}
		public, err := acceptancePublicKey()
		if err != nil || m.SigningKeyID != AcceptanceKeyID(public) || !ed25519.Verify(public, data, signature) {
			return errors.New("manifest signature or contents invalid")
		}
		return nil
	}
	if unsigned && !m.DevelopmentOnly {
		return errors.New("manifest signature or contents invalid")
	}
	if !unsigned && !ed25519.Verify(trustRoot, data, signature) {
		return errors.New("manifest signature or contents invalid")
	}
	return nil
}

func acceptancePublicKey() (ed25519.PublicKey, error) {
	encoded := strings.TrimSpace(os.Getenv(AcceptancePublicKeyEnv))
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(data) != ed25519.PublicKeySize {
		return nil, errors.New("acceptance public key is not a valid Ed25519 key")
	}
	return ed25519.PublicKey(data), nil
}

// AcceptanceKeyID is the identifier a development manifest carries as
// signingKeyId: the hex SHA-256 of the acceptance public key.
func AcceptanceKeyID(public ed25519.PublicKey) string {
	return payload.Digest(public)
}

// EncodeSignature is the detached-signature file format: the base64 of the
// raw signature followed by a newline. FetchManifest reads it back.
func EncodeSignature(signature []byte) string {
	return base64.StdEncoding.EncodeToString(signature) + "\n"
}
