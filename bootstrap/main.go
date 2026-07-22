// Command synesis-bootstrap is the self-updating installer for the Synesis
// CLI. It downloads a signed release manifest, verifies and extracts the
// platform-specific artifact, and manages one stable installation root.
package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
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
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"unicode"
)

const (
	bootstrapVersion = "0.1.0-dev.local"
	maxManifestBytes = 1 << 20          // manifests are small JSON documents
	maxArchiveFiles  = 10000            // upper bound on entries; guards against archive bombs
	maxExtractedSize = int64(512 << 20) // upper bound on total extracted bytes
)

// This development public key is replaced by the project's protected CI key
// before a public release. No private key belongs in this repository.
var manifestPublicKeyHex = "d75a9801d7b3e3c7e8c8f6f5e4d6f6e4e4b8a7a7c8d9e0f1a2b3c4d5e6f70809"
var manifestPublicKey = ed25519.PublicKey(mustHex(manifestPublicKeyHex))

// artifact describes one platform-specific download entry in a manifest.
type artifact struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

// manifest is the signed release descriptor fetched from SYNESIS_MANIFEST_URL.
type manifest struct {
	SchemaVersion           int                 `json:"schemaVersion"`
	Channel                 string              `json:"channel"`
	Version                 string              `json:"version"`
	PublishedAt             string              `json:"publishedAt"`
	MinimumBootstrapVersion string              `json:"minimumBootstrapVersion"`
	DevelopmentOnly         bool                `json:"developmentOnly"`
	Artifacts               map[string]artifact `json:"artifacts"`
}

// installPaths is the resolved on-disk layout for one installation root.
type installPaths struct {
	root, bin, launcher, rollback string
}

