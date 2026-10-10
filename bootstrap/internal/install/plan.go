package install

import (
	crand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/migration"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

// updatePlan records the verified inputs and migration state for a resumable
// prepare/execute update operation.
type updatePlan struct {
	SchemaVersion           int                   `json:"schemaVersion"`
	PlanID                  string                `json:"planId"`
	PreparedAt              string                `json:"preparedAt"`
	InstallationRoot        string                `json:"installationRoot"`
	BundlePath              string                `json:"bundlePath"`
	ManifestPath            string                `json:"manifestPath"`
	SignaturePath           string                `json:"signaturePath"`
	BundleFingerprint       string                `json:"bundleFingerprint"`
	ManifestFingerprint     string                `json:"manifestFingerprint"`
	TargetVersion           string                `json:"targetVersion"`
	CurrentFingerprint      string                `json:"currentFingerprint,omitempty"`
	ProviderMigrations      []migration.Reference `json:"providerMigrations,omitempty"`
	ProjectMigration        migration.Reference   `json:"projectMigration"`
	ProjectRoot             string                `json:"projectRoot,omitempty"`
	MigrationState          string                `json:"migrationState"`
	RollbackCompatibility   string                `json:"rollbackCompatibility"`
	LiveProjectSessionState string                `json:"liveProjectSessionState"`
	AcceptanceMode          bool                  `json:"acceptanceMode,omitempty"`
	CanonicalHash           string                `json:"canonicalHash"`
}

// updateLock identifies the process holding the single installation mutation
// lock and the operation it is performing.
type updateLock struct {
	SchemaVersion    int    `json:"schemaVersion"`
	PID              int    `json:"pid"`
	AcquiredAt       string `json:"acquiredAt"`
	Executable       string `json:"executable"`
	Nonce            string `json:"nonce"`
	Operation        string `json:"operation"`
	InstallationRoot string `json:"installationRoot"`
}

func acquireUpdateLock(paths layout.Paths, operation string) (func(), error) {
	if err := os.MkdirAll(paths.Admin, 0o755); err != nil {
		return nil, err
	}
	nonceBytes := make([]byte, 16)
	if _, err := crand.Read(nonceBytes); err != nil {
		return nil, err
	}
	lock := updateLock{SchemaVersion: 1, PID: os.Getpid(), AcquiredAt: time.Now().UTC().Format(time.RFC3339Nano), Executable: os.Args[0], Nonce: hex.EncodeToString(nonceBytes), Operation: operation, InstallationRoot: paths.Root}
	data, _ := json.Marshal(lock)
	file, err := os.OpenFile(paths.Lock, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if os.IsExist(err) {
			return nil, errors.New("update execution busy")
		}
		return nil, err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		os.Remove(paths.Lock)
		return nil, err
	}
	if err := file.Close(); err != nil {
		os.Remove(paths.Lock)
		return nil, err
	}
	return func() { _ = os.Remove(paths.Lock) }, nil
}

func prepareUpdatePlan(paths layout.Paths, m releasemanifest.Manifest, manifestData, signature, archive []byte, acceptance bool) (string, error) {
	if _, _, err := activepointer.Read(paths, paths.Current); err != nil {
		return "", err
	}
	if err := os.MkdirAll(paths.Plans, 0o755); err != nil {
		return "", err
	}
	currentData, _ := os.ReadFile(paths.Current)
	migrations, err := migration.CollectPrepared(paths)
	if err != nil {
		return "", err
	}
	plan := updatePlan{SchemaVersion: 1, PreparedAt: time.Now().UTC().Format(time.RFC3339Nano), InstallationRoot: paths.Root, BundleFingerprint: payload.Digest(archive), ManifestFingerprint: payload.Digest(manifestData), TargetVersion: m.Version, CurrentFingerprint: payload.Digest(currentData), ProviderMigrations: migrations.Providers, ProjectMigration: migrations.Project, ProjectRoot: migrations.ProjectRoot, MigrationState: "MIGRATIONS_PREPARED", RollbackCompatibility: "SAFE", LiveProjectSessionState: "NO_MIGRATION_REQUIRED", AcceptanceMode: acceptance}
	if plan.ProjectMigration.State == "NO_PROJECT" {
		plan.LiveProjectSessionState = "NO_PROJECT"
	}
	for _, ref := range plan.ProviderMigrations {
		if ref.State == "MIGRATION_REQUIRED" {
			plan.RollbackCompatibility = "MIGRATION_BACKUPS_REQUIRED"
		}
	}
	if plan.ProjectMigration.State == "MIGRATION_REQUIRED" {
		plan.RollbackCompatibility = "MIGRATION_BACKUPS_REQUIRED"
	}
	planBytes, _ := json.Marshal(plan)
	plan.PlanID = payload.Digest(planBytes)[:24]
	plan.BundlePath = filepath.Join(paths.Plans, plan.PlanID+".bundle")
	plan.ManifestPath = filepath.Join(paths.Plans, plan.PlanID+".json")
	plan.SignaturePath = filepath.Join(paths.Plans, plan.PlanID+".sig")
	planBytes, _ = json.Marshal(plan)
	plan.CanonicalHash = payload.Digest(planBytes)
	planBytes, _ = json.MarshalIndent(plan, "", "  ")
	if err := os.WriteFile(plan.BundlePath, archive, 0o600); err != nil {
		return "", err
	}
	if err := os.WriteFile(plan.ManifestPath, manifestData, 0o600); err != nil {
		return "", err
	}
	if err := os.WriteFile(plan.SignaturePath, signature, 0o600); err != nil {
		return "", err
	}
	if err := installfs.AtomicWrite(filepath.Join(paths.Plans, plan.PlanID+".plan.json"), planBytes); err != nil {
		return "", err
	}
	return plan.PlanID, nil
}

func readUpdatePlan(paths layout.Paths, id string) (updatePlan, error) {
	if !payload.ValidDirectory(id) {
		return updatePlan{}, errors.New("update plan invalid")
	}
	data, err := os.ReadFile(filepath.Join(paths.Plans, id+".plan.json"))
	if err != nil {
		return updatePlan{}, errors.New("update plan not found")
	}
	var plan updatePlan
	if json.Unmarshal(data, &plan) != nil || plan.SchemaVersion != 1 || plan.PlanID != id || plan.InstallationRoot != paths.Root {
		return updatePlan{}, errors.New("update plan invalid")
	}
	hash := plan.CanonicalHash
	plan.CanonicalHash = ""
	canonical, _ := json.Marshal(plan)
	if payload.Digest(canonical) != hash {
		return updatePlan{}, errors.New("update plan invalid")
	}
	plan.CanonicalHash = hash
	return plan, nil
}

func executeUpdatePlan(paths layout.Paths, id string) error {
	plan, err := readUpdatePlan(paths, id)
	if err != nil {
		return err
	}
	if data, readErr := os.ReadFile(filepath.Join(paths.Executions, id+".json")); readErr == nil && strings.Contains(string(data), `"result":"SUCCESS"`) && latestUpdatePlan(paths, id) {
		return nil
	}
	_ = appendUpdateTransactionState(paths, id, "PLANNED", "")
	currentData, _ := os.ReadFile(paths.Current)
	if payload.Digest(currentData) != plan.CurrentFingerprint {
		_ = appendUpdateTransactionState(paths, id, "FAILED_BEFORE_MUTATION", "update_plan_stale")
		return errors.New("update plan stale")
	}
	manifestData, err := os.ReadFile(plan.ManifestPath)
	if err != nil {
		return err
	}
	signature, _ := os.ReadFile(plan.SignaturePath)
	m, err := releasemanifest.Parse(manifestData)
	if err != nil || verifyManifestAuthenticity(m, manifestData, signature, plan.AcceptanceMode) != nil {
		return errors.New("update plan bundle verification failed")
	}
	archive, err := os.ReadFile(plan.BundlePath)
	if err != nil || payload.Digest(archive) != plan.BundleFingerprint || payload.Digest(manifestData) != plan.ManifestFingerprint {
		return errors.New("update plan stale")
	}
	if plan.ProjectMigration.State == "UNSUPPORTED_SOURCE_SCHEMA" || plan.ProjectMigration.State == "AMBIGUOUS" {
		_ = appendUpdateTransactionState(paths, id, "FAILED_BEFORE_MUTATION", "project_schema_unsupported")
		return errors.New("project migration unsupported")
	}
	_ = appendUpdateTransactionState(paths, id, "MIGRATIONS_PREPARED", "")
	if err := activateVersioned(paths, m, manifestData, archive, "execute", &plan, true); err != nil {
		_ = appendUpdateTransactionState(paths, id, "FAILED_RESTORED", "update_migration_failed")
		return err
	}
	_ = appendUpdateTransactionState(paths, id, "COMPLETED", "")
	return installfs.AtomicWrite(filepath.Join(paths.Executions, id+".json"), []byte(fmt.Sprintf("{\"planId\":%q,\"result\":\"SUCCESS\"}\n", id)))
}

func appendUpdateTransactionState(paths layout.Paths, planID, state, reason string) error {
	if err := os.MkdirAll(paths.Executions, 0o700); err != nil {
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
	file, err := os.OpenFile(filepath.Join(paths.Executions, planID+".jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.Write(append(data, '\n'))
	return err
}

func latestUpdatePlan(paths layout.Paths, planID string) bool {
	entries, err := os.ReadDir(paths.Plans)
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
