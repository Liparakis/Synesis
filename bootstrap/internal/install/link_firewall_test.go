package install

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/layout"
)

func TestTheFirewallRuleIsRequestedOnlyWithTheOptionAndNamesTheActiveRuntime(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	installRoot := filepath.Join(root, "install")
	var programs []string
	failure := errors.New("administrator approval was declined")
	allowLinkProgram = func(program string) error {
		programs = append(programs, program)
		return failure
	}
	t.Cleanup(func() { allowLinkProgram = func(string) error { return nil } })

	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	if len(programs) != 0 {
		t.Fatalf("an install without the option requested a rule for %v", programs)
	}
	// A refused rule leaves a finished installation finished.
	for _, run := range []func() error{
		func() error {
			return RunRepair([]string{"--bundle", archive, "--install-dir", installRoot, "--allow-link"})
		},
		func() error {
			return RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot, "--allow-link"})
		},
	} {
		programs = nil
		if err := run(); err != nil {
			t.Fatal(err)
		}
		paths, err := layout.Resolve(installRoot)
		if err != nil {
			t.Fatal(err)
		}
		pointer, _, err := activepointer.ReadLoose(paths, paths.Current)
		if err != nil {
			t.Fatal(err)
		}
		want := filepath.Join(paths.Versions, pointer.PayloadDirectory, "runtime", "bin", "java.exe")
		if len(programs) != 1 || programs[0] != want {
			t.Fatalf("rule requested for %v, want %s", programs, want)
		}
	}
}

func TestUninstallGoesOnWhenTheFirewallRuleCannotBeRemoved(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	archive := writeBundleArchive(t, root, "0.1.0")
	installRoot := filepath.Join(root, "install")
	if err := RunInstall("install", []string{"--bundle", archive, "--install-dir", installRoot}); err != nil {
		t.Fatal(err)
	}
	removeLinkRuleForUninstall = func() error { return errors.New("administrator approval was declined") }
	t.Cleanup(func() { removeLinkRuleForUninstall = func() error { return nil } })
	var warnings []string
	err := RunUninstall([]string{"--install-dir", installRoot}, func(stage, status, detail string) {
		if status == "warning" {
			warnings = append(warnings, detail)
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(warnings) != 1 || !strings.Contains(warnings[0], "firewall rule was left in place") {
		t.Fatalf("warnings = %v", warnings)
	}
}
