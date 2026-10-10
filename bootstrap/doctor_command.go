package main

import (
	"errors"
	"flag"
	"fmt"
	"path/filepath"
)

func runDoctor(args []string) error {
	flags := flag.NewFlagSet("doctor", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	pointer, _, err := readPointer(paths, paths.current)
	if err != nil || pointer == nil {
		return errors.New("installed pointer invalid")
	}
	bundle := filepath.Join(paths.versions, pointer.PayloadDirectory)
	if err := validateBundleVersion(bundle, pointer.Version); err != nil {
		return err
	}
	if err := runBundleDoctor(bundle); err != nil {
		return err
	}
	pathStatus := "MISSING"
	if userPathContains(paths) {
		pathStatus = "PASS"
	}
	fmt.Printf("INSTALL_ROOT=%s\nINSTALL_LAYOUT=VERSIONED_IMMUTABLE\nINSTALLED_VERSION=%s\nACTIVE_PAYLOAD=%s\nLAUNCHER=%s\nPATH_STATUS=%s\nDOCTOR_RESULT=PASS\n", paths.root, pointer.Version, pointer.PayloadDirectory, paths.launcher, pathStatus)
	return nil
}