func main() {
	if len(os.Args) < 2 {
		usage()
		return
	}
	switch os.Args[1] {
	case "version":
		fmt.Printf("SYNESIS_BOOTSTRAP_VERSION=%s\n", bootstrapVersion)
	case "install", "update":
		if err := runInstall(os.Args[1], os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "uninstall":
		if err := runUninstall(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	case "doctor":
		if err := runDoctor(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "ERROR="+err.Error())
			os.Exit(1)
		}
	default:
		usage()
		os.Exit(2)
	}
}

func usage() { fmt.Println("synesis-bootstrap install|update|uninstall|doctor|version") }

// runInstall implements both "install" and "update": fetch and verify the
// manifest, resolve the platform-appropriate artifact, verify its checksum,
// and atomically activate it.
func runInstall(operation string, args []string) error {
	flags := flag.NewFlagSet(operation, flag.ContinueOnError)
	manifestURL := flags.String("manifest", os.Getenv("SYNESIS_MANIFEST_URL"), "manifest URL")
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *manifestURL == "" {
		return errors.New("SYNESIS_MANIFEST_URL or --manifest is required")
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	data, signature, err := fetchManifest(*manifestURL)
	if err != nil {
		return err
	}
	m, err := parseManifest(data)
	if err != nil {
		return errors.New("manifest signature or contents invalid")
	}
	if err := verifyManifestAuthenticity(m, data, signature); err != nil {
		return err
	}
	if operation == "update" && isFlatLayout(paths) {
		installed := installedVersion(paths)
		if installed != "" && compareVersions(m.Version, installed) <= 0 {
			if err := pathUpdater(paths, true); err != nil {
				return err
			}
			fmt.Println("UPDATE_RESULT=NOOP")
			return nil
		}
	}
	selected, ok := m.Artifacts[platformID()]
	if !ok {
		return fmt.Errorf("unsupported platform: %s", platformID())
	}
	archive, err := fetch(resolveArtifactURL(*manifestURL, selected.URL))
	if err != nil {
		return err
	}
	if int64(len(archive)) != selected.Size || !matchesSHA256(archive, selected.SHA256) {
		return errors.New("artifact size or SHA-256 mismatch")
	}
	if err := activate(paths, m.Version, archive); err != nil {
		return err
	}
	fmt.Printf("INSTALL_RESULT=SUCCESS\nVERSION=%s\nPLATFORM=%s\n", m.Version, platformID())
	return nil
}

// verifyManifestAuthenticity enforces the project's trust policy for a
// downloaded manifest: it must carry a valid Ed25519 signature, unless it
// has no signature at all and is explicitly self-declared developmentOnly.
// A nil signature can only reach this function via the one bypass path in
// fetchManifest (local file:// URL with no .sig present), so this is the
// single place that decides whether that bypass is actually honored.
func verifyManifestAuthenticity(m manifest, data, signature []byte) error {
	unsigned := len(signature) == 0
	if unsigned && !m.DevelopmentOnly {
		return errors.New("manifest signature or contents invalid")
	}
	if !unsigned && !ed25519.Verify(manifestPublicKey, data, signature) {
		return errors.New("manifest signature or contents invalid")
	}
	return nil
}

// installationPaths resolves the on-disk layout for a Synesis installation.
// If explicit is empty, the root follows OS convention (LOCALAPPDATA on
// Windows, XDG_DATA_HOME or ~/.local/share on Linux, ~/Library/Application
// Support on macOS).
func installationPaths(explicit string) (installPaths, error) {
	root := explicit
	if root == "" {
		base, err := defaultInstallBase()
		if err != nil || base == "" {
			return installPaths{}, errors.New("unable to determine installation root")
		}
		root = filepath.Join(base, "Synesis")
	}
	root, err := filepath.Abs(root)
	if err != nil || root == filepath.VolumeName(root)+string(filepath.Separator) {
		return installPaths{}, errors.New("unsafe installation root")
	}

	launcherName := "synesis"
	if runtime.GOOS == "windows" {
		launcherName = "synesis.cmd"
	}
	bin := filepath.Join(root, "bin")
	return installPaths{root: root, bin: bin, launcher: filepath.Join(bin, launcherName), rollback: root + ".rollback"}, nil
}

// defaultInstallBase returns the OS-conventional parent directory for
// application data when --install-dir wasn't given explicitly.
func defaultInstallBase() (string, error) {
	switch runtime.GOOS {
	case "windows":
		if base := os.Getenv("LOCALAPPDATA"); base != "" {
			return base, nil
		}
		return os.UserConfigDir()
	case "linux":
		if base := os.Getenv("XDG_DATA_HOME"); base != "" {
			return base, nil
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, ".local", "share"), nil
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, "Library", "Application Support"), nil
	default:
		return os.UserConfigDir()
	}
}

// platformID returns the manifest artifact key for the current OS/arch,
// e.g. "linux-x64", or "<os>-unsupported" when the arch has no mapping.
func platformID() string {
	arch := map[string]string{"amd64": "x64", "arm64": "arm64"}[runtime.GOARCH]
	if arch == "" {
		return runtime.GOOS + "-unsupported"
	}
	osID := map[string]string{"windows": "windows", "linux": "linux", "darwin": "macos"}[runtime.GOOS]
	if osID == "" {
		return runtime.GOOS + "-" + arch
	}
	return osID + "-" + arch
}

// fetchManifest downloads the manifest and its detached signature. If the
// signature can't be fetched at all, the manifest is only usable when it
// was loaded from a local file:// URL and self-declares developmentOnly;
// otherwise this is a hard failure. The actual accept/reject decision is
// made by verifyManifestAuthenticity, not here.
func fetchManifest(raw string) ([]byte, []byte, error) {
	data, err := fetch(raw)
	if err != nil || len(data) > maxManifestBytes {
		return nil, nil, errors.New("manifest download failed or is too large")
	}
	signatureURL := raw + ".sig"
	signature, err := fetch(signatureURL)
	if err != nil {
		u, parseErr := url.Parse(raw)
		m, manifestErr := parseManifest(data)
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

// fetch retrieves raw bytes from a file:// or https:// URL. Any other
// scheme, including plain http://, is rejected.
func fetch(raw string) ([]byte, error) {
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
	return io.ReadAll(io.LimitReader(response.Body, maxExtractedSize+1))
}

// resolveArtifactURL turns a manifest artifact URL, which may be relative,
// into an absolute URL resolved against the manifest's own location.
func resolveArtifactURL(manifestURL, raw string) string {
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

// parseManifest unmarshals and bounds-checks a manifest: pinned schema
// version, a sane artifact count, and well-formed hex SHA-256 digests.
func parseManifest(data []byte) (manifest, error) {
	var value manifest
	if err := json.Unmarshal(data, &value); err != nil {
		return value, err
	}
	if value.SchemaVersion != 1 || !validVersion(value.Version) || len(value.Artifacts) == 0 || len(value.Artifacts) > 6 {
		return value, errors.New("manifest bounds invalid")
	}
	for id, entry := range value.Artifacts {
		if entry.URL == "" || entry.Size <= 0 || len(entry.SHA256) != sha256.Size*2 {
			return value, fmt.Errorf("artifact %s invalid", id)
		}
		if _, err := hex.DecodeString(entry.SHA256); err != nil {
			return value, fmt.Errorf("artifact %s hash invalid", id)
		}
	}
	return value, nil
}

// validVersion rejects anything that isn't a plain, path-safe version
// string, since it is written to the stable bundle's VERSION file.
func validVersion(value string) bool {
	if len(value) == 0 || len(value) > 128 || filepath.IsAbs(value) || filepath.VolumeName(value) != "" {
		return false
	}
	for _, character := range value {
		if !(unicode.IsLetter(character) || unicode.IsDigit(character) || strings.ContainsRune(".-_", character)) {
			return false
		}
	}
	return value != "." && value != ".."
}

// activate extracts, validates, and atomically swaps in the stable bundle.
func activate(paths installPaths, version string, archive []byte) error {
	if err := recoverRollback(paths); err != nil {
		return err
	}
	parent := filepath.Dir(paths.root)
	staging, err := os.MkdirTemp(parent, filepath.Base(paths.root)+".staging-")
	if err != nil {
		return err
	}
	if err := os.Chmod(staging, 0o755); err != nil {
		_ = os.RemoveAll(staging)
		return err
	}
	defer os.RemoveAll(staging)
	if err := extractArchive(bytes.NewReader(archive), staging); err != nil {
		return err
	}
	bundle := flattenBundle(staging)
	if err := validateBundleVersion(bundle, version); err != nil {
		return err
	}
	if hasLegacyMarkers(bundle) {
		return errors.New("flat bundle contains legacy layout markers")
	}
	oldExists := fileExists(paths.root)
	if oldExists {
		if err := os.RemoveAll(paths.rollback); err != nil {
			return err
		}
		if err := os.Rename(paths.root, paths.rollback); err != nil {
			return err
		}
	}
	if err := os.Rename(bundle, paths.root); err != nil {
		return restorePrevious(paths, oldExists)
	}
	if err := preserveLinkProfile(paths, oldExists); err != nil {
		return restorePrevious(paths, oldExists, err)
	}
	if err := validateStableInstall(paths); err != nil {
		return restorePrevious(paths, oldExists, err)
	}
	if err := pathUpdater(paths, true); err != nil {
		return restorePrevious(paths, oldExists, err)
	}
	if oldExists {
		if err := os.RemoveAll(paths.rollback); err != nil {
			return err
		}
	}
	return nil
}

func fileExists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

func recoverRollback(paths installPaths) error {
	if !fileExists(paths.rollback) {
		return nil
	}
	if !fileExists(paths.root) {
		return os.Rename(paths.rollback, paths.root)
	}
	if validateBundle(paths.root) == nil {
		return os.RemoveAll(paths.rollback)
	}
	if err := os.RemoveAll(paths.root); err != nil {
		return err
	}
	return os.Rename(paths.rollback, paths.root)
}

func restorePrevious(paths installPaths, oldExists bool, failures ...error) error {
	if fileExists(paths.root) {
		if err := os.RemoveAll(paths.root); err != nil {
			return fmt.Errorf("activation failed and cleanup failed: %w", err)
		}
	}
	if oldExists {
		if err := os.Rename(paths.rollback, paths.root); err != nil {
			return fmt.Errorf("activation failed and rollback failed: %w", err)
		}
	}
	if len(failures) > 0 {
		return failures[0]
	}
	return errors.New("activation failed")
}

func preserveLinkProfile(paths installPaths, oldExists bool) error {
	if !oldExists {
		return nil
	}
	source := filepath.Join(paths.rollback, "Link")
	if !fileExists(source) {
		return nil
	}
	target := filepath.Join(paths.root, "Link")
	if fileExists(target) {
		return errors.New("stable bundle unexpectedly contains Link profile")
	}
	return copyTree(source, target)
}

func copyTree(source, target string) error {
	info, err := os.Lstat(source)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errors.New("Link profile contains symlink")
	}
	if info.IsDir() {
		if err := os.MkdirAll(target, info.Mode().Perm()); err != nil {
			return err
		}
		entries, err := os.ReadDir(source)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if err := copyTree(filepath.Join(source, entry.Name()), filepath.Join(target, entry.Name())); err != nil {
				return err
			}
		}
		return nil
	}
	if !info.Mode().IsRegular() {
		return errors.New("Link profile contains unsupported entry")
	}
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	output, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
	if err != nil {
		input.Close()
		return err
	}
	_, copyErr := io.Copy(output, input)
	input.Close()
	if err := output.Close(); err != nil && copyErr == nil {
		copyErr = err
	}
	if copyErr != nil {
		return copyErr
	}
	if err := os.Chmod(target, info.Mode().Perm()); err != nil {
		return err
	}
	return nil
}

func validateStableInstall(paths installPaths) error {
	if err := validateBundle(paths.root); err != nil {
		return err
	}
	if err := runBundleDoctor(paths.root); err != nil {
		return err
	}
	return nil
}

func hasLegacyMarkers(bundle string) bool {
	return fileExists(filepath.Join(bundle, "versions")) || fileExists(filepath.Join(bundle, "current"))
}

func isFlatLayout(paths installPaths) bool {
	return validateBundle(paths.root) == nil && !hasLegacyMarkers(paths.root)
}

func installedVersion(paths installPaths) string {
	if data, err := os.ReadFile(filepath.Join(paths.root, "VERSION")); err == nil && validVersion(strings.TrimSpace(string(data))) {
		return strings.TrimSpace(string(data))
	}
	if data, err := os.ReadFile(filepath.Join(paths.root, "current")); err == nil && validVersion(strings.TrimSpace(string(data))) {
		return strings.TrimSpace(string(data))
	}
	return ""
}

// extractArchive dispatches to the ZIP or tar.gz extractor based on the
// leading bytes, after capping the total decoded size read into memory.
func extractArchive(data io.Reader, destination string) error {
	bytesData, err := io.ReadAll(io.LimitReader(data, maxExtractedSize+1))
	if err != nil || int64(len(bytesData)) > maxExtractedSize {
		return errors.New("archive is too large")
	}
	if bytes.HasPrefix(bytesData, []byte("PK\x03\x04")) {
		return extractZip(bytesData, destination)
	}
	return extractTarGz(bytesData, destination)
}

// reserveExtractedBytes is the shared zip/tar-bomb defense: it accounts an
// entry's declared size against the running total and rejects the archive
// if the cumulative extracted size would exceed maxExtractedSize.
func reserveExtractedBytes(total *int64, size int64) error {
	if size > maxExtractedSize-*total {
		return errors.New("archive expands beyond limit")
	}
	*total += size
	return nil
}

// openExtractedFile creates (truncating) the destination file for an
// archive entry, creating parent directories as needed.
func openExtractedFile(target string, mode os.FileMode) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return nil, err
	}
	return os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
}

