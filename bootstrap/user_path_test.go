package main

import (
	"os"
	"runtime"
	"testing"
)

func TestWindowsUserPathMergeIsCaseInsensitiveAndLossless(t *testing.T) {
	original := `C:\Windows\System32;C:\Tools\;c:\Users\ME\AppData\Local\Synesis\bin;C:\Other`
	merged := mergePath(original, `C:\Users\me\AppData\Local\Synesis\bin\`, true, true)
	if merged != original {
		t.Fatalf("duplicate Windows PATH entry changed value: %q", merged)
	}
	removed := mergePath(merged, `c:/users/me/appdata/local/synesis/bin`, true, false)
	if removed != `C:\Windows\System32;C:\Tools\;C:\Other` {
		t.Fatalf("PATH removal changed unrelated entries: %q", removed)
	}
}

func TestWindowsUserPathUpdateNotifiesExistingShells(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows PATH notification is platform-specific")
	}
	originalPath := os.Getenv("PATH")
	t.Cleanup(func() { _ = os.Setenv("PATH", originalPath) })

	paths := installPaths{bin: `C:\Users\ME\AppData\Local\Synesis\bin`}
	previousRead := readWindowsUserPath
	previousWrite := writeWindowsUserPath
	previousNotify := notifyEnvironmentChanged
	t.Cleanup(func() {
		readWindowsUserPath = previousRead
		writeWindowsUserPath = previousWrite
		notifyEnvironmentChanged = previousNotify
	})

	readWindowsUserPath = func() (string, string, error) {
		return `C:\Windows\System32`, "REG_SZ", nil
	}
	var written string
	writeWindowsUserPath = func(value, _ string) error {
		written = value
		return nil
	}
	notified := false
	notifyEnvironmentChanged = func() { notified = true }

	if err := updateWindowsUserPath(paths, true); err != nil {
		t.Fatal(err)
	}
	if written != `C:\Windows\System32;C:\Users\ME\AppData\Local\Synesis\bin` {
		t.Fatalf("unexpected user PATH: %q", written)
	}
	if !notified {
		t.Fatal("Windows environment change was not broadcast")
	}
}
