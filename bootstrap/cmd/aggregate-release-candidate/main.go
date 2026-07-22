package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

var expectedPlatforms = []platform{
	{name: "windows-x64", bootstrapSuffix: ".exe", bundleSuffix: ".zip"},
	{name: "windows-arm64", bootstrapSuffix: ".exe", bundleSuffix: ".zip"},
	{name: "linux-x64", bundleSuffix: ".tar.gz"},
	{name: "linux-arm64", bundleSuffix: ".tar.gz"},
	{name: "macos-x64", bundleSuffix: ".tar.gz"},
	{name: "macos-arm64", bundleSuffix: ".tar.gz"},
}

const defaultManifestPublicKeyHex = "d75a9801d7b3e3c7e8c8f6f5e4d6f6e4e4b8a7a7c8d9e0f1a2b3c4d5e6f70809"

type platform struct {
	name            string
	bootstrapSuffix string
	bundleSuffix    string
}

type artifact struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

type manifest struct {
	SchemaVersion   int                 `json:"schemaVersion"`
	Version         string              `json:"version"`
	DevelopmentOnly bool                `json:"developmentOnly"`
	Artifacts       map[string]artifact `json:"artifacts"`
}

type options struct {
	input            string
	output           string
	repoRoot         string
	version          string
	requireSignature bool
	publicKeyHex     string
}

func main() {
	var opts options
	publicKeyHex := os.Getenv("SYNESIS_MANIFEST_PUBLIC_KEY_HEX")
	if publicKeyHex == "" {
		publicKeyHex = defaultManifestPublicKeyHex
	}
	flag.StringVar(&opts.input, "input", "artifacts", "downloaded internal artifacts directory")
	flag.StringVar(&opts.output, "output", "build/release-candidate", "release-candidate output directory")
	flag.StringVar(&opts.repoRoot, "repo-root", ".", "repository root containing install scripts")
	flag.StringVar(&opts.version, "version", "", "release-candidate version")
	flag.BoolVar(&opts.requireSignature, "require-signature", false, "require a valid non-empty manifest signature")
	flag.StringVar(&opts.publicKeyHex, "public-key-hex", publicKeyHex, "manifest verification public key")
	flag.Parse()
	if err := aggregate(opts); err != nil {
		fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
		os.Exit(1)
	}
	fmt.Println("RELEASE_CANDIDATE=PASS")
}

func aggregate(opts options) error {
	if opts.version == "" || strings.ContainsAny(opts.version, `/\\`) {
		return errors.New("version must be non-empty and path-safe")
	}
	if err := requireDirectory(opts.input); err != nil {
		return err
	}
	if err := requireFile(filepath.Join(opts.repoRoot, "install", "install.ps1")); err != nil {
		return err
	}
	if err := requireFile(filepath.Join(opts.repoRoot, "install", "install.sh")); err != nil {
		return err
	}

	files, err := collectInput(opts.input, opts.version)
	if err != nil {
		return err
	}
	m, manifestBytes, signature, err := readManifest(files)
	if err != nil {
		return err
	}
	if err := validateManifest(m, manifestBytes, signature, files, opts); err != nil {
		return err
	}
	if err := os.RemoveAll(opts.output); err != nil {
		return fmt.Errorf("clear output: %w", err)
	}
	if err := os.MkdirAll(opts.output, 0o755); err != nil {
		return fmt.Errorf("create output: %w", err)
	}
	if err := writeOutput(opts, files, manifestBytes, signature); err != nil {
		return err
	}
	return validateOutput(opts, m)
}

type inputFiles struct {
	bundles    map[string]string
	bootstraps map[string]string
	manifest   string
	signature  string
}

func collectInput(root, version string) (inputFiles, error) {
	result := inputFiles{bundles: map[string]string{}, bootstraps: map[string]string{}}
	expected := map[string]platform{}
	for _, p := range expectedPlatforms {
		expected[fmt.Sprintf("synesis-%s-%s%s", version, p.name, p.bundleSuffix)] = p
		expected[fmt.Sprintf("synesis-bootstrap-%s-%s%s", version, p.name, p.bootstrapSuffix)] = p
	}
	err := filepath.Walk(root, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if info.IsDir() {
			return nil
		}
		name := info.Name()
		switch name {
		case "manifest.json":
			if result.manifest != "" {
				return errors.New("duplicate manifest.json")
			}
			result.manifest = path
		case "manifest.json.sig":
			if result.signature != "" {
				return errors.New("duplicate manifest.json.sig")
			}
			result.signature = path
		case "checksums.txt":
			// The final checksum file is regenerated from the normalized layout.
		default:
			p, ok := expected[name]
			if !ok {
				return fmt.Errorf("unexpected input artifact file: %s", path)
			}
			if strings.HasPrefix(name, "synesis-bootstrap-") {
				if _, exists := result.bootstraps[p.name]; exists {
					return fmt.Errorf("duplicate bootstrap platform: %s", p.name)
				}
				result.bootstraps[p.name] = path
			} else {
				if _, exists := result.bundles[p.name]; exists {
					return fmt.Errorf("duplicate bundle platform: %s", p.name)
				}
				result.bundles[p.name] = path
			}
		}
		return nil
	})
	if err != nil {
		return result, err
	}
	if result.manifest == "" {
		return result, errors.New("manifest.json is missing")
	}
	if result.signature == "" {
		return result, errors.New("manifest.json.sig is missing")
	}
	for _, p := range expectedPlatforms {
		if _, ok := result.bundles[p.name]; !ok {
			return result, fmt.Errorf("bundle is missing: %s", p.name)
		}
		if _, ok := result.bootstraps[p.name]; !ok {
			return result, fmt.Errorf("bootstrapper is missing: %s", p.name)
		}
	}
	return result, nil
}

