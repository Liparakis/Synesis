package protocolreg

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

func (store *memoryProtocolStore) Exists(key string) (bool, error) {
	return store.keys[key], nil
}

func (store *memoryProtocolStore) Get(key, value string) (string, error) {
	values, ok := store.data[key]
	if !ok {
		return "", ErrValueNotFound
	}
	data, ok := values[value]
	if !ok {
		return "", ErrValueNotFound
	}
	return data, nil
}

func (store *memoryProtocolStore) Set(key, value, data string) error {
	store.keys[key] = true
	if store.data[key] == nil {
		store.data[key] = map[string]string{}
	}
	store.data[key][value] = data
	return nil
}

func (store *memoryProtocolStore) DeleteTree(key string) error {
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
	command, err := Command(fixtureInstallRoot + `\bin\synesis-activate.exe`)
	if err != nil {
		t.Fatal(err)
	}
	if command != `"C:\Users\Test User\Apps\Synesis with spaces\bin\synesis-activate.exe" activate "%1"` {
		t.Fatalf("unexpected handler command: %q", command)
	}
}

func TestProtocolRegistrationIsIdempotentAndRepairsOwnedStalePath(t *testing.T) {
	store := newMemoryProtocolStore()
	root, activation := `C:\Install A`, `C:\Install A\bin\synesis-activate.exe`
	if err := Register(store, root, activation); err != nil {
		t.Fatal(err)
	}
	if err := Register(store, root, activation); err != nil {
		t.Fatal(err)
	}
	if err := store.Set(RootKey, InstallName, `C:\Old Install`); err != nil {
		t.Fatal(err)
	}
	if err := Register(store, root, activation); err != nil {
		t.Fatal(err)
	}
	registered, _ := store.Get(RootKey, InstallName)
	command, _ := store.Get(CommandKey, "")
	if registered != root || command == "" {
		t.Fatalf("owned registration was not repaired: root=%q command=%q", registered, command)
	}
}

func TestProtocolRegistrationRefusesUnownedExistingKey(t *testing.T) {
	store := newMemoryProtocolStore()
	if err := store.Set(RootKey, "", "Third Party"); err != nil {
		t.Fatal(err)
	}
	if err := Register(store, `C:\Install`, `C:\Install\bin\synesis-activate.exe`); err == nil {
		t.Fatal("unowned protocol registration was overwritten")
	}
}

func TestProtocolUnregisterRequiresOwnedMatchingInstallation(t *testing.T) {
	store := newMemoryProtocolStore()
	root, activation := `C:\Install`, `C:\Install\bin\synesis-activate.exe`
	if err := Register(store, root, activation); err != nil {
		t.Fatal(err)
	}
	if err := Unregister(store, `C:\Other`); err == nil {
		t.Fatal("unrelated installation removed Synesis registration")
	}
	if err := Unregister(store, root); err != nil {
		t.Fatal(err)
	}
	if exists, _ := store.Exists(RootKey); exists {
		t.Fatal("owned registration remained after unregister")
	}
	if err := Unregister(store, root); !errors.Is(err, nil) {
		t.Fatalf("idempotent unregister failed: %v", err)
	}
}
