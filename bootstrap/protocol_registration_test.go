package main

import (
	"errors"
	"testing"
)

type memoryProtocolStore struct {
	keys map[string]bool
	data map[string]map[string]string
}

func newMemoryProtocolStore() *memoryProtocolStore {
	return &memoryProtocolStore{keys: map[string]bool{}, data: map[string]map[string]string{}}
}

func (store *memoryProtocolStore) exists(key string) (bool, error) {
	return store.keys[key], nil
}

func (store *memoryProtocolStore) get(key, value string) (string, error) {
	values, ok := store.data[key]
	if !ok {
		return "", errProtocolValueNotFound
	}
	data, ok := values[value]
	if !ok {
		return "", errProtocolValueNotFound
	}
	return data, nil
}

func (store *memoryProtocolStore) set(key, value, data string) error {
	store.keys[key] = true
	if store.data[key] == nil {
		store.data[key] = map[string]string{}
	}
	store.data[key][value] = data
	return nil
}

func (store *memoryProtocolStore) deleteTree(key string) error {
	for current := range store.keys {
		if current == key || len(current) > len(key) && current[:len(key)+1] == key+`\` {
			delete(store.keys, current)
			delete(store.data, current)
		}
	}
	return nil
}

func TestProtocolCommandQuotesStableLauncherAndOneUriPlaceholder(t *testing.T) {
	const fixtureInstallRoot = `C:\Users\Test User\Apps\Synesis with spaces`
	paths := installPaths{root: fixtureInstallRoot,
		activation: fixtureInstallRoot + `\bin\synesis-activate.exe`}
	command, err := protocolCommand(paths)
	if err != nil {
		t.Fatal(err)
	}
	if command != `"C:\Users\Test User\Apps\Synesis with spaces\bin\synesis-activate.exe" activate "%1"` {
		t.Fatalf("unexpected handler command: %q", command)
	}
}

func TestProtocolRegistrationIsIdempotentAndRepairsOwnedStalePath(t *testing.T) {
	store := newMemoryProtocolStore()
	paths := installPaths{root: `C:\Install A`, activation: `C:\Install A\bin\synesis-activate.exe`}
	if err := registerProtocolWithStore(store, paths); err != nil {
		t.Fatal(err)
	}
	if err := registerProtocolWithStore(store, paths); err != nil {
		t.Fatal(err)
	}
	if err := store.set(protocolRootKey, protocolInstallName, `C:\Old Install`); err != nil {
		t.Fatal(err)
	}
	if err := registerProtocolWithStore(store, paths); err != nil {
		t.Fatal(err)
	}
	root, _ := store.get(protocolRootKey, protocolInstallName)
	command, _ := store.get(protocolCommandKey, "")
	if root != paths.root || command == "" {
		t.Fatalf("owned registration was not repaired: root=%q command=%q", root, command)
	}
}

func TestProtocolRegistrationRefusesUnownedExistingKey(t *testing.T) {
	store := newMemoryProtocolStore()
	paths := installPaths{root: `C:\Install`, activation: `C:\Install\bin\synesis-activate.exe`}
	if err := store.set(protocolRootKey, "", "Third Party"); err != nil {
		t.Fatal(err)
	}
	if err := registerProtocolWithStore(store, paths); err == nil {
		t.Fatal("unowned protocol registration was overwritten")
	}
}

func TestProtocolUnregisterRequiresOwnedMatchingInstallation(t *testing.T) {
	store := newMemoryProtocolStore()
	paths := installPaths{root: `C:\Install`, activation: `C:\Install\bin\synesis-activate.exe`}
	if err := registerProtocolWithStore(store, paths); err != nil {
		t.Fatal(err)
	}
	other := installPaths{root: `C:\Other`, activation: `C:\Other\bin\synesis-activate.exe`}
	if err := unregisterProtocolWithStore(store, other); err == nil {
		t.Fatal("unrelated installation removed Synesis registration")
	}
	if err := unregisterProtocolWithStore(store, paths); err != nil {
		t.Fatal(err)
	}
	if exists, _ := store.exists(protocolRootKey); exists {
		t.Fatal("owned registration remained after unregister")
	}
	if err := unregisterProtocolWithStore(store, paths); !errors.Is(err, nil) {
		t.Fatalf("idempotent unregister failed: %v", err)
	}
}