func extractZip(data []byte, destination string) error {
	archive, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return err
	}
	if len(archive.File) > maxArchiveFiles {
		return errors.New("too many archive entries")
	}
	var total int64
	for _, entry := range archive.File {
		// Reject anything that isn't a plain regular file or directory
		// (symlinks in particular could point outside `destination`).
		name, err := safeArchiveName(entry.Name)
		if err != nil || entry.Mode()&os.ModeSymlink != 0 || (!entry.FileInfo().IsDir() && !entry.FileInfo().Mode().IsRegular()) {
			return errors.New("unsafe ZIP entry")
		}
		if err := reserveExtractedBytes(&total, int64(entry.UncompressedSize64)); err != nil {
			return err
		}
		target := filepath.Join(destination, name)
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		input, err := entry.Open()
		if err != nil {
			return err
		}
		mode := safeFileMode(entry.Mode())
		output, err := openExtractedFile(target, mode)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.CopyN(output, input, int64(entry.UncompressedSize64))
		input.Close()
		output.Close()
		if copyErr != nil && !(entry.UncompressedSize64 == 0 && copyErr == io.EOF) {
			return copyErr
		}
		_ = os.Chmod(target, mode)
	}
	return nil
}

func extractTarGz(data []byte, destination string) error {
	reader, err := newGzipReader(bytes.NewReader(data))
	if err != nil {
		return err
	}
	defer reader.Close()
	tarReader := tar.NewReader(reader)
	var count int
	var total int64
	for {
		header, err := tarReader.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		count++
		if count > maxArchiveFiles || header.Size < 0 {
			return errors.New("unsafe TAR bounds")
		}
		name, err := safeArchiveName(header.Name)
		if err != nil || header.Typeflag == tar.TypeSymlink || header.Typeflag == tar.TypeLink {
			return errors.New("unsafe TAR entry")
		}
		if err := reserveExtractedBytes(&total, header.Size); err != nil {
			return err
		}
		target := filepath.Join(destination, name)
		if header.Typeflag == tar.TypeDir {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if header.Typeflag != tar.TypeReg && header.Typeflag != tar.TypeRegA {
			return errors.New("unsupported TAR entry")
		}
		mode := safeFileMode(os.FileMode(header.Mode))
		output, err := openExtractedFile(target, mode)
		if err != nil {
			return err
		}
		_, copyErr := io.CopyN(output, tarReader, header.Size)
		output.Close()
		if copyErr != nil {
			return copyErr
		}
		_ = os.Chmod(target, mode)
	}
}

// safeFileMode collapses an archive entry's mode down to one of two known
// values, so no bit from an untrusted archive (e.g. setuid) ever reaches disk.
func safeFileMode(mode os.FileMode) os.FileMode {
	if mode&0o111 != 0 {
		return 0o755
	}
	return 0o644
}

// safeArchiveName rejects absolute paths and "../" traversal in an archive
// entry name, and normalizes it to the host's path separator.
func safeArchiveName(name string) (string, error) {
	name = filepath.FromSlash(name)
	if name == "" || filepath.IsAbs(name) || filepath.VolumeName(name) != "" {
		return "", errors.New("absolute archive path")
	}
	clean := filepath.Clean(name)
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("archive traversal")
	}
	return clean, nil
}

