// Package bundlearchive reads and writes the bundle archives the installer handles.
// Archives are untrusted input: extraction rejects traversal, links, special
// files, and archives that expand beyond fixed bounds.
package bundlearchive

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	// MaxExtractedBytes bounds both the archive read into memory and the total
	// size of the files it declares.
	MaxExtractedBytes = int64(512 << 20)
	maxArchiveFiles   = 10000 // upper bound on entries; guards against archive bombs
)

// Extract dispatches to the ZIP or tar.gz extractor based on the leading
// bytes, after capping the total decoded size read into memory.
func Extract(data io.Reader, destination string) error {
	bytesData, err := io.ReadAll(io.LimitReader(data, MaxExtractedBytes+1))
	if err != nil || int64(len(bytesData)) > MaxExtractedBytes {
		return errors.New("archive is too large")
	}
	if bytes.HasPrefix(bytesData, []byte("PK\x03\x04")) {
		return extractZip(bytesData, destination)
	}
	return extractTarGz(bytesData, destination)
}

// ZipDirectory archives the regular files below root. Anything that is not a
// regular file, symlinks included, is refused.
func ZipDirectory(root string) ([]byte, error) {
	buffer := new(bytes.Buffer)
	writer := zip.NewWriter(buffer)
	err := filepath.Walk(root, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if info.IsDir() {
			return nil
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return errors.New("local bundle contains unsupported entry")
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		header, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		header.Name = filepath.ToSlash(relative)
		header.Method = zip.Deflate
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return err
		}
		input, err := os.Open(path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(entry, input)
		closeErr := input.Close()
		if copyErr != nil {
			return copyErr
		}
		return closeErr
	})
	if err != nil {
		_ = writer.Close()
		return nil, err
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

// Flatten unwraps a single top-level directory some archive formats add (for
// example "synesis-0.2.0/..."), so the bundle root is the same however the
// archive was packaged.
func Flatten(staging string) string {
	entries, _ := os.ReadDir(staging)
	if len(entries) == 1 && entries[0].IsDir() {
		return filepath.Join(staging, entries[0].Name())
	}
	return staging
}

// SafeName rejects absolute paths and "../" traversal in an archive entry
// name, and normalizes it to the host's path separator.
func SafeName(name string) (string, error) {
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

// reserveExtractedBytes is the shared zip/tar-bomb defense: it accounts an
// entry's declared size against the running total and rejects the archive
// if the cumulative extracted size would exceed MaxExtractedBytes.
func reserveExtractedBytes(total *int64, size int64) error {
	if size > MaxExtractedBytes-*total {
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
		name, err := SafeName(entry.Name)
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
	reader, err := gzip.NewReader(bytes.NewReader(data))
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
		name, err := SafeName(header.Name)
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
