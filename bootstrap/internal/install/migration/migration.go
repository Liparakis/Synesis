// Package migration plans and runs the provider and project configuration
// migrations that an update performs, with backups to restore on failure.
package migration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"synesis/bootstrap/internal/childproc"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/payload"
)

// Reference records the source fingerprint and durable plan state
// for one provider or project migration.
type Reference struct {
	Kind       string `json:"kind"`
	Provider   string `json:"provider,omitempty"`
	PlanID     string `json:"planId,omitempty"`
	State      string `json:"state"`
	SourcePath string `json:"sourcePath,omitempty"`
	SourceHash string `json:"sourceHash,omitempty"`
}

// Prepared is the complete migration set required before update
// activation can proceed.
type Prepared struct {
	Providers   []Reference
	Project     Reference
	ProjectRoot string
}

// CollectPrepared inspects every supported migration source and returns the
// set that an update must carry, failing when a required plan is not prepared.
func CollectPrepared(paths layout.Paths) (Prepared, error) {
	refs := Prepared{}
	for _, provider := range []struct{ id, path string }{
		{"codex", filepath.Join(userHome(), ".codex", "config.toml")},
	} {
		ref, err := inspectProviderMigration(paths, provider.id, provider.path)
		if err != nil {
			return Prepared{}, err
		}
		refs.Providers = append(refs.Providers, ref)
	}
	projectRoot, found := activeProjectRoot()
	if !found {
		refs.Project = Reference{Kind: "project", State: "NO_PROJECT"}
		return refs, nil
	}
	refs.ProjectRoot = projectRoot
	ref, err := inspectProjectMigration(projectRoot)
	if err != nil {
		return Prepared{}, err
	}
	refs.Project = ref
	if ref.State == "MIGRATION_REQUIRED" {
		planID, ok := findPreparedPlan("pmig-project-", ref.SourcePath, ref.SourceHash)
		if !ok {
			return Prepared{}, errors.New("update migrations not prepared")
		}
		refs.Project.PlanID = planID
	}
	return refs, nil
}

func inspectProviderMigration(paths layout.Paths, provider, sourcePath string) (Reference, error) {
	if provider == "codex" {
		return inspectCodexTomlMigration(paths, sourcePath)
	}
	ref := Reference{Kind: "provider", Provider: provider, SourcePath: sourcePath}
	data, err := os.ReadFile(sourcePath)
	if os.IsNotExist(err) {
		ref.State = "MISSING"
		return ref, nil
	}
	if err != nil {
		return ref, err
	}
	ref.SourceHash = payload.Digest(data)
	if strings.Count(string(data), `"synesis"`) > 1 {
		ref.State = "AMBIGUOUS"
		return ref, errors.New("provider config duplicate Synesis entry")
	}
	var root map[string]any
	if json.Unmarshal(data, &root) != nil {
		return ref, errors.New("provider config malformed")
	}
	servers, ok := root["mcpServers"].(map[string]any)
	if !ok {
		ref.State = "UP_TO_DATE"
	} else if value, exists := servers["synesis"].(map[string]any); exists {
		command, _ := value["command"].(string)
		stable := stableLauncherCommand(command) && (filepath.Clean(command) == filepath.Clean(paths.Launcher) || launcherExists(command) || command == "synesis" || command == "synesis.cmd")
		if stable {
			ref.State = "UP_TO_DATE"
		} else {
			ref.State = "MIGRATION_REQUIRED"
		}
	} else {
		ref.State = "UP_TO_DATE"
	}
	if ref.State == "MIGRATION_REQUIRED" {
		planID, ok := findPreparedPlan("pmig-", sourcePath, ref.SourceHash)
		if !ok {
			return ref, errors.New("update migrations not prepared")
		}
		ref.PlanID = planID
	}
	return ref, nil
}

