package install

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/installfs"
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

// stdoutOf returns what run prints.
func stdoutOf(t *testing.T, run func() error) string {
	t.Helper()
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	read := make(chan []byte)
	go func() {
		output, _ := io.ReadAll(reader)
		read <- output
	}()
	original := os.Stdout
	os.Stdout = writer
	runErr := run()
	os.Stdout = original
	writer.Close()
	output := <-read
	if runErr != nil {
		t.Fatal(runErr)
	}
	return string(output)
}

func TestARuleForAnotherVersionIsReportedAfterEveryChangeOfRuntimeWithoutTheOption(t *testing.T) {
	withoutPathMutation(t)
	root := t.TempDir()
	installRoot := filepath.Join(root, "install")
	t.Cleanup(func() {
		_ = installfs.RemoveInstallationTree(installRoot)
		_ = installfs.RemoveInstallationTree(installRoot + ".rollback")
	})
	v1 := writeBundleArchive(t, root, "0.1.0")
	v2 := writeBundleArchive(t, root, "0.2.0")
	m2 := writeDevelopmentManifest(t, root, "0.2.0", v2)
	var rule []string
	linkRulePrograms = func() ([]string, bool) { return rule, rule != nil }
	t.Cleanup(func() { linkRulePrograms = func() ([]string, bool) { return nil, false } })
	install := func(args ...string) string {
		return stdoutOf(t, func() error { return RunInstall(args[0], append(args[1:], "--install-dir", installRoot)) })
	}

	// No rule: the person never chose to host, so nothing is said.
	if output := install("install", "--bundle", v1); strings.Contains(output, "LINK_FIREWALL") {
		t.Fatalf("no rule, yet:\n%s", output)
	}
	first, err := activeJava(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	// A rule for the active runtime is current, however netsh spells the
	// folders above the versions folder.
	rule = []string{`C:\Users\????~1\X` + strings.ToUpper(first[len(filepath.Join(installRoot, "versions")):])}
	if output := install("install", "--bundle", v1); strings.Contains(output, "LINK_FIREWALL") {
		t.Fatalf("current rule, yet:\n%s", output)
	}

	if output := install("update", "--prepare", "--manifest", fileURL(m2)); strings.Contains(output, "LINK_FIREWALL") {
		t.Fatalf("a prepared update changed nothing, yet:\n%s", output)
	}
	plans, err := filepath.Glob(filepath.Join(installRoot, "admin", "update-plans", "*.plan.json"))
	if err != nil || len(plans) != 1 {
		t.Fatalf("plans %v, %v", plans, err)
	}
	output := install("update", "--execute", "--plan", strings.TrimSuffix(filepath.Base(plans[0]), ".plan.json"))
	second, err := activeJava(installRoot)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"LINK_FIREWALL=STALE\n",
		"LINK_FIREWALL_RULE_PROGRAM=" + rule[0] + "\n",
		"WARNING=other computers cannot connect to this one",
		`netsh advfirewall firewall set rule name="Synesis Link" new program="` + second + `"` + "\n",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("after the update, missing %q in:\n%s", want, output)
		}
	}
	// Rolling back makes the rule's runtime the active one again.
	if output := install("update", "--rollback"); strings.Contains(output, "LINK_FIREWALL") {
		t.Fatalf("after the rollback:\n%s", output)
	}
	if output := stdoutOf(t, func() error {
		return RunRepair([]string{"--bundle", v2, "--install-dir", installRoot})
	}); !strings.Contains(output, "LINK_FIREWALL=STALE\n") {
		t.Fatalf("after a repair with another version:\n%s", output)
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
