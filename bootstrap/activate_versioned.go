package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

func activateVersioned(paths installPaths, m releasemanifest.Manifest, manifestData, archive []byte, operation string, migrationPlan *updatePlan, updatePath bool, observers ...operationProgress) (err error) {
	reportOperation(observers, "runtime", "working", "Preparing and verifying runtime files")
	unlock, err := acquireUpdateLock(paths, operation)
	if err != nil {
		return err
	}
	defer unlock()
	var previous *activePointer
	var sourceHash string
	var restoreMigrations func() error
	var migrationsApplied bool
	var pointerActivated bool
	defer func() {
		if err != nil && migrationsApplied && restoreMigrations != nil {
			if pointerActivated && previous != nil {
				_ = writePointer(paths, paths.current, *previous, "")
			}
			if restoreErr := restoreMigrations(); restoreErr != nil {
				err = fmt.Errorf("migration rollback failed: %w", restoreErr)
			}
		}
	}()
	previous, sourceHash, err = readPointer(paths, paths.current)
	if err != nil {
		if operation != "repair" {
			return err
		}
		// Repair is allowed to recover from a damaged payload manifest or
		// binary, but it still requires a structurally valid current pointer so
		// it cannot guess which installation it is replacing.
		previous, sourceHash, err = readLoosePointer(paths, paths.current)
		if err != nil {
			return err
		}
		sourceHash = ""
	}
	staging, err := os.MkdirTemp(filepath.Dir(paths.root), filepath.Base(paths.root)+".staging-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	if err := bundlearchive.Extract(bytes.NewReader(archive), staging); err != nil {
		return err
	}
	bundle := bundlearchive.Flatten(staging)
	if err := validateBundleVersion(bundle, m.Version); err != nil {
		return err
	}
	if err := runBundleDoctor(bundle); err != nil {
		return err
	}
	token := m.Version + "-" + payload.Digest(archive)[:12]
	if !payload.ValidDirectory(token) {
		return errors.New("payload directory invalid")
	}
	if err := os.MkdirAll(paths.versions, 0o755); err != nil {
		return err
	}
	target := filepath.Join(paths.versions, token)
	if !fileExists(target) {
		if err := writePayloadManifest(bundle, m.Version, manifestData); err != nil {
			return err
		}
		if err := os.Rename(bundle, target); err != nil {
			return err
		}
		if err := makePayloadImmutable(target); err != nil {
			if restoreErr := removePayload(target); restoreErr != nil {
				return fmt.Errorf("payload hardening failed: %w; cleanup failed: %v", err, restoreErr)
			}
			return err
		}
	}
	payloadManifest, err := os.ReadFile(filepath.Join(target, "manifest.json"))
	if err != nil {
		return err
	}
	protectionProfile, err := readProtectionProfile(target)
	if err != nil {
		return err
	}
	if err := syncStableMcpLauncher(paths, target); err != nil {
		return err
	}
	if err := writeStableLauncher(paths); err != nil {
		return err
	}
	if err := syncStableActivationLauncher(paths, target); err != nil {
		return err
	}
	if migrationPlan != nil {
		restoreMigrations, err = executePreparedMigrations(paths, *migrationPlan)
		if err != nil {
			return err
		}
		migrationsApplied = true
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "BACKUPS_VERIFIED", "")
	}
	if previous != nil {
		if err := writePointer(paths, paths.previous, *previous, ""); err != nil {
			return err
		}
	}
	pointer := activePointer{
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
	if err := writePointer(paths, paths.current, pointer, sourceHash); err != nil {
		return err
	}
	pointerActivated = true
	reportOperation(observers, "runtime", "done", "Verified runtime activated")
	if migrationPlan != nil {
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "POINTER_ACTIVATED", "")
	}
	// Remove only the obsolete text pointer after the new pointer is durable.
	_ = os.Remove(filepath.Join(paths.root, "current"))
	if updatePath {
		reportOperation(observers, "shell", "working", "Updating the user PATH")
		if err := pathUpdater(paths, true); err != nil {
			return err
		}
		reportOperation(observers, "shell", "done", "Shell integration updated")
	}
	if migrationPlan != nil {
		_ = appendUpdateTransactionState(paths, migrationPlan.PlanID, "INSTALLED_SMOKE_TEST_PASSED", "")
	}
	return nil
}

func rollbackVersioned(paths installPaths) error {
	unlock, err := acquireUpdateLock(paths, "rollback")
	if err != nil {
		return err
	}
	defer unlock()
	previous, _, err := readPointer(paths, paths.previous)
	if err != nil || previous == nil {
		return errors.New("update rollback unavailable")
	}
	current, currentHash, err := readPointer(paths, paths.current)
	if err != nil || current == nil {
		return errors.New("active pointer invalid")
	}
	if err := writePointer(paths, paths.previous, *current, ""); err != nil {
		return err
	}
	if err := writePointer(paths, paths.current, *previous, currentHash); err != nil {
		return err
	}
	return atomicWrite(filepath.Join(paths.executions, "rollback-"+time.Now().UTC().Format("20060102T150405.000000000Z")+".json"), []byte("{\"result\":\"ROLLBACK_SUCCESS\"}\n"))
}
