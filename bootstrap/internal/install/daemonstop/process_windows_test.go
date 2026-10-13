//go:build windows

package daemonstop

import (
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestStandInProcess is not a test: it is what the stand-in process below runs.
func TestStandInProcess(t *testing.T) {
	if os.Getenv("SYNESIS_STAND_IN") != "1" {
		t.Skip("only run as a stand-in process")
	}
	time.Sleep(time.Minute)
}

// startStandIn runs a copy of this test program from its own folder, so that
// folder can play an installation root holding nothing else.
func startStandIn(t *testing.T) (root string, exited chan struct{}) {
	t.Helper()
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	root = t.TempDir()
	program := filepath.Join(root, "runtime", "stand-in.exe")
	if err := os.MkdirAll(filepath.Dir(program), 0o755); err != nil {
		t.Fatal(err)
	}
	source, err := os.Open(self)
	if err != nil {
		t.Fatal(err)
	}
	target, err := os.Create(program)
	if err != nil {
		t.Fatal(err)
	}
	_, copyErr := io.Copy(target, source)
	source.Close()
	if err := target.Close(); err != nil || copyErr != nil {
		t.Fatal(err, copyErr)
	}
	process := exec.Command(program, "-test.run=^TestStandInProcess$")
	process.Env = append(os.Environ(), "SYNESIS_STAND_IN=1")
	if err := process.Start(); err != nil {
		t.Fatal(err)
	}
	exited = make(chan struct{})
	go func() { process.Wait(); close(exited) }()
	t.Cleanup(func() { process.Process.Kill(); <-exited })
	return root, exited
}

// Uninstall ends whatever still runs from the installation, and nothing else.
func TestEveryProcessRunningFromTheInstallationIsEnded(t *testing.T) {
	previous := exitTimeout
	exitTimeout = 5 * time.Second
	defer func() { exitTimeout = previous }()
	root, exited := startStandIn(t)
	_, bystander := startStandIn(t)
	for deadline := time.Now().Add(5 * time.Second); len(processIDs()) == 0 && time.Now().Before(deadline); {
		time.Sleep(20 * time.Millisecond)
	}
	if err := endProcessesWithin(root); err != nil {
		t.Fatal(err)
	}
	select {
	case <-exited:
	case <-time.After(5 * time.Second):
		t.Fatal("a process running from the installation survived")
	}
	select {
	case <-bystander:
		t.Fatal("a process outside the installation was ended")
	default:
	}
	if err := endProcessesWithin(root); err != nil {
		t.Fatalf("an installation nothing runs from: %v", err)
	}
}

// A daemon from a build that predates the stop request is ended directly, once
// its program file is seen to lie inside the installation.
func TestStopEndsAnOlderDaemonThatRunsFromTheInstallation(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	if image := processImage(os.Getpid()); !strings.EqualFold(image, self) {
		t.Fatalf("program file of this process = %q, want %q", image, self)
	}
	// A stand-in that just stays alive; its folder plays the installation root.
	daemon := exec.Command("ping.exe", "-n", "60", "127.0.0.1")
	if err := daemon.Start(); err != nil {
		t.Skip("no long-running stand-in process available: " + err.Error())
	}
	exited := make(chan struct{})
	go func() { daemon.Wait(); close(exited) }()
	defer daemon.Process.Kill()
	image := processImage(daemon.Process.Pid)
	if image == "" {
		t.Fatal("program file of the stand-in daemon is unknown")
	}
	endpointFile, _ := fakeDaemon(t, `{"ok":false,"error":"REQUEST_REJECTED"}`, false, daemon.Process.Pid)

	previous := exitTimeout
	exitTimeout = 300 * time.Millisecond
	if err := stop(endpointFile, t.TempDir()); err == nil {
		t.Fatal("a daemon outside the installation was reported stopped")
	}
	select {
	case <-exited:
		t.Fatal("a daemon outside the installation was ended")
	default:
	}
	exitTimeout = previous

	if err := stop(endpointFile, filepath.Dir(image)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-exited:
	case <-time.After(5 * time.Second):
		t.Fatal("the older daemon inside the installation kept running")
	}
}
