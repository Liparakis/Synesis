// Command synesis-bootstrap is the self-updating installer for the Synesis
// CLI. It downloads a signed release manifest, verifies and extracts the
// platform-specific artifact, and manages the "current" version pointer and
// launcher script used by end users.
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
	root, versions, current, launcher string
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
	if operation == "update" {
		current, _ := os.ReadFile(paths.current)
		if strings.TrimSpace(string(current)) != "" && compareVersions(m.Version, strings.TrimSpace(string(current))) <= 0 {
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

	// The launcher lives outside `root` on Unix (typically ~/.local/bin,
	// usually already on $PATH); the script it contains resolves `root`
	// on its own via an absolute path, so the launcher's own location
	// doesn't need to be relative to root. Windows has no equivalent PATH
	// convention, so its launcher lives inside root/bin instead.
	var launcher string
	if runtime.GOOS == "windows" {
		launcher = filepath.Join(root, "bin", "synesis.cmd")
	} else {
		home, err := os.UserHomeDir()
		if err != nil {
			return installPaths{}, err
		}
		launcher = filepath.Join(home, ".local", "bin", "synesis")
	}
	return installPaths{
		root:     root,
		versions: filepath.Join(root, "versions"),
		current:  filepath.Join(root, "current"),
		launcher: launcher,
	}, nil
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
// string, since the version is later used as a path component under
// paths.versions.
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

// activate extracts, validates, and atomically swaps in a new version.
func activate(paths installPaths, version string, archive []byte) error {
	if err := os.MkdirAll(paths.versions, 0o755); err != nil {
		return err
	}
	staging := filepath.Join(paths.versions, version+".staging")
	_ = os.RemoveAll(staging)
	if err := os.MkdirAll(staging, 0o755); err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	if err := extractArchive(bytes.NewReader(archive), staging); err != nil {
		return err
	}
	bundle := flattenBundle(staging)
	if err := validateBundle(bundle); err != nil {
		return err
	}
	final := filepath.Join(paths.versions, version)
	_ = os.RemoveAll(final)
	if err := os.Rename(bundle, final); err != nil {
		return err
	}
	if err := replacePointer(paths.current, version); err != nil {
		return err
	}
	return writeLauncher(paths)
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
	for _, relative := range []string{"bin", "runtime", "VERSION"} {
		if _, err := os.Stat(filepath.Join(bundle, relative)); err != nil {
			return fmt.Errorf("bundle missing %s", relative)
		}
	}
	return runBundleVersion(bundle)
}

func runBundleVersion(bundle string) error {
	var command []string
	if runtime.GOOS == "windows" {
		command = []string{"cmd.exe", "/c", filepath.Join(bundle, "bin", "synesis.cmd"), "version"}
	} else {
		command = []string{filepath.Join(bundle, "bin", "synesis"), "version"}
	}
	output, err := exec.Command(command[0], command[1:]...).CombinedOutput()
	if err != nil || !bytes.Contains(output, []byte("SYNESIS_VERSION=")) {
		return errors.New("bundled synesis version check failed")
	}
	return nil
}

// replacePointer writes the "current version" file via write-temp-then-rename,
// so a crash mid-write can never leave `current` truncated or corrupt.
func replacePointer(path, version string) error {
	temporary := path + ".staging"
	if err := os.WriteFile(temporary, []byte(version+"\n"), 0o644); err != nil {
		return err
	}
	if err := os.Rename(temporary, path); err != nil {
		_ = os.Remove(path)
		return os.Rename(temporary, path)
	}
	return nil
}

// writeLauncher (re)writes the small script that resolves the "current"
// pointer at run time and execs the matching version's real binary.
func writeLauncher(paths installPaths) error {
	if err := os.MkdirAll(filepath.Dir(paths.launcher), 0o755); err != nil {
		return err
	}
	if runtime.GOOS == "windows" {
		return os.WriteFile(paths.launcher, []byte("@echo off\r\nset ROOT=%~dp0..\r\nset /p VERSION=<\"%ROOT%\\current\"\r\ncall \"%ROOT%\\versions\\%VERSION%\\bin\\synesis.cmd\" %*\r\n"), 0o644)
	}
	rootLiteral := strings.ReplaceAll(paths.root, "'", "'\\''")
	content := "#!/bin/sh\nROOT='" + rootLiteral + "'\nVERSION=$(cat \"$ROOT/current\")\nexec \"$ROOT/versions/$VERSION/bin/synesis\" \"$@\"\n"
	return os.WriteFile(paths.launcher, []byte(content), 0o755)
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
	version, err := os.ReadFile(paths.current)
	if err != nil {
		return errors.New("CURRENT_POINTER=FAIL")
	}
	active := filepath.Join(paths.versions, strings.TrimSpace(string(version)))
	if _, err := os.Stat(paths.launcher); err != nil {
		return errors.New("LAUNCHER=FAIL")
	}
	if err := validateBundle(active); err != nil {
		return err
	}
	fmt.Printf("INSTALL_ROOT=%s\nCURRENT_VERSION=%s\nDOCTOR_RESULT=PASS\n", paths.root, strings.TrimSpace(string(version)))
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
	if err := os.RemoveAll(paths.versions); err != nil {
		return err
	}
	_ = os.Remove(paths.current)
	_ = os.Remove(paths.launcher)
	fmt.Println("UNINSTALL_RESULT=SUCCESS", "USER_PROJECTS_PRESERVED=true")
	return nil
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
