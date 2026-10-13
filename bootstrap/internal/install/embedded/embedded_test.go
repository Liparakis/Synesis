package embedded

import (
	"archive/zip"
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

func TestArchivedVersionReadsOnlyTheBundleRootVersion(t *testing.T) {
	archive := func(entries map[string]string) *bytes.Reader {
		buffer := new(bytes.Buffer)
		writer := zip.NewWriter(buffer)
		for name, content := range entries {
			entry, err := writer.Create(name)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := entry.Write([]byte(content)); err != nil {
				t.Fatal(err)
			}
		}
		if err := writer.Close(); err != nil {
			t.Fatal(err)
		}
		return bytes.NewReader(buffer.Bytes())
	}
	for name, test := range map[string]struct {
		entries map[string]string
		want    string
	}{
		"wrapped bundle":                    {map[string]string{"synesis-1.2.0/VERSION": "1.2.0\r\n", "synesis-1.2.0/bin/synesis.cmd": "x"}, "1.2.0"},
		"flat bundle":                       {map[string]string{"VERSION": "1.3.0\n"}, "1.3.0"},
		"nested file":                       {map[string]string{"synesis-1.2.0/runtime/VERSION": "21"}, ""},
		"flat bundle with a deeper version": {map[string]string{"bin/VERSION": "9.9.9", "VERSION": "1.3.0", "runtime/VERSION": "21"}, "1.3.0"},
		"flat bundle without its own":       {map[string]string{"bin/VERSION": "9.9.9", "runtime/release": "x"}, ""},
		"no version":                        {map[string]string{"synesis-1.2.0/bin/synesis.cmd": "x"}, ""},
	} {
		reader := archive(test.entries)
		if got := archivedVersion(reader, reader.Size()); got != test.want {
			t.Fatalf("%s: version = %q, want %q", name, got, test.want)
		}
	}
	if got := archivedVersion(bytes.NewReader([]byte("not an archive")), 14); got != "" {
		t.Fatalf("damaged archive reported version %q", got)
	}
}