func readManifest(files inputFiles) (manifest, []byte, []byte, error) {
	data, err := os.ReadFile(files.manifest)
	if err != nil {
		return manifest{}, nil, nil, fmt.Errorf("read manifest: %w", err)
	}
	var m manifest
	if err := json.Unmarshal(data, &m); err != nil {
		return m, nil, nil, fmt.Errorf("parse manifest: %w", err)
	}
	signature, err := os.ReadFile(files.signature)
	if err != nil {
		return m, nil, nil, fmt.Errorf("read manifest signature: %w", err)
	}
	return m, data, signature, nil
}

func validateManifest(m manifest, data, signature []byte, files inputFiles, opts options) error {
	if m.SchemaVersion != 1 || m.Version != opts.version {
		return errors.New("manifest version or schema mismatch")
	}
	if len(m.Artifacts) != len(expectedPlatforms) {
		return errors.New("manifest platform set mismatch")
	}
	for _, p := range expectedPlatforms {
		name := fmt.Sprintf("synesis-%s-%s%s", opts.version, p.name, p.bundleSuffix)
		entry, ok := m.Artifacts[p.name]
		if !ok || entry.URL != name {
			return fmt.Errorf("manifest entry mismatch: %s", p.name)
		}
		if err := validateArchive(files.bundles[p.name]); err != nil {
			return fmt.Errorf("unsafe bundle %s: %w", p.name, err)
		}
		sum, size, err := digestFile(files.bundles[p.name])
		if err != nil {
			return err
		}
		if entry.SHA256 != sum || entry.Size != size {
			return fmt.Errorf("manifest checksum mismatch: %s", p.name)
		}
	}
	text := strings.TrimSpace(string(signature))
	if text == "" {
		if opts.requireSignature || !m.DevelopmentOnly {
			return errors.New("required manifest signature is empty")
		}
		return nil
	}
	decoded, err := base64.StdEncoding.DecodeString(text)
	if err != nil || len(decoded) != ed25519.SignatureSize {
		return errors.New("manifest signature encoding invalid")
	}
	keyBytes, err := hex.DecodeString(opts.publicKeyHex)
	if err != nil || len(keyBytes) != ed25519.PublicKeySize {
		return errors.New("manifest public key is invalid")
	}
	if !ed25519.Verify(ed25519.PublicKey(keyBytes), data, decoded) {
		return errors.New("manifest signature verification failed")
	}
	return nil
}

func writeOutput(opts options, files inputFiles, manifestBytes, signature []byte) error {
	for _, p := range expectedPlatforms {
		bundleName := fmt.Sprintf("synesis-%s-%s%s", opts.version, p.name, p.bundleSuffix)
		if err := copyFile(files.bundles[p.name], filepath.Join(opts.output, "bundles", bundleName)); err != nil {
			return err
		}
		bootstrapName := fmt.Sprintf("synesis-bootstrap-%s%s", p.name, p.bootstrapSuffix)
		if err := copyFile(files.bootstraps[p.name], filepath.Join(opts.output, "bootstrap", bootstrapName)); err != nil {
			return err
		}
	}
	if err := copyFile(filepath.Join(opts.repoRoot, "install", "install.ps1"), filepath.Join(opts.output, "install", "install.ps1")); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(opts.repoRoot, "install", "install.sh"), filepath.Join(opts.output, "install", "install.sh")); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(opts.output, "manifest.json"), manifestBytes, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(opts.output, "manifest.json.sig"), signature, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(opts.output, "VERSION"), []byte(opts.version+"\n"), 0o644); err != nil {
		return err
	}
	readme := "Synesis release candidate " + opts.version + "\n\nPlatform-specific bootstrappers and Java bundles are included for controlled testing.\nThis is a CI artifact, not a public release.\n"
	if err := os.WriteFile(filepath.Join(opts.output, "README.md"), []byte(readme), 0o644); err != nil {
		return err
	}
	return writeChecksums(opts.output)
}