func inspectCodexTomlMigration(paths layout.Paths, sourcePath string) (Reference, error) {
	ref := Reference{Kind: "provider", Provider: "codex", SourcePath: sourcePath}
	data, err := os.ReadFile(sourcePath)
	if os.IsNotExist(err) {
		ref.State = "MISSING"
		return ref, nil
	}
	if err != nil {
		return ref, err
	}
	ref.SourceHash = payload.Digest(data)
	text := string(data)
	if strings.Count(text, "[mcp_servers.synesis]") > 1 || strings.Contains(text, "mcp_servers.synesis.") {
		ref.State = "AMBIGUOUS"
		return ref, errors.New("provider config duplicate Synesis entry")
	}
	start := strings.Index(text, "[mcp_servers.synesis]")
	if start < 0 {
		ref.State = "MIGRATION_REQUIRED"
	} else {
		block := text[start:]
		if next := strings.Index(block[len("[mcp_servers.synesis]"):], "\n["); next >= 0 {
			block = block[:len("[mcp_servers.synesis]")+next+1]
		}
		stableCommand := false
		stableArgs := strings.Contains(block, "args = [\"mcp\", \"--provider\", \"codex\"]")
		for _, line := range strings.Split(block, "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "command") {
				parts := strings.SplitN(line, "=", 2)
				if len(parts) == 2 {
					command := strings.Trim(strings.TrimSpace(parts[1]), "\"'")
					stableCommand = stableLauncherCommand(command) && (filepath.Clean(command) == filepath.Clean(paths.Launcher) || launcherExists(command) || command == "synesis" || command == "synesis.cmd")
				}
			}
		}
		ref.State = "UP_TO_DATE"
		if !stableCommand || !stableArgs {
			ref.State = "MIGRATION_REQUIRED"
		}
	}
	if ref.State == "MIGRATION_REQUIRED" {
		planID, ok := findPreparedPlan("pmig-", sourcePath, ref.SourceHash)
		if !ok {
			return ref, errors.New("update migrations not prepared")
		}
		ref.PlanID = planID
	}
	return ref, nil
}

func stableLauncherCommand(command string) bool {
	if command == "synesis" || command == "synesis.cmd" {
		return true
	}
	clean := filepath.ToSlash(filepath.Clean(command))
	base := strings.ToLower(filepath.Base(clean))
	if base != "synesis" && base != "synesis.cmd" {
		return false
	}
	lower := strings.ToLower(clean)
	return !strings.Contains(lower, "/versions/") && !strings.Contains(lower, "/payloads/")
}

func launcherExists(command string) bool {
	_, err := os.Stat(command)
	return err == nil
}

func inspectProjectMigration(root string) (Reference, error) {
	path := filepath.Join(root, ".synesis", "project.json")
	data, err := os.ReadFile(path)
	if err != nil {
		return Reference{}, err
	}
	var metadata struct {
		SchemaVersion int `json:"schemaVersion"`
	}
	if json.Unmarshal(data, &metadata) != nil {
		return Reference{Kind: "project", State: "AMBIGUOUS", SourcePath: path, SourceHash: payload.Digest(data)}, nil
	}
	ref := Reference{Kind: "project", SourcePath: path, SourceHash: payload.Digest(data)}
	if metadata.SchemaVersion == 1 {
		ref.State = "UP_TO_DATE"
		return ref, nil
	}
	ref.State = "UNSUPPORTED_SOURCE_SCHEMA"
	return ref, nil
}

func findPreparedPlan(prefix, sourcePath, sourceHash string) (string, bool) {
	dir := filepath.Join(globalSynesisAdminRoot(), "migration-plans")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", false
	}
	for _, entry := range entries {
		if !strings.HasPrefix(entry.Name(), prefix) || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
		if err != nil {
			continue
		}
		if bytesContain(data, sourcePath, sourceHash) {
			return strings.TrimSuffix(entry.Name(), ".json"), true
		}
	}
	return "", false
}

func bytesContain(data []byte, sourcePath, sourceHash string) bool {
	var value any
	if json.Unmarshal(data, &value) != nil {
		return false
	}
	encoded, _ := json.Marshal(value)
	return strings.Contains(string(encoded), sourcePath) && strings.Contains(string(encoded), sourceHash)
}

func activeProjectRoot() (string, bool) {
	current, err := os.Getwd()
	if err != nil {
		return "", false
	}
	for {
		if _, err := os.Stat(filepath.Join(current, ".synesis", "project.json")); err == nil {
			return filepath.Clean(current), true
		}
		parent := filepath.Dir(current)
		if parent == current {
			return "", false
		}
		current = parent
	}
}

func userHome() string {
	home, _ := os.UserHomeDir()
	return home
}

func globalSynesisAdminRoot() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		if home := userHome(); home != "" {
			base = filepath.Join(home, "AppData", "Local")
		}
	}
	return filepath.Join(base, "Synesis", "admin")
}

