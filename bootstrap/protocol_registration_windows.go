//go:build windows

package main

import (
	"errors"
	"golang.org/x/sys/windows/registry"
)

type windowsProtocolStore struct{}

func (windowsProtocolStore) exists(key string) (bool, error) {
	opened, err := registry.OpenKey(registry.CURRENT_USER, key, registry.QUERY_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, opened.Close()
}

func (windowsProtocolStore) get(key, value string) (string, error) {
	opened, err := registry.OpenKey(registry.CURRENT_USER, key, registry.QUERY_VALUE)
	if err != nil {
		if errors.Is(err, registry.ErrNotExist) {
			return "", errProtocolValueNotFound
		}
		return "", err
	}
	defer opened.Close()
	data, _, err := opened.GetStringValue(value)
	if errors.Is(err, registry.ErrNotExist) {
		return "", errProtocolValueNotFound
	}
	return data, err
}

func (windowsProtocolStore) set(key, value, data string) error {
	created, _, err := registry.CreateKey(registry.CURRENT_USER, key, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer created.Close()
	return created.SetStringValue(value, data)
}

func (windowsProtocolStore) deleteTree(key string) error {
	return deleteWindowsRegistryTree(registry.CURRENT_USER, key)
}

func deleteWindowsRegistryTree(parent registry.Key, path string) error {
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
		if err := deleteWindowsRegistryTree(parent, path+"\\"+child); err != nil {
			return err
		}
	}
	return registry.DeleteKey(parent, path)
}

func registerProtocol(paths installPaths) error {
	return registerProtocolWithStore(windowsProtocolStore{}, paths)
}

func unregisterProtocol(paths installPaths) error {
	return unregisterProtocolWithStore(windowsProtocolStore{}, paths)
}