// flattenBundle unwraps a single top-level directory some archive formats
// add (e.g. "synesis-0.2.0/..."), so the bundle root always matches what
// validateBundle expects regardless of how the archive was packaged.
func flattenBundle(staging string) string {
	entries, _ := os.ReadDir(staging)
	if len(entries) == 1 && entries[0].IsDir() {
		return filepath.Join(staging, entries[0].Name())
	}
	return staging
}

// validateBundle checks that an extracted bundle has the expected shape
// and that its embedded launcher actually runs before it's ever activated.
func validateBundle(bundle string) error {
	for _, relative := range []string{"bin", "runtime"} {
		info, err := os.Stat(filepath.Join(bundle, relative))
		if err != nil || !info.IsDir() {
			return fmt.Errorf("bundle missing %s", relative)
		}
	}
	version, err := os.Stat(filepath.Join(bundle, "VERSION"))
	if err != nil || !version.Mode().IsRegular() {
		return errors.New("bundle missing VERSION")
	}
	launcher := filepath.Join(bundle, "bin", launcherName())
	if info, err := os.Stat(launcher); err != nil || !info.Mode().IsRegular() {
		return errors.New("bundle missing stable launcher")
	}
	return runBundleVersion(bundle)
}

func launcherName() string {
	if runtime.GOOS == "windows" {
		return "synesis.cmd"
	}
	return "synesis"
}

