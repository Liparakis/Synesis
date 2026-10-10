package activepointer

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/layout"
)

func TestActivePointerRejectsTraversal(t *testing.T) {
	paths, err := layout.Resolve(filepath.Join(t.TempDir(), "install"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(paths.Root, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(paths.Current, []byte(`{"schemaVersion":1,"version":"0.1.0","payloadDirectory":"../escape","manifestHash":"`+strings.Repeat("0", 64)+`"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Read(paths, paths.Current); err == nil {
		t.Fatal("pointer traversal accepted")
	}
}
