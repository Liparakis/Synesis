package daemonstop

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// fakeDaemon answers its first request the way a daemon would and reports what
// it was sent. Its endpoint record names the process pid.
func fakeDaemon(t *testing.T, reply string, stopAfterReply bool, pid int) (endpointFile string, received chan map[string]string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	received = make(chan map[string]string, 1)
	go func() {
		for first := true; ; {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			_ = connection.SetDeadline(time.Now().Add(time.Second))
			line, _ := bufio.NewReader(connection).ReadBytes('\n')
			var request map[string]string
			if first && json.Unmarshal(line, &request) == nil {
				first = false
				received <- request
				fmt.Fprintln(connection, reply)
				if stopAfterReply {
					connection.Close()
					listener.Close()
					return
				}
			}
			connection.Close()
		}
	}()
	endpointFile = filepath.Join(t.TempDir(), "endpoint.json")
	record := fmt.Sprintf(`{"protocolVersion":1,"port":%d,"token":"secret","pid":%d}`, listener.Addr().(*net.TCPAddr).Port, pid)
	if err := os.WriteFile(endpointFile, []byte(record), 0o600); err != nil {
		t.Fatal(err)
	}
	return endpointFile, received
}

func TestStopAsksTheDaemonWithItsTokenAndWaitsForItToGo(t *testing.T) {
	previous := running
	running = func(int) bool { return false }
	defer func() { running = previous }()
	endpointFile, received := fakeDaemon(t, `{"ok":true,"state":"STOPPING"}`, true, os.Getpid())
	if err := stop(endpointFile, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if request := <-received; request["token"] != "secret" || request["operation"] != "SHUTDOWN" {
		t.Fatalf("request = %v", request)
	}
}

func TestStopIsQuietWhenNoDaemonRuns(t *testing.T) {
	if err := stop(filepath.Join(t.TempDir(), "missing.json"), t.TempDir()); err != nil {
		t.Fatalf("missing endpoint file: %v", err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	stale := filepath.Join(t.TempDir(), "endpoint.json")
	for name, record := range map[string]string{
		"stale":   fmt.Sprintf(`{"port":%d,"token":"secret","pid":%d}`, port, os.Getpid()),
		"damaged": "not json",
	} {
		if err := os.WriteFile(stale, []byte(record), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := stop(stale, t.TempDir()); err != nil {
			t.Fatalf("%s endpoint file: %v", name, err)
		}
	}
}

// A daemon that does not know the request is ended directly only when it runs
// from the installation. This test's own process does not, and must survive.
func TestStopNeverEndsAProcessOutsideTheInstallation(t *testing.T) {
	previous := exitTimeout
	exitTimeout = 300 * time.Millisecond
	defer func() { exitTimeout = previous }()
	for _, reply := range []string{`{"ok":false,"error":"REQUEST_REJECTED"}`, `{"ok":true,"state":"STOPPING"}`} {
		endpointFile, _ := fakeDaemon(t, reply, false, os.Getpid())
		if err := stop(endpointFile, t.TempDir()); err == nil {
			t.Fatalf("a daemon that answered %s and kept running was reported stopped", reply)
		}
	}
}