func runBundleVersion(bundle string) error {
	output, err := runBundleCommand(bundle, "version")
	if err != nil || !bytes.Contains(output, []byte("SYNESIS_VERSION=")) {
		return errors.New("bundled synesis version check failed")
	}
	return nil
}

func runBundleDoctor(bundle string) error {
	output, err := runBundleCommand(bundle, "doctor")
	if err != nil || !bytes.Contains(output, []byte("DOCTOR")) {
		return errors.New("bundled synesis doctor check failed")
	}
	return nil
}

func validateBundleVersion(bundle, expected string) error {
	if err := validateBundle(bundle); err != nil {
		return err
	}
	data, err := os.ReadFile(filepath.Join(bundle, "VERSION"))
	if err != nil || strings.TrimSpace(string(data)) != expected {
		return errors.New("bundle VERSION does not match manifest")
	}
	return nil
}

func runBundleCommand(bundle string, args ...string) ([]byte, error) {
	launcher := filepath.Join(bundle, "bin", launcherName())
	command := []string{launcher}
	if runtime.GOOS == "windows" {
		command = []string{"cmd.exe", "/d", "/c", "call", launcher}
	}
	command = append(command, args...)
	return exec.Command(command[0], command[1:]...).CombinedOutput()
}

func runDoctor(args []string) error {
	flags := flag.NewFlagSet("doctor", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if err := validateBundle(paths.root); err != nil {
		return err
	}
	if err := runBundleDoctor(paths.root); err != nil {
		return err
	}
	version := installedVersion(paths)
	pathStatus := "MISSING"
	if userPathContains(paths) {
		pathStatus = "PASS"
	}
	fmt.Printf("INSTALL_ROOT=%s\nINSTALL_LAYOUT=FLAT_STABLE\nINSTALLED_VERSION=%s\nLAUNCHER=%s\nPATH_STATUS=%s\nDOCTOR_RESULT=PASS\n", paths.root, version, paths.launcher, pathStatus)
	return nil
}

func runUninstall(args []string) error {
	flags := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if filepath.Base(paths.root) != "Synesis" && *installDir == "" {
		return errors.New("refusing unexpected installation root")
	}
	if err := pathUpdater(paths, false); err != nil {
		return err
	}
	if err := os.RemoveAll(paths.root); err != nil {
		return err
	}
	if err := os.RemoveAll(paths.rollback); err != nil {
		return err
	}
	entries, err := os.ReadDir(filepath.Dir(paths.root))
	if errors.Is(err, os.ErrNotExist) {
		fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true")
		return nil
	}
	if err != nil {
		return err
	}
	prefix := filepath.Base(paths.root) + ".staging-"
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), prefix) {
			if err := os.RemoveAll(filepath.Join(filepath.Dir(paths.root), entry.Name())); err != nil {
				return err
			}
		}
	}
	fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true")
	return nil
}

