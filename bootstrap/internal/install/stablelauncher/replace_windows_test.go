//go:build windows

package stablelauncher

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"synesis/bootstrap/internal/install/installfs"
)

const holdImageVariable = "SYNESIS_TEST_HOLD_LAUNCHER_IMAGE"

// A provider session keeps its MCP server, and therefore the launcher image,
// open. Updating must still put the new launcher under the stable name without
// disturbing the server that is running.
func TestLauncherHeldOpenByARunningProcessIsReplaced(t *testing.T) {
	if os.Getenv(holdImageVariable) == "1" {
		time.Sleep(2 * time.Minute)
		return
	}
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	image, err := os.ReadFile(self)
	if err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "synesis-mcp.exe")
	if err := os.WriteFile(target, image, 0o755); err != nil {
		t.Fatal(err)
	}
	running := exec.Command(target, "-test.run=^TestLauncherHeldOpenByARunningProcessIsReplaced$")
	running.Env = append(os.Environ(), holdImageVariable+"=1")
	if err := running.Start(); err != nil {
		t.Fatal(err)
	}
	exited := make(chan struct{})
	go func() {
		_ = running.Wait()
		close(exited)
	}()
	stopped := false
	stop := func() {
		if !stopped {
			stopped = true
			_ = running.Process.Kill()
			<-exited
		}
	}
	defer stop()

	if err := installfs.AtomicWrite(target, []byte("overwrite")); err == nil {
		t.Fatal("the precondition does not hold: an open image was overwritten in place")
	}
	if err := installStableLauncher(target, []byte("new-launcher")); err != nil {
		t.Fatalf("an open launcher must be replaceable: %v", err)
	}
	if data, err := os.ReadFile(target); err != nil || string(data) != "new-launcher" {
		t.Fatalf("stable name holds %q, %v", data, err)
	}
	select {
	case <-exited:
		t.Fatal("replacing the launcher ended the process that was running from it")
	default:
	}
	aside, err := filepath.Glob(target + replacedLauncherSuffix + "*")
	if err != nil || len(aside) != 1 {
		t.Fatalf("expected the open image to be kept aside once, found %v, %v", aside, err)
	}

	stop()
	if err := installStableLauncher(target, []byte("new-launcher")); err != nil {
		t.Fatal(err)
	}
	if aside, _ := filepath.Glob(target + replacedLauncherSuffix + "*"); len(aside) != 0 {
		t.Fatalf("a released image must be removed by the next installation, found %v", aside)
	}
}
