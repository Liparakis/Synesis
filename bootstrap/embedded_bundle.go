package main

import (
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
	"synesis/bootstrap/internal/payload"
)

const selfExtractMagic = "SYNESIS_SELF_EXTRACT_V1\n"

// hasEmbeddedBundle identifies the single-file installer produced by the
// release build. A normal installer inside an extracted bundle has no footer
// and continues through the ordinary bundle path.
func hasEmbeddedBundle() bool {
	file, _, _, err := embeddedPayloadBounds()
	if file != nil {
		_ = file.Close()
	}
	return err == nil
}

// runEmbeddedCommand extracts the appended platform bundle and delegates the
// requested command to the installer inside it. Install and repair receive an
// implicit local bundle argument so they never need a network manifest.
func runEmbeddedCommand(args []string) error {
	bundle, cleanup, err := extractEmbeddedBundle()
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

func extractEmbeddedBundle() (string, func(), error) {
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
	if err := validateBundle(bundle); err != nil {
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
	for _, relative := range []string{"bin/synesis", "bin/synesis-installer", "bin/synesis-mcp", "runtime/bin/java"} {
		if err := os.Chmod(filepath.Join(bundle, relative), 0o755); err != nil {
			return err
		}
	}
	return nil
}
