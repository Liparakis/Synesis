// Package embedded runs the single-file installer produced by the release
// build: a native installer with a platform bundle appended to it.
package embedded

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/payload"
)

const selfExtractMagic = "SYNESIS_SELF_EXTRACT_V1\n"

// HasBundle identifies the single-file installer produced by the
// release build. A normal installer inside an extracted bundle has no footer
// and continues through the ordinary bundle path.
func HasBundle() bool {
	file, _, _, err := embeddedPayloadBounds()
	if file != nil {
		_ = file.Close()
	}
	return err == nil
}

// RunCommand extracts the appended platform bundle and delegates the
// requested command to the installer inside it. Install and repair receive an
// implicit local bundle argument so they never need a network manifest.
func RunCommand(args []string) error {
	bundle, cleanup, err := Extract()
	if err != nil {
		return err
	}
	defer cleanup()
	installer := filepath.Join(bundle, "bin", payload.InstallerName())
	if len(args) > 0 && (args[0] == "install" || args[0] == "repair") && !hasBundleArgument(args[1:]) {
		args = append(append([]string(nil), args...), "--bundle", bundle)
	}
	command := exec.Command(installer, args...)
	command.Stdin = os.Stdin
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Dir = bundle
	return command.Run()
}

func hasBundleArgument(args []string) bool {
	for _, arg := range args {
		if arg == "--bundle" || strings.HasPrefix(arg, "--bundle=") {
			return true
		}
	}
	return false
}

// embeddedPayloadBounds returns the appended archive offset and size. The
// footer is written as [archive][magic][big-endian uint64 archive size].
func embeddedPayloadBounds() (*os.File, int64, int64, error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, 0, 0, err
	}
	file, err := os.Open(executable)
	if err != nil {
		return nil, 0, 0, err
	}
	stat, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, 0, 0, err
	}
	payloadOffset, payloadSize, err := readEmbeddedPayloadBounds(file, stat.Size())
	if err != nil {
		file.Close()
		return nil, 0, 0, err
	}
	return file, payloadOffset, payloadSize, nil
}

func readEmbeddedPayloadBounds(reader io.ReaderAt, fileSize int64) (int64, int64, error) {
	footerSize := int64(len(selfExtractMagic) + 8)
	if fileSize < footerSize {
		return 0, 0, errors.New("self-extract footer missing")
	}
	footer := make([]byte, footerSize)
	if _, err := reader.ReadAt(footer, fileSize-footerSize); err != nil {
		return 0, 0, err
	}
	if !bytes.Equal(footer[:len(selfExtractMagic)], []byte(selfExtractMagic)) {
		return 0, 0, errors.New("self-extract footer missing")
	}
	payloadSize := int64(binary.BigEndian.Uint64(footer[len(selfExtractMagic):]))
	payloadOffset := fileSize - footerSize - payloadSize
	if payloadSize <= 0 || payloadSize > bundlearchive.MaxExtractedBytes || payloadOffset < 0 {
		return 0, 0, errors.New("self-extract payload bounds invalid")
	}
	return payloadOffset, payloadSize, nil
}

// BundleVersion reads the version the appended bundle declares, or "" when it
// cannot be read without unpacking. It is display text: activation verifies the
// unpacked bundle, never this value.
func BundleVersion() string {
	file, offset, size, err := embeddedPayloadBounds()
	if err != nil {
		return ""
	}
	defer file.Close()
	return archivedVersion(io.NewSectionReader(file, offset, size), size)
}

func archivedVersion(reader io.ReaderAt, size int64) string {
	archive, err := zip.NewReader(reader, size)
	if err != nil {
		return ""
	}
	// The bundle root is the archive root, or its one top-level directory.
	name, roots := "VERSION", map[string]bool{}
	for _, entry := range archive.File {
		root, _, _ := strings.Cut(entry.Name, "/")
		roots[root] = true
		if len(roots) == 1 && !roots["VERSION"] {
			name = root + "/VERSION"
		}
	}
	if len(roots) > 1 || roots["VERSION"] {
		name = "VERSION"
	}
	version, err := archive.Open(name)
	if err != nil {
		return ""
	}
	defer version.Close()
	data, _ := io.ReadAll(io.LimitReader(version, 256))
	return strings.TrimSpace(string(data))
}

// Extract unpacks and validates the appended bundle in a temporary directory.
// The returned cleanup removes it.
func Extract() (string, func(), error) {
	file, offset, size, err := embeddedPayloadBounds()
	if err != nil {
		return "", func() {}, err
	}
	defer file.Close()
	staging, err := os.MkdirTemp("", "synesis-self-extract-")
	if err != nil {
		return "", func() {}, err
	}
	cleanup := func() { _ = os.RemoveAll(staging) }
	if err := bundlearchive.Extract(io.NewSectionReader(file, offset, size), staging); err != nil {
		cleanup()
		return "", func() {}, err
	}
	bundle := bundlearchive.Flatten(staging)
	if err := restoreBundlePermissions(bundle); err != nil {
		cleanup()
		return "", func() {}, err
	}
	if err := bundlecheck.Validate(bundle); err != nil {
		cleanup()
		return "", func() {}, err
	}
	return bundle, cleanup, nil
}

// restoreBundlePermissions makes the executable files usable after archive
// extraction on Unix hosts. Archive formats and extractors do not always
// preserve executable mode bits, while Windows does not use them.
func restoreBundlePermissions(bundle string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	executables := []string{"bin/synesis", "bin/synesis-installer", "bin/synesis-mcp", "bin/synesis-folder-picker"}
	// The runtime starts every child process through jspawnhelper and makes its TLS material
	// with keytool. With java alone executable an installed Synesis could start nothing, Git
	// included.
	tools, err := os.ReadDir(filepath.Join(bundle, "runtime", "bin"))
	if err != nil {
		return err
	}
	for _, tool := range tools {
		executables = append(executables, "runtime/bin/"+tool.Name())
	}
	if _, err := os.Stat(filepath.Join(bundle, "runtime", "lib", "jspawnhelper")); err == nil {
		executables = append(executables, "runtime/lib/jspawnhelper")
	}
	for _, relative := range executables {
		if err := os.Chmod(filepath.Join(bundle, relative), 0o755); err != nil {
			return err
		}
	}
	return nil
}
