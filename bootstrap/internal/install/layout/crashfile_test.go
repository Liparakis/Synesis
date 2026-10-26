package layout

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestCrashFileOptionPointsIntoSynesisStateNotTheWorkingDirectory(t *testing.T) {
	state := t.TempDir()
	t.Setenv("LOCALAPPDATA", state)
	t.Setenv("XDG_STATE_HOME", state)
	t.Setenv("HOME", state)
	t.Setenv("USERPROFILE", state)

	option := CrashFileOption()

	if !strings.HasPrefix(option, "-XX:ErrorFile=") || !strings.HasSuffix(option, "hs_err_pid%p.log") {
		t.Fatalf("unexpected option %q", option)
	}
	path := strings.TrimPrefix(option, "-XX:ErrorFile=")
	if !filepath.IsAbs(path) {
		t.Fatalf("the crash file must not depend on the working directory: %q", path)
	}
	if runtime.GOOS != "darwin" && !strings.HasPrefix(path, state) {
		t.Fatalf("crash file %q is outside the state folder %q", path, state)
	}
	if info, err := os.Stat(filepath.Dir(path)); err != nil || !info.IsDir() {
		t.Fatalf("the crash folder must exist so the JVM can write into it: %v", err)
	}
}