var pathUpdater = updateUserPath

func updateUserPath(paths installPaths, install bool) error {
	if runtime.GOOS == "windows" {
		return updateWindowsUserPath(paths, install)
	}
	return updateUnixUserPath(paths, install)
}

func userPathContains(paths installPaths) bool {
	if runtime.GOOS == "windows" {
		value, _, err := readWindowsUserPath()
		return err == nil && pathContains(value, paths.bin, true)
	}
	if pathContains(os.Getenv("PATH"), paths.bin, false) {
		return true
	}
	profile, err := os.UserHomeDir()
	if err != nil {
		return false
	}
	data, err := os.ReadFile(filepath.Join(profile, ".profile"))
	return err == nil && strings.Contains(string(data), pathBegin)
}

func updateWindowsUserPath(paths installPaths, install bool) error {
	value, valueType, err := readWindowsUserPath()
	if err != nil {
		return err
	}
	merged := mergePath(value, paths.bin, true, install)
	if merged != value {
		if err := writeWindowsUserPath(merged, valueType); err != nil {
			return err
		}
	}
	os.Setenv("PATH", mergePath(os.Getenv("PATH"), paths.bin, true, install))
	if install {
		fmt.Println("PATH_UPDATE=USER", "PATH_ENTRY="+paths.bin, "NEW_TERMINAL_REQUIRED=true")
	}
	return nil
}

