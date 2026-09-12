package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
)

const (
	protocolRootKey     = `Software\Classes\synesis`
	protocolCommandKey  = protocolRootKey + `\shell\open\command`
	protocolManagedName = "SynesisManaged"
	protocolInstallName = "SynesisInstallRoot"
)

var errProtocolValueNotFound = errors.New("protocol registry value not found")

// protocolStore is the small registry surface needed by the ownership policy.
// The Windows adapter implements it with HKCU; tests use an in-memory store so
// focused registration checks never mutate the developer hive.
type protocolStore interface {
	exists(key string) (bool, error)
	get(key, value string) (string, error)
	set(key, value, data string) error
	deleteTree(key string) error
}

func protocolCommand(paths installPaths) (string, error) {
	if strings.ContainsAny(paths.activation, "\"\r\n") {
		return "", errors.New("stable launcher path cannot be represented safely")
	}
	return fmt.Sprintf("\"%s\" activate \"%%1\"", paths.activation), nil
}

func registerProtocolWithStore(store protocolStore, paths installPaths) error {
	exists, err := store.exists(protocolRootKey)
	if err != nil {
		return err
	}
	if exists {
		managed, readErr := store.get(protocolRootKey, protocolManagedName)
		if readErr != nil && !errors.Is(readErr, errProtocolValueNotFound) {
			return readErr
		}
		if readErr != nil || managed != "1" {
			return errors.New("existing synesis protocol registration is not Synesis-owned")
		}
	}
	command, err := protocolCommand(paths)
	if err != nil {
		return err
	}
	values := []struct {
		key, name, data string
	}{
		{protocolRootKey, "", "URL:Synesis Protocol"},
		{protocolRootKey, "URL Protocol", ""},
		{protocolRootKey, protocolManagedName, "1"},
		{protocolRootKey, protocolInstallName, paths.root},
		{protocolCommandKey, "", command},
	}
	for _, value := range values {
		if err := store.set(value.key, value.name, value.data); err != nil {
			return err
		}
	}
	return nil
}

func unregisterProtocolWithStore(store protocolStore, paths installPaths) error {
	exists, err := store.exists(protocolRootKey)
	if err != nil || !exists {
		return err
	}
	managed, err := store.get(protocolRootKey, protocolManagedName)
	if errors.Is(err, errProtocolValueNotFound) {
		return errors.New("existing synesis protocol registration is not Synesis-owned")
	}
	if err != nil {
		return err
	}
	if managed != "1" {
		return errors.New("existing synesis protocol registration is not Synesis-owned")
	}
	installRoot, err := store.get(protocolRootKey, protocolInstallName)
	if err != nil {
		return errors.New("Synesis protocol registration ownership is incomplete")
	}
	if !strings.EqualFold(installRoot, paths.root) {
		return errors.New("Synesis protocol registration belongs to another installation")
	}
	return store.deleteTree(protocolRootKey)
}

func runRegisterProtocol(args []string) error {
	flags := flag.NewFlagSet("register-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if _, err := os.Stat(paths.activation); err != nil {
		return fmt.Errorf("stable Synesis activation helper is missing: %w", err)
	}
	if err := registerProtocol(paths); err != nil {
		return err
	}
	fmt.Printf("PROTOCOL_REGISTRATION=SUCCESS\nLAUNCHER=%s\n", paths.launcher)
	return nil
}

func runUnregisterProtocol(args []string) error {
	flags := flag.NewFlagSet("unregister-protocol", flag.ContinueOnError)
	installDir := flags.String("install-dir", "", "installation root")
	if err := flags.Parse(args); err != nil {
		return err
	}
	paths, err := installationPaths(*installDir)
	if err != nil {
		return err
	}
	if err := unregisterProtocol(paths); err != nil {
		return err
	}
	fmt.Println("PROTOCOL_REGISTRATION=REMOVED")
	return nil
}