func validateOutput(opts options, m manifest) error {
	allowed := map[string]bool{"manifest.json": true, "manifest.json.sig": true, "checksums.txt": true, "VERSION": true, "README.md": true, "install/install.ps1": true, "install/install.sh": true}
	for _, p := range expectedPlatforms {
		allowed[filepath.ToSlash(filepath.Join("bundles", fmt.Sprintf("synesis-%s-%s%s", opts.version, p.name, p.bundleSuffix)))] = true
		allowed[filepath.ToSlash(filepath.Join("bootstrap", fmt.Sprintf("synesis-bootstrap-%s%s", p.name, p.bootstrapSuffix)))] = true
	}
	actual := map[string]bool{}
	err := filepath.Walk(opts.output, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if info.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(opts.output, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if !allowed[rel] {
			return fmt.Errorf("unexpected output file: %s", rel)
		}
		actual[rel] = true
		return nil
	})
	if err != nil {
		return err
	}
	if len(actual) != len(allowed) {
		return errors.New("release candidate layout is incomplete")
	}
	if m.Version != opts.version {
		return errors.New("output version mismatch")
	}
	if err := verifyChecksums(opts.output, allowed); err != nil {
		return err
	}
	if err := scanSensitiveText(opts.output, opts.repoRoot); err != nil {
		return err
	}
	return nil
}

func verifyChecksums(root string, allowed map[string]bool) error {
	data, err := os.ReadFile(filepath.Join(root, "checksums.txt"))
	if err != nil {
		return fmt.Errorf("read generated checksums: %w", err)
	}
	seen := map[string]bool{}
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 || len(fields[0]) != sha256.Size*2 {
			return errors.New("invalid generated checksum line")
		}
		path := filepath.ToSlash(fields[1])
		if path == "checksums.txt" || !allowed[path] || seen[path] {
			return fmt.Errorf("invalid or duplicate generated checksum path: %s", path)
		}
		seen[path] = true
		actual, _, err := digestFile(filepath.Join(root, filepath.FromSlash(path)))
		if err != nil || !strings.EqualFold(actual, fields[0]) {
			return fmt.Errorf("generated checksum mismatch: %s", path)
		}
	}
	if len(seen) != len(allowed)-1 {
		return errors.New("generated checksums do not cover the release candidate")
	}
	return nil
}

func scanSensitiveText(root, repoRoot string) error {
	for _, rel := range []string{"install/install.ps1", "install/install.sh", "manifest.json", "manifest.json.sig", "README.md", "VERSION", "checksums.txt"} {
		data, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(rel)))
		if err != nil {
			return err
		}
		text := strings.ToLower(string(data))
		for _, marker := range []string{"private_key", "begin private key", "github_token", "actions_runtime_token", "ghs_"} {
			if strings.Contains(text, marker) {
				return fmt.Errorf("sensitive marker in release candidate: %s", rel)
			}
		}
		if repoRoot != "" && strings.Contains(text, strings.ToLower(filepath.ToSlash(repoRoot))) {
			return fmt.Errorf("source-tree path in release candidate: %s", rel)
		}
	}
	return nil
}

func writeChecksums(root string) error {
	var paths []string
	err := filepath.Walk(root, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if info.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if rel != "checksums.txt" {
			paths = append(paths, rel)
		}
		return nil
	})
	if err != nil {
		return err
	}
	sort.Strings(paths)
	var out strings.Builder
	for _, rel := range paths {
		sum, _, err := digestFile(filepath.Join(root, filepath.FromSlash(rel)))
		if err != nil {
			return err
		}
		fmt.Fprintf(&out, "%s  %s\n", sum, rel)
	}
	return os.WriteFile(filepath.Join(root, "checksums.txt"), []byte(out.String()), 0o644)
}

func validateArchive(path string) error {
	if strings.HasSuffix(path, ".zip") {
		r, err := zip.OpenReader(path)
		if err != nil {
			return err
		}
		defer r.Close()
		for _, f := range r.File {
			if unsafeArchivePath(f.Name) {
				return fmt.Errorf("unsafe archive path: %s", f.Name)
			}
		}
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		header, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		if unsafeArchivePath(header.Name) {
			return fmt.Errorf("unsafe archive path: %s", header.Name)
		}
	}
}

func unsafeArchivePath(name string) bool {
	if name == "" || strings.ContainsRune(name, 0) || filepath.IsAbs(name) || strings.HasPrefix(name, "/") || strings.HasPrefix(name, "\\") {
		return true
	}
	for _, part := range strings.FieldsFunc(name, func(r rune) bool { return r == '/' || r == '\\' }) {
		if part == ".." {
			return true
		}
	}
	return false
}

func digestFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), size, nil
}

func copyFile(source, destination string) error {
	data, err := os.ReadFile(source)
	if err != nil {
		return fmt.Errorf("read %s: %w", source, err)
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(destination, data, 0o644); err != nil {
		return fmt.Errorf("write %s: %w", destination, err)
	}
	return nil
}

func requireDirectory(path string) error {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("input directory is missing: %s", path)
	}
	return nil
}
func requireFile(path string) error {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return fmt.Errorf("required file is missing: %s", path)
	}
	return nil
}