// Execute backs up every source that needs migration, runs the prepared
// migration plans through the stable launcher, and returns the function that
// restores the backups. record logs transaction states for the update planID.
func Execute(paths layout.Paths, planID string, providers []Reference, project Reference, projectRoot string, record func(state, reason string) error) (func() error, error) {
	backupRoot := filepath.Join(paths.Admin, "migration-backups", planID)
	if err := os.MkdirAll(backupRoot, 0o700); err != nil {
		return nil, err
	}
	refs := append([]Reference{}, providers...)
	refs = append(refs, project)
	backed := make([]struct{ source, backup, expected string }, 0, len(refs))
	seen := map[string]bool{}
	for _, ref := range refs {
		if ref.State != "MIGRATION_REQUIRED" || ref.SourcePath == "" || seen[ref.SourcePath] {
			continue
		}
		seen[ref.SourcePath] = true
		backup := filepath.Join(backupRoot, fmt.Sprintf("%d.bak", len(backed)))
		data, err := os.ReadFile(ref.SourcePath)
		if err != nil {
			return nil, err
		}
		if payload.Digest(data) != ref.SourceHash {
			return nil, errors.New("migration source stale")
		}
		if err := os.WriteFile(backup, data, 0o600); err != nil {
			return nil, err
		}
		backed = append(backed, struct{ source, backup, expected string }{ref.SourcePath, backup, ref.SourceHash})
	}
	restore := func() error {
		for _, item := range backed {
			if info, err := os.Lstat(item.source); err != nil || info.Mode()&os.ModeSymlink != 0 {
				return errors.New("migration restore target changed")
			}
			data, err := os.ReadFile(item.backup)
			if err != nil {
				return err
			}
			if payload.Digest(data) != item.expected {
				return errors.New("migration backup hash mismatch")
			}
			if err := os.WriteFile(item.source, data, 0o600); err != nil {
				return err
			}
			if restored, err := os.ReadFile(item.source); err != nil || payload.Digest(restored) != payload.Digest(data) {
				return errors.New("migration restore hash mismatch")
			}
		}
		return nil
	}
	seenPlans := map[string]bool{}
	for _, ref := range refs {
		if ref.State != "MIGRATION_REQUIRED" || ref.PlanID == "" || seenPlans[ref.PlanID] {
			continue
		}
		seenPlans[ref.PlanID] = true
		args := []string{}
		if ref.Kind == "provider" {
			_ = record("PROVIDER_MIGRATION_EXECUTING", ref.Provider)
			args = []string{"provider", "migrate", "--execute", ref.PlanID}
		} else {
			_ = record("PROJECT_MIGRATION_EXECUTING", "project")
			args = []string{"migrate", "--execute", ref.PlanID}
		}
		if err := runStableMigrationCommand(paths.Launcher, args, projectRoot); err != nil {
			_ = restore()
			return nil, err
		}
		if ref.Kind == "provider" {
			_ = record("PROVIDER_MIGRATION_VERIFIED", ref.Provider)
		} else {
			_ = record("PROJECT_MIGRATION_VERIFIED", "project")
		}
	}
	return restore, nil
}

func runStableMigrationCommand(launcher string, args []string, projectRoot string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	command := exec.CommandContext(ctx, launcher, args...)
	if projectRoot != "" {
		command.Dir = projectRoot
	}
	if runtime.GOOS == "windows" && strings.HasSuffix(strings.ToLower(launcher), ".cmd") {
		command = exec.CommandContext(ctx, "cmd.exe", append([]string{"/d", "/s", "/c", launcher}, args...)...)
		command.Dir = projectRoot
	}
	childproc.Hide(command)
	output, err := command.CombinedOutput()
	if err != nil {
		return fmt.Errorf("migration command failed: %w", err)
	}
	text := string(output)
	if strings.Contains(text, "REQUIRES_HUMAN_REVIEW") || strings.Contains(text, "STALE") ||
		strings.Contains(text, "MIGRATION_RESULT=FAILED") || strings.Contains(text, "MIGRATION_RESULT=FAILED_RESTORED") ||
		strings.Contains(text, "MIGRATION_RESULT=ROLLBACK_UNSAFE") {
		return errors.New("migration command reported unresolved state")
	}
	return nil
}
