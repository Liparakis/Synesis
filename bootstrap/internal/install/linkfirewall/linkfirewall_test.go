package linkfirewall

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// recordNetsh replaces netsh for one test and returns the commands it saw. A
// "show" finds a rule only when ruleExists says so.
func recordNetsh(t *testing.T, ruleExists bool) *[][]string {
	t.Helper()
	var calls [][]string
	original := netsh
	netsh = func(args ...string) ([]byte, error) {
		calls = append(calls, args)
		if args[2] == "show" && !ruleExists {
			return []byte("No rules match the specified criteria."), errors.New("exit status 1")
		}
		return nil, nil
	}
	t.Cleanup(func() { netsh = original })
	return &calls
}

func TestAllowReplacesTheRuleWithOneForTheProgram(t *testing.T) {
	program := filepath.Join(t.TempDir(), "java.exe")
	if err := os.WriteFile(program, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	calls := recordNetsh(t, true)

	if err := RunElevated([]string{"allow", "--program", program}); err != nil {
		t.Fatal(err)
	}

	want := [][]string{
		{"advfirewall", "firewall", "delete", "rule", "name=Synesis Link"},
		{"advfirewall", "firewall", "add", "rule", "name=Synesis Link", "dir=in", "action=allow", "protocol=UDP", "profile=any", "program=" + program},
	}
	if !reflect.DeepEqual(*calls, want) {
		t.Fatalf("netsh commands = %v, want %v", *calls, want)
	}
}

func TestAllowRefusesAnythingButAnExistingJavaLauncher(t *testing.T) {
	directory := t.TempDir()
	other := filepath.Join(directory, "other.exe")
	if err := os.WriteFile(other, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	for _, program := range []string{"", "java.exe", other, filepath.Join(directory, "java.exe")} {
		calls := recordNetsh(t, true)
		if err := RunElevated([]string{"allow", "--program", program}); err == nil {
			t.Fatalf("program %q was accepted", program)
		}
		if len(*calls) != 0 {
			t.Fatalf("program %q reached netsh: %v", program, *calls)
		}
	}
}

func TestRemoveDeletesOnlyAnExistingRule(t *testing.T) {
	calls := recordNetsh(t, false)
	if err := RunElevated([]string{"remove"}); err != nil {
		t.Fatal(err)
	}
	if len(*calls) != 1 || (*calls)[0][2] != "show" {
		t.Fatalf("a missing rule led to %v", *calls)
	}

	calls = recordNetsh(t, true)
	if err := RunElevated([]string{"remove"}); err != nil {
		t.Fatal(err)
	}
	if last := (*calls)[len(*calls)-1]; strings.Join(last, " ") != "advfirewall firewall delete rule name=Synesis Link" {
		t.Fatalf("an existing rule led to %v", *calls)
	}
}

func TestAnUnknownCommandIsRefused(t *testing.T) {
	recordNetsh(t, true)
	for _, args := range [][]string{nil, {"reset"}} {
		if err := RunElevated(args); err == nil {
			t.Fatalf("%v was accepted", args)
		}
	}
}
