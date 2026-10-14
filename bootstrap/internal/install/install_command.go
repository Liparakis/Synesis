package install

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"synesis/bootstrap/internal/bundlearchive"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/payload"
	"synesis/bootstrap/internal/releasemanifest"
)

// OperationProgress reports completed backend boundaries without estimating time or bytes.
// A nil observer keeps command-line operation behavior unchanged.
type OperationProgress func(stage, status, detail string)

// ReportOperation delivers one backend boundary to every non-nil observer.
func ReportOperation(observers []OperationProgress, stage, status, detail string) {
	for _, observer := range observers {
		if observer != nil {
			observer(stage, status, detail)
		}
	}
}

// RunInstall implements both "install" and "update": fetch and verify the
// manifest, resolve the platform-appropriate artifact, verify its checksum,
// and atomically activate it.
func RunInstall(operation string, args []string, observers ...OperationProgress) error {
	flags := flag.NewFlagSet(operation, flag.ContinueOnError)
	manifestURL := flags.String("manifest", os.Getenv("SYNESIS_MANIFEST_URL"), "manifest URL")
	bundlePath := flags.String("bundle", "", "verified local bundle path")
	installDir := flags.String("install-dir", "", "installation root")
	prepare := flags.Bool("prepare", false, "prepare an immutable update plan")
	execute := flags.Bool("execute", false, "execute a prepared update plan")
	acceptance := flags.Bool("acceptance", false, "use the explicitly configured local acceptance trust root")
	skipPathUpdate := flags.Bool("skip-path-update", false, "do not modify the user PATH (disposable acceptance only)")
	allowLink := flags.Bool("allow-link", false, "add the Windows Firewall rule that lets other computers connect (asks for administrator approval)")
	planID := flags.String("plan", "", "prepared update plan ID")
	rollback := flags.Bool("rollback", false, "roll back the last successful update")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	if *rollback {
		return rollbackVersioned(paths)
	}
	if *execute {
		if *planID == "" {
			return errors.New("--execute requires --plan")
		}
		return executeUpdatePlan(paths, *planID)
	}
	if operation == "update" && !*prepare && !*rollback {
		return errors.New("update requires --prepare or --execute")
	}
	if *bundlePath != "" {
		if operation == "update" {
			return errors.New("local bundles support install or repair; use a signed manifest for update")
		}
		ReportOperation(observers, "verify", "working", "Verifying the local bundle")
		archive, m, manifestData, err := loadLocalBundle(*bundlePath)
		if err != nil {
			return err
		}
		ReportOperation(observers, "verify", "done", "Local bundle verified")
		if *prepare {
			id, err := prepareUpdatePlan(paths, m, manifestData, nil, archive, *acceptance)
			if err != nil {
				return err
			}
			fmt.Printf("UPDATE_RESULT=PREPARED\nPLAN_ID=%s\n", id)
			return nil
		}
		if err := activateVersioned(paths, m, manifestData, archive, operation, nil, !*skipPathUpdate, observers...); err != nil {
			return err
		}
		fmt.Printf("INSTALL_RESULT=SUCCESS\nVERSION=%s\nPLATFORM=%s\n", m.Version, releasemanifest.PlatformID())
		if *allowLink {
			reportAllowLink(paths.Root)
		}
		return nil
	}
	if *manifestURL == "" {
		return errors.New("SYNESIS_MANIFEST_URL or --manifest is required")
	}
	data, signature, err := releasemanifest.FetchManifest(*manifestURL)
	if err != nil {
		return err
	}
	m, err := releasemanifest.Parse(data)
	if err != nil {
		return errors.New("manifest signature or contents invalid")
	}
	if err := verifyManifestAuthenticity(m, data, signature, *acceptance); err != nil {
		return err
	}
	selected, ok := m.Artifacts[releasemanifest.PlatformID()]
	if !ok {
		return fmt.Errorf("unsupported platform: %s", releasemanifest.PlatformID())
	}
	archive, err := releasemanifest.Fetch(releasemanifest.ResolveArtifactURL(*manifestURL, selected.URL))
	if err != nil {
		return err
	}
	if int64(len(archive)) != selected.Size || !releasemanifest.MatchesSHA256(archive, selected.SHA256) {
		return errors.New("artifact size or SHA-256 mismatch")
	}
	if *prepare {
		id, err := prepareUpdatePlan(paths, m, data, signature, archive, *acceptance)
		if err != nil {
			return err
		}
		fmt.Printf("UPDATE_RESULT=PREPARED\nPLAN_ID=%s\n", id)
		return nil
	}
	if err := activateVersioned(paths, m, data, archive, "install", nil, !*skipPathUpdate); err != nil {
		return err
	}
	fmt.Printf("INSTALL_RESULT=SUCCESS\nVERSION=%s\nPLATFORM=%s\n", m.Version, releasemanifest.PlatformID())
	if *allowLink {
		reportAllowLink(paths.Root)
	}
	return nil
}

