// Package userpath adds the installation's bin directory to the user PATH, or
// removes it, and tells running desktop processes that the environment changed.
package userpath

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"synesis/bootstrap/internal/install/layout"
)

// Update adds (install) or removes the installation's bin directory on the
// user PATH using the platform mechanism.
func Update(paths layout.Paths, install bool) error {
	if runtime.GOOS == "windows" {
		return updateWindowsUserPath(paths, install)
	}
	return updateUnixUserPath(paths, install)
}

// Contains reports whether the user PATH already holds the installation's bin directory.
func Contains(paths layout.Paths) bool {
	if runtime.GOOS == "windows" {
		value, _, err := readWindowsUserPath()
		return err == nil && pathContains(value, paths.Bin, true)
	}
	if pathContains(os.Getenv("PATH"), paths.Bin, false) {
		return true
	}
	profile, err := os.UserHomeDir()
	if err != nil {
		return false
	}
	data, err := os.ReadFile(filepath.Join(profile, ".profile"))
	return err == nil && strings.Contains(string(data), pathBegin)
}

func updateWindowsUserPath(paths layout.Paths, install bool) error {
	value, valueType, err := readWindowsUserPath()
	if err != nil {
		return err
	}
	merged := mergePath(value, paths.Bin, true, install)
	if merged != value {
		if err := writeWindowsUserPath(merged, valueType); err != nil {
			return err
		}
		notifyEnvironmentChanged()
	}
	os.Setenv("PATH", mergePath(os.Getenv("PATH"), paths.Bin, true, install))
	if install {
		fmt.Println("PATH_UPDATE=USER", "PATH_ENTRY="+paths.Bin, "NEW_TERMINAL_REQUIRED=true")
	}
	return nil
}

const (
	pathBegin = "# >>> Synesis PATH >>>"
	pathEnd   = "# <<< Synesis PATH <<<"
)

func updateUnixUserPath(paths layout.Paths, install bool) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	profile := filepath.Join(home, ".profile")
	data, err := os.ReadFile(profile)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	content := string(data)
	updated := removeManagedPathBlock(content)
	if install && !pathContains(os.Getenv("PATH"), paths.Bin, false) {
		if updated != "" && !strings.HasSuffix(updated, "\n") {
			updated += "\n"
		}
		updated += pathBegin + "\nexport PATH=\"$PATH:" + shellDoubleQuote(paths.Bin) + "\"\n" + pathEnd + "\n"
	}
	if updated != content {
		if err := writeUserFile(profile, []byte(updated)); err != nil {
			return err
		}
	}
	os.Setenv("PATH", mergePath(os.Getenv("PATH"), paths.Bin, false, install))
	if install {
		fmt.Println("PATH_UPDATE=USER_PROFILE", "PATH_ENTRY="+paths.Bin, "NEW_TERMINAL_REQUIRED=true")
	}
	return nil
}

func removeManagedPathBlock(content string) string {
	for {
		start := strings.Index(content, pathBegin)
		if start < 0 {
			return content
		}
		remainder := content[start:]
		end := strings.Index(remainder, pathEnd)
		if end < 0 {
			return content[:start]
		}
		end += len(pathEnd)
		if end < len(remainder) && remainder[end] == '\n' {
			end++
		}
		content = content[:start] + remainder[end:]
	}
}

func shellDoubleQuote(value string) string {
	return strings.NewReplacer("\\", "\\\\", "\"", "\\\"", "$", "\\$", "`", "\\`").Replace(value)
}

func writeUserFile(path string, data []byte) error {
	mode := os.FileMode(0o644)
	if info, err := os.Stat(path); err == nil {
		mode = info.Mode().Perm()
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".synesis-path-")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		_ = os.Remove(path)
		return os.Rename(temporaryPath, path)
	}
	return nil
}

var readWindowsUserPath = readWindowsUserPathImpl

var writeWindowsUserPath = writeWindowsUserPathImpl

func readWindowsUserPathImpl() (string, string, error) {
	if runtime.GOOS != "windows" {
		return os.Getenv("PATH"), "", nil
	}
	output, err := exec.Command("reg.exe", "query", `HKCU\Environment`, "/v", "Path").CombinedOutput()
	if err != nil {
		return "", "REG_EXPAND_SZ", nil
	}
	for _, line := range strings.Split(string(output), "\n") {
		line = strings.TrimSpace(line)
		for _, valueType := range []string{"REG_EXPAND_SZ", "REG_SZ"} {
			if index := strings.Index(line, valueType); index >= 0 {
				return strings.TrimSpace(line[index+len(valueType):]), valueType, nil
			}
		}
	}
	return "", "REG_EXPAND_SZ", nil
}

func writeWindowsUserPathImpl(value, valueType string) error {
	if valueType != "REG_SZ" && valueType != "REG_EXPAND_SZ" {
		valueType = "REG_EXPAND_SZ"
	}
	return exec.Command("reg.exe", "add", `HKCU\Environment`, "/v", "Path", "/t", valueType, "/d", value, "/f").Run()
}

func mergePath(value, entry string, windows, add bool) string {
	separator := string(os.PathListSeparator)
	if windows {
		separator = ";"
	}
	entries := strings.Split(value, separator)
	result := make([]string, 0, len(entries)+1)
	found := false
	for _, candidate := range entries {
		if pathEntriesEqual(candidate, entry, windows) {
			if add && !found {
				result = append(result, candidate)
			}
			found = true
			continue
		}
		result = append(result, candidate)
	}
	if add && !found {
		result = append(result, entry)
	}
	if !add && len(result) == 1 && result[0] == "" && value == "" {
		return ""
	}
	return strings.Join(result, separator)
}

func pathContains(value, entry string, windows bool) bool {
	separator := string(os.PathListSeparator)
	if windows {
		separator = ";"
	}
	for _, candidate := range strings.Split(value, separator) {
		if pathEntriesEqual(candidate, entry, windows) {
			return true
		}
	}
	return false
}

func pathEntriesEqual(left, right string, windows bool) bool {
	left = strings.TrimSpace(left)
	right = strings.TrimSpace(right)
	if windows {
		left = strings.TrimRight(strings.ReplaceAll(left, "/", "\\"), "\\")
		right = strings.TrimRight(strings.ReplaceAll(right, "/", "\\"), "\\")
		return strings.EqualFold(left, right)
	}
	return filepath.Clean(left) == filepath.Clean(right)
}
