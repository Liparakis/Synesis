package bundlearchive

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestRejectsArchiveTraversal(t *testing.T) {
	buffer := new(bytes.Buffer)
	writer := zip.NewWriter(buffer)
	entry, err := writer.Create("../escape")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("bad"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := Extract(bytes.NewReader(buffer.Bytes()), t.TempDir()); err == nil {
		t.Fatal("traversal accepted")
	}
	buffer.Reset()
	writer = zip.NewWriter(buffer)
	absolute := "/escape"
	if runtime.GOOS == "windows" {
		absolute = "C:/escape"
	}
	entry, err = writer.Create(absolute)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("bad"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := Extract(bytes.NewReader(buffer.Bytes()), t.TempDir()); err == nil {
		t.Fatal("absolute path accepted")
	}
}

func TestTarGzPreservesExecutableLauncherSafely(t *testing.T) {
	archive := new(bytes.Buffer)
	gzipWriter := gzip.NewWriter(archive)
	tarWriter := tar.NewWriter(gzipWriter)
	data := []byte("#!/bin/sh\necho SYNESIS_VERSION=fixture\n")
	if err := tarWriter.WriteHeader(&tar.Header{Name: "bin/synesis", Mode: 0o755, Size: int64(len(data))}); err != nil {
		t.Fatal(err)
	}
	if _, err := tarWriter.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := tarWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gzipWriter.Close(); err != nil {
		t.Fatal(err)
	}
	destination := t.TempDir()
	if err := Extract(bytes.NewReader(archive.Bytes()), destination); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(destination, "bin", "synesis"))
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o111 == 0 {
		t.Fatal("executable bit was not preserved")
	}
}
