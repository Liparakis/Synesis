// Package protocolreg owns the policy for registering the synesis:// URL
// protocol: a registration is only ever overwritten or removed when it is
// marked as Synesis-owned and, for removal, belongs to the same installation.
// The registry itself sits behind Store, so the policy runs against an
// in-memory store in tests and against HKCU on Windows.
package protocolreg

import (
	"errors"
	"fmt"
	"strings"
)

const (
	RootKey     = `Software\Classes\synesis`
	CommandKey  = RootKey + `\shell\open\command`
	ManagedName = "SynesisManaged"
	InstallName = "SynesisInstallRoot"
)

// ErrValueNotFound is returned by Store.Get for a missing key or value.
var ErrValueNotFound = errors.New("protocol registry value not found")

// Store is the small registry surface needed by the ownership policy.
type Store interface {
	Exists(key string) (bool, error)
	Get(key, value string) (string, error)
	Set(key, value, data string) error
	DeleteTree(key string) error
}

// Command is the shell command registered for the protocol: the stable
// activation helper with exactly one URI placeholder.
func Command(activation string) (string, error) {
	if strings.ContainsAny(activation, "\"\r\n") {
		return "", errors.New("stable launcher path cannot be represented safely")
	}
	return fmt.Sprintf("\"%s\" activate \"%%1\"", activation), nil
}

// Register writes the registration for the installation at root, whose
// activation helper is activation. An existing registration that is not
// Synesis-owned is refused.
func Register(store Store, root, activation string) error {
	exists, err := store.Exists(RootKey)
	if err != nil {
		return err
	}
	if exists {
		managed, readErr := store.Get(RootKey, ManagedName)
		if readErr != nil && !errors.Is(readErr, ErrValueNotFound) {
			return readErr
		}
		if readErr != nil || managed != "1" {
			return errors.New("existing synesis protocol registration is not Synesis-owned")
		}
	}
	command, err := Command(activation)
	if err != nil {
		return err
	}
	values := []struct {
		key, name, data string
	}{
		{RootKey, "", "URL:Synesis Protocol"},
		{RootKey, "URL Protocol", ""},
		{RootKey, ManagedName, "1"},
		{RootKey, InstallName, root},
		{CommandKey, "", command},
	}
	for _, value := range values {
		if err := store.Set(value.key, value.name, value.data); err != nil {
			return err
		}
	}
	return nil
}

// Unregister removes the registration if it is Synesis-owned and belongs to
// the installation at root. A missing registration is not an error.
func Unregister(store Store, root string) error {
	exists, err := store.Exists(RootKey)
	if err != nil || !exists {
		return err
	}
	managed, err := store.Get(RootKey, ManagedName)
	if errors.Is(err, ErrValueNotFound) {
		return errors.New("existing synesis protocol registration is not Synesis-owned")
	}
	if err != nil {
		return err
	}
	if managed != "1" {
		return errors.New("existing synesis protocol registration is not Synesis-owned")
	}
	installRoot, err := store.Get(RootKey, InstallName)
	if err != nil {
		return errors.New("Synesis protocol registration ownership is incomplete")
	}
	if !strings.EqualFold(installRoot, root) {
		return errors.New("Synesis protocol registration belongs to another installation")
	}
	return store.DeleteTree(RootKey)
}
