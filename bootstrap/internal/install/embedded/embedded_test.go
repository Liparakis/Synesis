package embedded

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestEmbeddedPayloadFooterBounds(t *testing.T) {
	payload := []byte("platform bundle payload")
	data := append([]byte("native installer"), payload...)
	data = append(data, []byte(selfExtractMagic)...)
	footer := make([]byte, 8)
	binary.BigEndian.PutUint64(footer, uint64(len(payload)))
	data = append(data, footer...)

	offset, size, err := readEmbeddedPayloadBounds(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data[offset:offset+size], payload) {
		t.Fatalf("embedded payload mismatch: got %q, want %q", data[offset:offset+size], payload)
	}
}

func TestRestoreBundlePermissionsMakesTheRuntimeAbleToStartProcesses(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not use executable mode bits")
	}
	bundle := t.TempDir()
	executables := []string{"bin/synesis", "bin/synesis-installer", "bin/synesis-mcp", "bin/synesis-folder-picker",
		"runtime/bin/java", "runtime/bin/keytool", "runtime/lib/jspawnhelper"}
	for _, relative := range append([]string{"runtime/lib/modules"}, executables...) {
		path := filepath.Join(bundle, relative)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := restoreBundlePermissions(bundle); err != nil {
		t.Fatal(err)
	}
	for _, relative := range executables {
		if info, err := os.Stat(filepath.Join(bundle, relative)); err != nil || info.Mode()&0o111 == 0 {
			t.Fatalf("%s is not executable", relative)
		}
	}
	if info, err := os.Stat(filepath.Join(bundle, "runtime/lib/modules")); err != nil || info.Mode()&0o111 != 0 {
		t.Fatal("a runtime data file became executable")
	}
}
