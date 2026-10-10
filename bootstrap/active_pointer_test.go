package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestActivePointerRejectsTraversal(t *testing.T) {
	paths, err := installationPaths(filepath.Join(t.TempDir(), "install"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(paths.root, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(paths.current, []byte(`{"schemaVersion":1,"version":"0.1.0","payloadDirectory":"../escape","manifestHash":"`+strings.Repeat("0", 64)+`"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := readPointer(paths, paths.current); err == nil {
		t.Fatal("pointer traversal accepted")
	}
}
