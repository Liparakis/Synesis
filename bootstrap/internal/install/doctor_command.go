package install

import (
	"errors"
	"flag"
	"fmt"
	"path/filepath"

	"synesis/bootstrap/internal/install/activepointer"
	"synesis/bootstrap/internal/install/bundlecheck"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/install/userpath"
)

// RunDoctor implements the doctor command.
func RunDoctor(args []string) error {
	flags := flag.NewFlagSet("doctor", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := layout.Resolve(*installDir)
	if err != nil {
		return err
	}
	pointer, _, err := activepointer.Read(paths, paths.Current)
	if err != nil || pointer == nil {
		return errors.New("installed pointer invalid")
	}
	bundle := filepath.Join(paths.Versions, pointer.PayloadDirectory)
	if err := bundlecheck.ValidateVersion(bundle, pointer.Version); err != nil {
		return err
	}
	if err := bundlecheck.RunDoctor(bundle); err != nil {
		return err
	}
	pathStatus := "MISSING"
	if userpath.Contains(paths) {
		pathStatus = "PASS"
	}
	fmt.Printf("INSTALL_ROOT=%s\nINSTALL_LAYOUT=VERSIONED_IMMUTABLE\nINSTALLED_VERSION=%s\nACTIVE_PAYLOAD=%s\nLAUNCHER=%s\nPATH_STATUS=%s\nDOCTOR_RESULT=PASS\n", paths.Root, pointer.Version, pointer.PayloadDirectory, paths.Launcher, pathStatus)
	return nil
}
