package install

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/install/daemonstop"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/migration"
	"synesis/bootstrap/internal/install/payloadmanifest"
	"synesis/bootstrap/internal/install/stablelauncher"
	"synesis/bootstrap/internal/install/userpath"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

// pathUpdater is injectable so disposable installation tests never edit the
// developer's real user PATH.
var pathUpdater = userpath.Update

// stopDaemon is injectable so disposable installation tests never stop the
// developer's real daemon.
var stopDaemon = daemonstop.Stop

func activateVersioned(paths layout.Paths, m releasemanifest.Manifest, manifestData, archive []byte, operation string, migrationPlan *updatePlan, updatePath bool, observers ...OperationProgress) (err error) {
	ReportOperation(observers, "runtime", "working", "Preparing and verifying runtime files")
	unlock, err := acquireUpdateLock(paths, operation)
	if err != nil {
		return err
	}
	defer unlock()
	var previous *activepointer.Pointer
	var sourceHash string
	var restoreMigrations func() error
	var migrationsApplied bool
	var pointerActivated bool
	defer func() {
		if err != nil && migrationsApplied && restoreMigrations != nil {
			if pointerActivated && previous != nil {
				_ = activepointer.Write(paths, paths.Current, *previous, "")
			}
			if restoreErr := restoreMigrations(); restoreErr != nil {
				err = fmt.Errorf("migration rollback failed: %w", restoreErr)
			}
		}
	}()
	previous, sourceHash, err = activepointer.Read(paths, paths.Current)
	if err != nil {
		if operation != "repair" {
			return err
		}
		// Repair is allowed to recover from a damaged payload manifest or
		// binary, but it still requires a structurally valid current pointer so
		// it cannot guess which installation it is replacing.
		previous, sourceHash, err = activepointer.ReadLoose(paths, paths.Current)
		if err != nil {
			return err
		}
		sourceHash = ""
	}
	staging, err := os.MkdirTemp(filepath.Dir(paths.Root), filepath.Base(paths.Root)+".staging-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	if err := bundlearchive.Extract(bytes.NewReader(archive), staging); err != nil {
		return err
	}
	bundle := bundlearchive.Flatten(staging)
	if err := bundlecheck.ValidateVersion(bundle, m.Version); err != nil {
		return err
	}
	if err := bundlecheck.RunDoctor(bundle); err != nil {
		return err
	}
	token := m.Version + "-" + payload.Digest(archive)[:12]
	if !payload.ValidDirectory(token) {
		return errors.New("payload directory invalid")
	}
	if err := os.MkdirAll(paths.Versions, 0o755); err != nil {
		return err
	}
	target := filepath.Join(paths.Versions, token)
	if !installfs.FileExists(target) {
		if err := payloadmanifest.Write(bundle, m.Version, manifestData); err != nil {
			return err
		}
		if err := os.Rename(bundle, target); err != nil {
			return err
		}
		if err := payloadmanifest.MakeImmutable(target); err != nil {
			if restoreErr := installfs.RemovePayload(target); restoreErr != nil {
				return fmt.Errorf("payload hardening failed: %w; cleanup failed: %v", err, restoreErr)
			}
			return err
		}
	}
	payloadManifest, err := os.ReadFile(filepath.Join(target, "manifest.json"))
	if err != nil {
		return err
	}
	protectionProfile, err := payloadmanifest.ReadProtectionProfile(target)
	if err != nil {
		return err
	}
	if err := stablelauncher.SyncMCP(paths, target); err != nil {
		return err
	}
	if err := stablelauncher.Write(paths); err != nil {
		return err
	}
	if err := stablelauncher.SyncActivation(paths, target); err != nil {
		return err
	}
	if migrationPlan != nil {
		restoreMigrations, err = migration.Execute(paths, migrationPlan.PlanID, migrationPlan.ProviderMigrations, migrationPlan.ProjectMigration, migrationPlan.ProjectRoot,
			func(state, reason string) error {
				return appendUpdateTransactionState(paths, migrationPlan.PlanID, state, reason)
			})
		if err != nil {
			return err
		}
		migrationsApplied = true
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "BACKUPS_VERIFIED", "")
	}
	if previous != nil {
		if err := activepointer.Write(paths, paths.Previous, *previous, ""); err != nil {
			return err
		}
	}
	pointer := activepointer.Pointer{
		SchemaVersion:     1,
		Version:           m.Version,
		PayloadDirectory:  token,
		ManifestHash:      payload.Digest(payloadManifest),
		ProtectionProfile: protectionProfile,
		ActivatedAt:       time.Now().UTC().Format(time.RFC3339Nano),
	}
	if previous != nil {
		pointer.PreviousVersion = previous.Version
	}
	if migrationPlan != nil {
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "POINTER_ACTIVATING", "")
	}
	if err := activepointer.Write(paths, paths.Current, pointer, sourceHash); err != nil {
		return err
	}
	pointerActivated = true
	// The daemon of the build just replaced would keep serving it. The next
	// command starts the new one. A daemon that will not stop does not undo an
	// activation that succeeded.
	if stopDaemon(paths.Root) != nil {
		ReportOperation(observers, "runtime", "warning", "The running Synesis daemon could not be stopped; it keeps the previous version until it exits")
	}
	ReportOperation(observers, "runtime", "done", "Verified runtime activated")
	if migrationPlan != nil {
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "POINTER_ACTIVATED", "")
	}
	// Remove only the obsolete text pointer after the new pointer is durable.
	_ = os.Remove(filepath.Join(paths.Root, "current"))
	if updatePath {
		ReportOperation(observers, "shell", "working", "Updating the user PATH")
		if err := pathUpdater(paths, true); err != nil {
			return err
		}
		ReportOperation(observers, "shell", "done", "Shell integration updated")
	}
	if migrationPlan != nil {
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "INSTALLED_SMOKE_TEST_PASSED", "")
	}
	return nil
}

func rollbackVersioned(paths layout.Paths) error {
	unlock, err := acquireUpdateLock(paths, "rollback")
	if err != nil {
		return err
	}
	defer unlock()
	previous, _, err := activepointer.Read(paths, paths.Previous)
	if err != nil || previous == nil {
		return errors.New("update rollback unavailable")
	}
	current, currentHash, err := activepointer.Read(paths, paths.Current)
	if err != nil || current == nil {
		return errors.New("active pointer invalid")
	}
	if err := activepointer.Write(paths, paths.Previous, *current, ""); err != nil {
		return err
	}
	if err := activepointer.Write(paths, paths.Current, *previous, currentHash); err != nil {
		return err
	}
	return installfs.AtomicWrite(filepath.Join(paths.Executions, "rollback-"+time.Now().UTC().Format("20060102T150405.000000000Z")+".json"), []byte("{\"result\":\"ROLLBACK_SUCCESS\"}\n"))
}
