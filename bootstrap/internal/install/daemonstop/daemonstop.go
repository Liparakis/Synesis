// Package daemonstop stops the per-user Synesis daemon before the installer
// replaces or removes the files it runs from. The daemon outlives every
// command, so without this an update keeps serving the old build and an
// uninstall leaves a process behind.
package daemonstop

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"time"

	"synesis/bootstrap/internal/payload"
)

const (
	dialTimeout  = 750 * time.Millisecond
	replyTimeout = 3 * time.Second
)

// exitTimeout is how long a daemon is given to go after it is told to.
var exitTimeout = 6 * time.Second

// running reports whether a process still runs; tests replace it.
var running = processRunning

// endpoint is the record the daemon publishes for local clients.
type endpoint struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
	PID   int    `json:"pid"`
}

// Stop asks the running daemon to stop and waits until it has. It returns nil
// when no daemon is running. A daemon too old to know the request is ended
// directly, but only when its program file lies inside installRoot: the
// process identifier in the endpoint file alone proves nothing.
func Stop(installRoot string) error {
	file, err := endpointFile()
	if err != nil {
		return nil
	}
	return stop(file, installRoot)
}

// StopAll stops the daemon and then ends every other process that still runs
// from installRoot: project runtimes that outlive a daemon ended directly, and
// MCP servers started by agents. Uninstall calls it, because a process running
// from the installation holds its files open and has nothing left to run from.
func StopAll(installRoot string) error {
	if err := Stop(installRoot); err != nil {
		return err
	}
	return endProcessesWithin(installRoot)
}

func endProcessesWithin(installRoot string) error {
	remaining := func() []int {
		var pids []int
		for _, pid := range processIDs() {
			if image := processImage(pid); pid != os.Getpid() && image != "" && payload.PathWithin(installRoot, image) {
				pids = append(pids, pid)
			}
		}
		return pids
	}
	// A runtime notices its daemon is gone and exits by itself; give it that
	// chance before ending what is left.
	if waitFor(time.Second, func() bool { return len(remaining()) == 0 }) {
		return nil
	}
	for _, pid := range remaining() {
		terminate(pid)
	}
	if !waitUntil(func() bool { return len(remaining()) == 0 }) {
		return fmt.Errorf("%d Synesis processes are still running from %s; close them and try again", len(remaining()), installRoot)
	}
	return nil
}

func stop(endpointFile, installRoot string) error {
	data, err := os.ReadFile(endpointFile)
	if err != nil {
		return nil
	}
	var daemon endpoint
	if json.Unmarshal(data, &daemon) != nil || daemon.Port < 1 || daemon.Port > 65535 || daemon.Token == "" {
		return nil
	}
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(daemon.Port))
	accepted, reachable := requestShutdown(address, daemon.Token)
	if !reachable {
		return nil // a stale endpoint file: nothing answers there
	}
	if accepted && waitUntil(func() bool { return !listening(address) && !running(daemon.PID) }) {
		return nil
	}
	if image := processImage(daemon.PID); image != "" && payload.PathWithin(installRoot, image) && terminate(daemon.PID) &&
		waitUntil(func() bool { return !running(daemon.PID) }) {
		return nil
	}
	return errors.New("the running Synesis daemon could not be stopped")
}

// requestShutdown sends the authenticated request. reachable is false when
// nothing accepts connections at the address.
func requestShutdown(address, token string) (accepted, reachable bool) {
	connection, err := net.DialTimeout("tcp", address, dialTimeout)
	if err != nil {
		return false, false
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(replyTimeout))
	request, _ := json.Marshal(map[string]string{"token": token, "operation": "SHUTDOWN"})
	if _, err := fmt.Fprintf(connection, "%s\n", request); err != nil {
		return false, true
	}
	var reply struct {
		OK    bool   `json:"ok"`
		State string `json:"state"`
	}
	line, _ := bufio.NewReader(connection).ReadBytes('\n')
	return json.Unmarshal(line, &reply) == nil && reply.OK && reply.State == "STOPPING", true
}

func listening(address string) bool {
	connection, err := net.DialTimeout("tcp", address, dialTimeout)
	if err != nil {
		return false
	}
	connection.Close()
	return true
}

func waitUntil(done func() bool) bool {
	return waitFor(exitTimeout, done)
}

func waitFor(limit time.Duration, done func() bool) bool {
	for deadline := time.Now().Add(limit); time.Now().Before(deadline); time.Sleep(50 * time.Millisecond) {
		if done() {
			return true
		}
	}
	return done()
}

// endpointFile mirrors where the Synesis runtime keeps its per-user state.
func endpointFile() (string, error) {
	var base string
	switch runtime.GOOS {
	case "windows":
		base = os.Getenv("LOCALAPPDATA")
	case "darwin":
		if home, err := os.UserHomeDir(); err == nil {
			base = filepath.Join(home, "Library", "Application Support")
		}
	default:
		base = os.Getenv("XDG_STATE_HOME")
		if home, err := os.UserHomeDir(); base == "" && err == nil {
			base = filepath.Join(home, ".local", "state")
		}
	}
	if base == "" {
		return "", errors.New("no per-user state directory")
	}
	return filepath.Join(base, "Synesis", "daemon", "endpoint.json"), nil
}
