package payload

import (
	"path/filepath"
	"testing"
)

func TestValidDirectoryRejectsTraversalAndSeparators(t *testing.T) {
	for _, value := range []string{"", ".", "..", "../escape", "a..b", "a/b", `a\b`} {
		if ValidDirectory(value) {
			t.Fatalf("%q accepted", value)
		}
	}
	if !ValidDirectory("0.1.0-dev.1-0123456789ab") {
		t.Fatal("plain payload directory rejected")
	}
}

func TestPathWithin(t *testing.T) {
	root := t.TempDir()
	if !PathWithin(root, filepath.Join(root, "a", "b")) || PathWithin(root, filepath.Join(root, "..", "x")) {
		t.Fatal("path containment wrong")
	}
}
