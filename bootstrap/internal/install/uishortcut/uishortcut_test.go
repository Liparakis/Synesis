package uishortcut

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestShortcutPointsAtTheUiAddressAndIsRemovedOnlyWhenOurs(t *testing.T) {
	directory := t.TempDir()
	file := filepath.Join(directory, fileName)
	if Exists(directory) {
		t.Fatal("an empty folder holds the shortcut")
	}
	if err := Remove(directory); err != nil {
		t.Fatalf("removing a missing shortcut: %v", err)
	}
	if err := Create(directory, `C:\Synesis\bin\synesis-activate.exe`); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range []string{"[InternetShortcut]", "URL=synesis://ui", `IconFile=C:\Synesis\bin\synesis-activate.exe`} {
		if !strings.Contains(string(data), line+"\r\n") {
			t.Fatalf("shortcut lacks the line %q: %q", line, data)
		}
	}
	if !Exists(directory) {
		t.Fatal("the written shortcut is not recognised")
	}
	if err := Remove(directory); err != nil || Exists(directory) {
		t.Fatalf("shortcut not removed: %v", err)
	}

	// A user's own file under the same name is not ours to delete.
	if err := os.WriteFile(file, []byte("[InternetShortcut]\r\nURL=https://example.test/\r\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := Remove(directory); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(file); err != nil {
		t.Fatal("a file that points elsewhere was removed")
	}
}
