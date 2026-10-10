//go:build windows

package protocolreg

import (
	"errors"

	"golang.org/x/sys/windows/registry"
)

// UserStore is the Store backed by the current user's registry hive.
type UserStore struct{}

func (UserStore) Exists(key string) (bool, error) {
	opened, err := registry.OpenKey(registry.CURRENT_USER, key, registry.QUERY_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, opened.Close()
}

func (UserStore) Get(key, value string) (string, error) {
	opened, err := registry.OpenKey(registry.CURRENT_USER, key, registry.QUERY_VALUE)
	if err != nil {
		if errors.Is(err, registry.ErrNotExist) {
			return "", ErrValueNotFound
		}
		return "", err
	}
	defer opened.Close()
	data, _, err := opened.GetStringValue(value)
	if errors.Is(err, registry.ErrNotExist) {
		return "", ErrValueNotFound
	}
	return data, err
}

func (UserStore) Set(key, value, data string) error {
	created, _, err := registry.CreateKey(registry.CURRENT_USER, key, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer created.Close()
	return created.SetStringValue(value, data)
}

func (UserStore) DeleteTree(key string) error {
	return deleteTree(registry.CURRENT_USER, key)
}

func deleteTree(parent registry.Key, path string) error {
	opened, err := registry.OpenKey(parent, path,
		registry.QUERY_VALUE|registry.ENUMERATE_SUB_KEYS|registry.SET_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	children, err := opened.ReadSubKeyNames(-1)
	closeErr := opened.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	for _, child := range children {
		if err := deleteTree(parent, path+"\\"+child); err != nil {
			return err
		}
	}
	return registry.DeleteKey(parent, path)
}
