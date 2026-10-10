package releasemanifest

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	maxManifestBytes = 1 << 20 // manifests are small JSON documents
	// MaxDownloadBytes bounds any single download.
	MaxDownloadBytes = int64(512 << 20)
)

// FetchManifest downloads the manifest and its detached signature. If the
// signature can't be fetched at all, the manifest is only usable when it
// was loaded from a local file:// URL and self-declares developmentOnly;
// otherwise this is a hard failure. The actual accept/reject decision is
// made by Verify, not here.
func FetchManifest(raw string) ([]byte, []byte, error) {
	data, err := Fetch(raw)
	if err != nil || len(data) > maxManifestBytes {
		return nil, nil, errors.New("manifest download failed or is too large")
	}
	signatureURL := raw + ".sig"
	signature, err := Fetch(signatureURL)
	if err != nil {
		u, parseErr := url.Parse(raw)
		m, manifestErr := Parse(data)
		if parseErr == nil && u.Scheme == "file" && manifestErr == nil && m.DevelopmentOnly {
			return data, nil, nil
		}
		return nil, nil, errors.New("manifest signature download failed")
	}
	signature = bytes.TrimSpace(signature)
	if len(signature) == 0 {
		return data, nil, nil
	}
	decoded, err := base64.StdEncoding.DecodeString(string(signature))
	if err != nil || len(decoded) != ed25519.SignatureSize {
		return nil, nil, errors.New("manifest signature encoding invalid")
	}
	return data, decoded, nil
}

// Fetch retrieves raw bytes from a file:// or https:// URL. Any other
// scheme, including plain http://, is rejected.
func Fetch(raw string) ([]byte, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	if u.Scheme == "file" {
		path := u.Path
		if runtime.GOOS == "windows" && strings.HasPrefix(path, "/") && len(path) > 2 && path[2] == ':' {
			path = path[1:]
		}
		return os.ReadFile(filepath.FromSlash(path))
	}
	if u.Scheme != "https" {
		return nil, errors.New("manifest and artifacts require HTTPS (file URLs are test-only)")
	}
	response, err := http.Get(raw) // #nosec G107 -- URL is an explicit release endpoint or local test server.
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("download returned %s", response.Status)
	}
	return io.ReadAll(io.LimitReader(response.Body, MaxDownloadBytes+1))
}

// ResolveArtifactURL turns a manifest artifact URL, which may be relative,
// into an absolute URL resolved against the manifest's own location.
func ResolveArtifactURL(manifestURL, raw string) string {
	u, err := url.Parse(raw)
	if err == nil && u.IsAbs() {
		return raw
	}
	base, err := url.Parse(manifestURL)
	if err != nil {
		return raw
	}
	base.Path = filepath.ToSlash(filepath.Join(filepath.Dir(base.Path), raw))
	return base.String()
}