// RunRepair reinstalls the bundled application payload without invoking any
// provider lifecycle command or modifying project workspaces. Installation
// bookkeeping (the active payload pointer and integrity manifest) is updated
// because those records are required to activate the repaired binaries safely.
func RunRepair(args []string, observers ...OperationProgress) error {
	flags := flag.NewFlagSet("repair", flag.ContinueOnError)
	bundlePath := flags.String("bundle", "", "local bundle directory or archive")
	installDir := flags.String("install-dir", "", "installation root")
	skipPathUpdate := flags.Bool("skip-path-update", false, "do not modify the user PATH (disposable acceptance only)")
	allowLink := flags.Bool("allow-link", false, "add the Windows Firewall rule that lets other computers connect (asks for administrator approval)")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *bundlePath == "" {
		var err error
		*bundlePath, err = bundlecheck.RunningRoot()
		if err != nil {
			return errors.New("repair requires --bundle when the installer is not inside a bundle")
		}
	}
	ReportOperation(observers, "verify", "working", "Verifying the local repair bundle")
	archive, m, manifestData, err := loadLocalBundle(*bundlePath)
	if err != nil {
		return err
	}
	ReportOperation(observers, "verify", "done", "Local bundle verified")
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	if !installfs.FileExists(paths.Root) {
		return errors.New("no Synesis installation found to repair")
	}
	if err := activateVersioned(paths, m, manifestData, archive, "repair", nil, !*skipPathUpdate, observers...); err != nil {
		return err
	}
	fmt.Printf("REPAIR_RESULT=SUCCESS\nVERSION=%s\nPLATFORM=%s\n", m.Version, releasemanifest.PlatformID())
	if *allowLink {
		reportAllowLink(paths.Root)
	}
	return nil
}

// loadLocalBundle turns an extracted bundle or a downloaded bundle archive
// into the same verified input used by the signed-manifest installer. Local
// installation is intentionally self-contained: the archive is not fetched
// and no provider command is run.
func loadLocalBundle(path string) ([]byte, releasemanifest.Manifest, []byte, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	var archive []byte
	if info.IsDir() {
		archive, err = bundlearchive.ZipDirectory(path)
	} else {
		archive, err = os.ReadFile(path)
	}
	if err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	if int64(len(archive)) > bundlearchive.MaxExtractedBytes {
		return nil, releasemanifest.Manifest{}, nil, errors.New("bundle is too large")
	}
	staging, err := os.MkdirTemp("", "synesis-local-bundle-")
	if err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	defer os.RemoveAll(staging)
	if err := bundlearchive.Extract(bytes.NewReader(archive), staging); err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	bundle := bundlearchive.Flatten(staging)
	versionData, err := os.ReadFile(filepath.Join(bundle, "VERSION"))
	if err != nil {
		return nil, releasemanifest.Manifest{}, nil, errors.New("local bundle missing VERSION")
	}
	version := strings.TrimSpace(string(versionData))
	if !releasemanifest.ValidVersion(version) {
		return nil, releasemanifest.Manifest{}, nil, errors.New("local bundle VERSION invalid")
	}
	if err := bundlecheck.ValidateVersion(bundle, version); err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	data, err := json.Marshal(releasemanifest.Manifest{
		SchemaVersion: 1,
		Channel:       "local",
		Version:       version,
		PublishedAt:   "local",
		Artifacts: map[string]releasemanifest.Artifact{releasemanifest.PlatformID(): {
			URL:    "local",
			SHA256: payload.Digest(archive),
			Size:   int64(len(archive)),
		}},
	})
	if err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	var m releasemanifest.Manifest
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, releasemanifest.Manifest{}, nil, err
	}
	return archive, m, data, nil
}
