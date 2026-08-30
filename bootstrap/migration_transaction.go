package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// migrationReference records the source fingerprint and durable plan state
// for one provider or project migration.
type migrationReference struct {
	Kind       string `json:"kind"`
	Provider   string `json:"provider,omitempty"`
	PlanID     string `json:"planId,omitempty"`
	State      string `json:"state"`
	SourcePath string `json:"sourcePath,omitempty"`
	SourceHash string `json:"sourceHash,omitempty"`
}

// preparedMigrations is the complete migration set required before update
// activation can proceed.
type preparedMigrations struct {
	Providers   []migrationReference
	Project     migrationReference
	ProjectRoot string
}

func collectPreparedMigrations(paths installPaths) (preparedMigrations, error) {
	refs := preparedMigrations{}
	for _, provider := range []struct{ id, path string }{
		{"codex", filepath.Join(userHome(), ".codex", "config.toml")},
	} {
		ref, err := inspectProviderMigration(paths, provider.id, provider.path)
		if err != nil {
			return preparedMigrations{}, err
		}
		refs.Providers = append(refs.Providers, ref)
	}
	projectRoot, found := activeProjectRoot()
	if !found {
		refs.Project = migrationReference{Kind: "project", State: "NO_PROJECT"}
		return refs, nil
	}
	refs.ProjectRoot = projectRoot
	ref, err := inspectProjectMigration(projectRoot)
	if err != nil {
		return preparedMigrations{}, err
	}
	refs.Project = ref
	if ref.State == "MIGRATION_REQUIRED" {
		planID, ok := findPreparedPlan("pmig-project-", ref.SourcePath, ref.SourceHash)
		if !ok {
			return preparedMigrations{}, errors.New("update migrations not prepared")
		}
		refs.Project.PlanID = planID
	}
	return refs, nil
}

func inspectProviderMigration(paths installPaths, provider, sourcePath string) (migrationReference, error) {
	if provider == "codex" {
		return inspectCodexTomlMigration(paths, sourcePath)
	}
	ref := migrationReference{Kind: "provider", Provider: provider, SourcePath: sourcePath}
	data, err := os.ReadFile(sourcePath)
	if os.IsNotExist(err) {
		ref.State = "MISSING"
		return ref, nil
	}
	if err != nil {
		return ref, err
	}
	ref.SourceHash = sha256Hex(data)
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
		stable := stableLauncherCommand(command) && (filepath.Clean(command) == filepath.Clean(paths.launcher) || launcherExists(command) || command == "synesis" || command == "synesis.cmd")
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

func inspectCodexTomlMigration(paths installPaths, sourcePath string) (migrationReference, error) {
	ref := migrationReference{Kind: "provider", Provider: "codex", SourcePath: sourcePath}
	data, err := os.ReadFile(sourcePath)
	if os.IsNotExist(err) {
		ref.State = "MISSING"
		return ref, nil
	}
	if err != nil {
		return ref, err
	}
	ref.SourceHash = sha256Hex(data)
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
					stableCommand = stableLauncherCommand(command) && (filepath.Clean(command) == filepath.Clean(paths.launcher) || launcherExists(command) || command == "synesis" || command == "synesis.cmd")
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

func inspectProjectMigration(root string) (migrationReference, error) {
	path := filepath.Join(root, ".synesis", "project.json")
	data, err := os.ReadFile(path)
	if err != nil {
		return migrationReference{}, err
	}
	var metadata struct {
		SchemaVersion int `json:"schemaVersion"`
	}
	if json.Unmarshal(data, &metadata) != nil {
		return migrationReference{Kind: "project", State: "AMBIGUOUS", SourcePath: path, SourceHash: sha256Hex(data)}, nil
	}
	ref := migrationReference{Kind: "project", SourcePath: path, SourceHash: sha256Hex(data)}
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

func executePreparedMigrations(paths installPaths, plan updatePlan) (func() error, error) {
	backupRoot := filepath.Join(paths.admin, "migration-backups", plan.PlanID)
	if err := os.MkdirAll(backupRoot, 0o700); err != nil {
		return nil, err
	}
	refs := append([]migrationReference{}, plan.ProviderMigrations...)
	refs = append(refs, plan.ProjectMigration)
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
		if sha256Hex(data) != ref.SourceHash {
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
			if sha256Hex(data) != item.expected {
				return errors.New("migration backup hash mismatch")
			}
			if err := os.WriteFile(item.source, data, 0o600); err != nil {
				return err
			}
			if restored, err := os.ReadFile(item.source); err != nil || sha256Hex(restored) != sha256Hex(data) {
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
			_ = appendUpdateTransactionState(paths, plan.PlanID, "PROVIDER_MIGRATION_EXECUTING", ref.Provider)
			args = []string{"provider", "migrate", "--execute", ref.PlanID}
		} else {
			_ = appendUpdateTransactionState(paths, plan.PlanID, "PROJECT_MIGRATION_EXECUTING", "project")
			args = []string{"migrate", "--execute", ref.PlanID}
		}
		if err := runStableMigrationCommand(paths.launcher, args, plan.ProjectRoot); err != nil {
			_ = restore()
			return nil, err
		}
		if ref.Kind == "provider" {
			_ = appendUpdateTransactionState(paths, plan.PlanID, "PROVIDER_MIGRATION_VERIFIED", ref.Provider)
		} else {
			_ = appendUpdateTransactionState(paths, plan.PlanID, "PROJECT_MIGRATION_VERIFIED", "project")
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

func sha256Hex(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

func appendUpdateTransactionState(paths installPaths, planID, state, reason string) error {
	if err := os.MkdirAll(paths.executions, 0o700); err != nil {
		return err
	}
	entry := struct {
		PlanID string `json:"planId"`
		State  string `json:"state"`
		Reason string `json:"reason,omitempty"`
	}{planID, state, reason}
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(filepath.Join(paths.executions, planID+".jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.Write(append(data, '\n'))
	return err
}

func latestUpdatePlan(paths installPaths, planID string) bool {
	entries, err := os.ReadDir(paths.plans)
	if err != nil {
		return false
	}
	var latestName string
	var latestTime time.Time
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".plan.json") {
			continue
		}
		info, err := entry.Info()
		if err != nil || (!latestTime.IsZero() && !info.ModTime().After(latestTime)) {
			continue
		}
		latestName = strings.TrimSuffix(entry.Name(), ".plan.json")
		latestTime = info.ModTime()
	}
	return latestName == planID
}
