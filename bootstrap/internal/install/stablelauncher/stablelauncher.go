// Package stablelauncher installs the launchers that live at the stable
// boundary of an installation and follow the active version pointer.
package stablelauncher

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
)

// Write writes the launcher scripts that resolve the active payload at run time.
func Write(paths layout.Paths) error {
	if runtime.GOOS == "windows" {
		ps1 := `param([Parameter(ValueFromRemainingArguments=$true)][string[]]$ForwardArgs)
$root = Split-Path -Parent $PSScriptRoot
try { $p = Get-Content -Raw -LiteralPath (Join-Path $root 'current.json') | ConvertFrom-Json } catch { exit 1 }
if ($p.schemaVersion -ne 1 -or $p.payloadDirectory -notmatch '^[A-Za-z0-9._-]+$' -or $p.payloadDirectory.Contains('..')) { exit 1 }
$r = Join-Path $root ('versions\' + $p.payloadDirectory)
$m = Join-Path $r 'manifest.json'
if (-not (Test-Path -LiteralPath $m)) { exit 1 }
$sha256 = [Security.Cryptography.SHA256]::Create()
try { $manifestHash = [BitConverter]::ToString($sha256.ComputeHash([IO.File]::ReadAllBytes($m))).Replace('-', '').ToLowerInvariant() } finally { $sha256.Dispose() }
if ($manifestHash -ne $p.manifestHash) { exit 1 }
$profile = [string]$p.protectionProfile
$profilePath = Join-Path $r 'PROTECTION_PROFILE'
if ($profile -ne 'maximum-release' -and (Test-Path -LiteralPath $profilePath)) {
try { $profile = (Get-Content -Raw -LiteralPath $profilePath).Trim() } catch { exit 1 }
}
if ($profile -eq 'maximum-release') {
$doctor = Join-Path $r 'bin\synesis-installer.exe'
if (-not (Test-Path -LiteralPath $doctor)) { exit 1 }
& $doctor doctor --install-dir $root
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$e = Join-Path $r 'bin\synesis.cmd'
if (-not (Test-Path -LiteralPath $e)) { exit 1 }
$command = [System.Collections.Generic.List[string]]::new()
[void]$command.Add(('"' + $e + '"'))
foreach ($argument in $ForwardArgs) {
if ($argument.Contains('"') -or $argument.Contains('%') -or $argument.Contains('!') -or $argument.Contains('^')) { exit 1 }
if ($argument -match '[\s&|<>()]') {
[void]$command.Add(('"' + $argument + '"'))
} else {
[void]$command.Add($argument)
}
}
$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = if ([string]::IsNullOrWhiteSpace($env:ComSpec)) { 'cmd.exe' } else { $env:ComSpec }
$startInfo.Arguments = '/d /s /c "' + ($command -join ' ') + '"'
$startInfo.UseShellExecute = $false
$child = [Diagnostics.Process]::Start($startInfo)
$child.WaitForExit()
exit $child.ExitCode
`
		if err := installfs.AtomicWrite(filepath.Join(paths.Bin, "synesis-launcher.ps1"), []byte(ps1)); err != nil {
			return err
		}
		return installfs.AtomicWrite(paths.Launcher, []byte("@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File \"%~dp0synesis-launcher.ps1\" %*\r\nexit /b %ERRORLEVEL%\r\n"))
	}
	if err := installfs.AtomicWrite(paths.Launcher, []byte("#!/bin/sh\nset -eu\nr=\"$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\"\np=\"$(sed -n 's/.*\"payloadDirectory\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' \"$r/current.json\")\"\ncase \"$p\" in (''|*..*|*/*|*\\\\*) exit 1;; esac\nprofile=\"$(sed -n 's/.*\"protectionProfile\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' \"$r/current.json\")\"\nprofilePath=\"$r/versions/$p/PROTECTION_PROFILE\"\nif [ \"$profile\" != maximum-release ] && [ -f \"$profilePath\" ]; then profile=\"$(sed -n '1{s/[[:space:]]*$//;p;}' \"$profilePath\")\"; fi\nif [ \"$profile\" = maximum-release ]; then\n  doctor=\"$r/versions/$p/bin/synesis-installer\"\n  [ -x \"$doctor\" ] || exit 1\n  if ! \"$doctor\" doctor --install-dir \"$r\" >/dev/null; then exit 1; fi\nfi\ne=\"$r/versions/$p/bin/synesis\"\nexec \"$e\" \"$@\"\n")); err != nil {
		return err
	}
	return os.Chmod(paths.Launcher, 0o755)
}