const (
	pathBegin = "# >>> Synesis PATH >>>"
	pathEnd   = "# <<< Synesis PATH <<<"
)

func updateUnixUserPath(paths installPaths, install bool) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	profile := filepath.Join(home, ".profile")
	data, err := os.ReadFile(profile)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	content := string(data)
	updated := removeManagedPathBlock(content)
	if install && !pathContains(os.Getenv("PATH"), paths.bin, false) {
		if updated != "" && !strings.HasSuffix(updated, "\n") {
			updated += "\n"
		}
		updated += pathBegin + "\nexport PATH=\"$PATH:" + shellDoubleQuote(paths.bin) + "\"\n" + pathEnd + "\n"
	}
	if updated != content {
		if err := writeUserFile(profile, []byte(updated)); err != nil {
			return err
		}
	}
	os.Setenv("PATH", mergePath(os.Getenv("PATH"), paths.bin, false, install))
	if install {
		fmt.Println("PATH_UPDATE=USER_PROFILE", "PATH_ENTRY="+paths.bin, "NEW_TERMINAL_REQUIRED=true")
	}
	return nil
}

func removeManagedPathBlock(content string) string {
	for {
		start := strings.Index(content, pathBegin)
		if start < 0 {
			return content
		}
		remainder := content[start:]
		end := strings.Index(remainder, pathEnd)
		if end < 0 {
			return content[:start]
		}
		end += len(pathEnd)
		if end < len(remainder) && remainder[end] == '\n' {
			end++
		}
		content = content[:start] + remainder[end:]
	}
}

func shellDoubleQuote(value string) string {
	return strings.NewReplacer("\\", "\\\\", "\"", "\\\"", "$", "\\$", "`", "\\`").Replace(value)
}

func writeUserFile(path string, data []byte) error {
	mode := os.FileMode(0o644)
	if info, err := os.Stat(path); err == nil {
		mode = info.Mode().Perm()
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".synesis-path-")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		_ = os.Remove(path)
		return os.Rename(temporaryPath, path)
	}
	return nil
}

func readWindowsUserPath() (string, string, error) {
	if runtime.GOOS != "windows" {
		return os.Getenv("PATH"), "", nil
	}
	output, err := exec.Command("reg.exe", "query", `HKCU\Environment`, "/v", "Path").CombinedOutput()
	if err != nil {
		return "", "REG_EXPAND_SZ", nil
	}
	for _, line := range strings.Split(string(output), "\n") {
		line = strings.TrimSpace(line)
		for _, valueType := range []string{"REG_EXPAND_SZ", "REG_SZ"} {
			if index := strings.Index(line, valueType); index >= 0 {
				return strings.TrimSpace(line[index+len(valueType):]), valueType, nil
			}
		}
	}
	return "", "REG_EXPAND_SZ", nil
}

func writeWindowsUserPath(value, valueType string) error {
	if valueType != "REG_SZ" && valueType != "REG_EXPAND_SZ" {
		valueType = "REG_EXPAND_SZ"
	}
	return exec.Command("reg.exe", "add", `HKCU\Environment`, "/v", "Path", "/t", valueType, "/d", value, "/f").Run()
}