// SyncMCP keeps the provider registration stable while updating
// its native launcher to the version-aware implementation shipped by the
// active bundle. The launcher itself follows current.json at process start,
// so it can safely select the immutable payload activated below.
func SyncMCP(paths layout.Paths, payload string) error {
	source := filepath.Join(payload, "bin", layout.MCPLauncherName())
	data, err := os.ReadFile(source)
	if err != nil {
		return fmt.Errorf("read bundled MCP launcher: %w", err)
	}
	target := filepath.Join(paths.Bin, layout.MCPLauncherName())
	if err := installStableLauncher(target, data); err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		return os.Chmod(target, 0o755)
	}
	return syncStableCommandLauncher(paths, data)
}

// installStableLauncher writes a native launcher under a stable name.
//
// A provider keeps its MCP server running for as long as a session is open, and
// Windows will not write over an open image, so updating with any session open
// used to fail the whole installation. Windows does let an open image be
// renamed: the launcher in use is moved aside and the new one takes its name.
// Running servers keep the image they started from, and the next one starts
// from the new launcher. A copy moved aside is removed by a later
// installation, once nothing holds it.
func installStableLauncher(target string, launcher []byte) error {
	if replaced, err := filepath.Glob(target + replacedLauncherSuffix + "*"); err == nil {
		for _, stale := range replaced {
			_ = os.Remove(stale)
		}
	}
	if existing, err := os.ReadFile(target); err == nil && bytes.Equal(existing, launcher) {
		return nil
	}
	writeErr := installfs.AtomicWrite(target, launcher)
	if writeErr == nil {
		return nil
	}
	if info, err := os.Lstat(target); err != nil || !info.Mode().IsRegular() {
		return writeErr
	}
	reserved, err := os.CreateTemp(filepath.Dir(target), filepath.Base(target)+replacedLauncherSuffix)
	if err != nil {
		return writeErr
	}
	aside := reserved.Name()
	reserved.Close()
	// An image whose process started a moment ago is still held without delete sharing, by the
	// loader or by a scanner, and cannot be moved until that is released.
	moveErr := os.Rename(target, aside)
	for attempt := 0; moveErr != nil && attempt < 40; attempt++ {
		time.Sleep(50 * time.Millisecond)
		moveErr = os.Rename(target, aside)
	}
	if moveErr != nil {
		_ = os.Remove(aside)
		return writeErr
	}
	if err := installfs.AtomicWrite(target, launcher); err != nil {
		if restoreErr := os.Rename(aside, target); restoreErr != nil {
			return fmt.Errorf("launcher replacement failed: %w; the previous launcher is at %s", err, aside)
		}
		return err
	}
	_ = os.Remove(aside)
	return nil
}

const replacedLauncherSuffix = ".replaced-"

// syncStableCommandLauncher installs the native launcher under the name people
// type. Windows resolves synesis.exe ahead of synesis.cmd in the same
// directory, so the command stops paying for a script interpreter on every
// run, while synesis.cmd stays in place for hooks and registrations that name
// it explicitly.
//
// A long-running command keeps the launcher image open, and Windows will not
// replace an open image. Because the launcher only follows the active version
// pointer, an identical copy is already correct, and a differing one that
// cannot be replaced is still a working launcher: neither case may fail an
// otherwise valid activation.
func syncStableCommandLauncher(paths layout.Paths, launcher []byte) error {
	target := filepath.Join(paths.Bin, "synesis.exe")
	if _, err := os.ReadFile(target); err == nil {
		_ = installStableLauncher(target, launcher)
		return nil
	}
	return installStableLauncher(target, launcher)
}

// SyncActivation copies the native, argv-safe activation helper
// to the stable installation boundary. It is registered instead of the
// versioned Java launcher so updates cannot leave a stale protocol target.
func SyncActivation(paths layout.Paths, payload string) error {
	if runtime.GOOS != "windows" {
		return nil
	}
	source := filepath.Join(payload, "bin", "synesis-installer.exe")
	data, err := os.ReadFile(source)
	if err != nil {
		return fmt.Errorf("read bundled activation helper: %w", err)
	}
	if err := installfs.AtomicWrite(paths.Activation, data); err != nil {
		return err
	}
	return nil
}