func mergePath(value, entry string, windows, add bool) string {
	separator := string(os.PathListSeparator)
	if windows {
		separator = ";"
	}
	entries := strings.Split(value, separator)
	result := make([]string, 0, len(entries)+1)
	found := false
	for _, candidate := range entries {
		if pathEntriesEqual(candidate, entry, windows) {
			if add && !found {
				result = append(result, candidate)
			}
			found = true
			continue
		}
		result = append(result, candidate)
	}
	if add && !found {
		result = append(result, entry)
	}
	if !add && len(result) == 1 && result[0] == "" && value == "" {
		return ""
	}
	return strings.Join(result, separator)
}

func pathContains(value, entry string, windows bool) bool {
	separator := string(os.PathListSeparator)
	if windows {
		separator = ";"
	}
	for _, candidate := range strings.Split(value, separator) {
		if pathEntriesEqual(candidate, entry, windows) {
			return true
		}
	}
	return false
}

func pathEntriesEqual(left, right string, windows bool) bool {
	left = strings.TrimSpace(left)
	right = strings.TrimSpace(right)
	if windows {
		left = strings.TrimRight(strings.ReplaceAll(left, "/", "\\"), "\\")
		right = strings.TrimRight(strings.ReplaceAll(right, "/", "\\"), "\\")
		return strings.EqualFold(left, right)
	}
	return filepath.Clean(left) == filepath.Clean(right)
}

func matchesSHA256(data []byte, expected string) bool {
	digest := sha256.Sum256(data)
	return strings.EqualFold(hex.EncodeToString(digest[:]), expected)
}

// compareVersions orders dotted numeric versions (with an optional
// "-prerelease" suffix), returning -1/0/1 like strings.Compare.
func compareVersions(left, right string) int {
	leftParts, leftPre := splitVersion(left)
	rightParts, rightPre := splitVersion(right)
	for i := 0; i < len(leftParts) || i < len(rightParts); i++ {
		lv, rv := 0, 0
		if i < len(leftParts) {
			lv, _ = strconv.Atoi(leftParts[i])
		}
		if i < len(rightParts) {
			rv, _ = strconv.Atoi(rightParts[i])
		}
		if lv != rv {
			if lv < rv {
				return -1
			}
			return 1
		}
	}
	return comparePrerelease(leftPre, rightPre)
}

// comparePrerelease follows semver precedence: a version with no
// prerelease suffix outranks one with a suffix (e.g. 1.0.0 > 1.0.0-alpha).
func comparePrerelease(left, right string) int {
	if left == right {
		return 0
	}
	if left == "" {
		return 1
	}
	if right == "" {
		return -1
	}
	leftParts, rightParts := strings.Split(left, "."), strings.Split(right, ".")
	for i := 0; i < len(leftParts) && i < len(rightParts); i++ {
		leftNumber, leftErr := strconv.Atoi(leftParts[i])
		rightNumber, rightErr := strconv.Atoi(rightParts[i])
		if leftErr == nil && rightErr == nil && leftNumber != rightNumber {
			if leftNumber < rightNumber {
				return -1
			}
			return 1
		}
		if leftParts[i] != rightParts[i] {
			return strings.Compare(leftParts[i], rightParts[i])
		}
	}
	if len(leftParts) < len(rightParts) {
		return -1
	}
	return 1
}

// splitVersion strips a leading "v", any build metadata ("+..."), and
// splits the remainder into numeric release parts and a prerelease string.
func splitVersion(value string) ([]string, string) {
	value = strings.TrimPrefix(strings.TrimSpace(value), "v")
	value = strings.Split(value, "+")[0]
	parts := strings.SplitN(value, "-", 2)
	return strings.Split(parts[0], "."), func() string {
		if len(parts) == 2 {
			return parts[1]
		}
		return ""
	}()
}

func mustHex(value string) []byte {
	result, err := hex.DecodeString(value)
	if err != nil {
		panic(err)
	}
	return result
}

// newGzipReader is kept behind a package variable so tests can replace it
// without any third-party compression package.
var newGzipReader = func(data io.Reader) (io.ReadCloser, error) { return gzip.NewReader(data) }
