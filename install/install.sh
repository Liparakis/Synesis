#!/bin/sh
set -eu
base=${SYNESIS_BOOTSTRAP_BASE_URL:?Set SYNESIS_BOOTSTRAP_BASE_URL before running the installer.}
version=${SYNESIS_BOOTSTRAP_VERSION:-0.1.0-dev.local}
case "$(uname -s):$(uname -m)" in
  Linux:x86_64) platform=linux-x64 ;;
  Linux:aarch64|Linux:arm64) platform=linux-arm64 ;;
  Darwin:x86_64) platform=macos-x64 ;;
  Darwin:arm64) platform=macos-arm64 ;;
  *) echo "Unsupported platform: $(uname -s):$(uname -m)" >&2; exit 2 ;;
esac
tmp=$(mktemp)
checksums=$(mktemp)
trap 'rm -f "$tmp" "$checksums"' EXIT
name="synesis-bootstrap-$version-$platform"
curl -fsSL "$base/$name" -o "$tmp"
curl -fsSL "$base/checksums.txt" -o "$checksums"
expected=$(awk -v name="$name" '$2 == name { print $1; exit }' "$checksums")
[ -n "$expected" ] || { echo "No published checksum for $name" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$tmp" | awk '{print $1}'); else actual=$(shasum -a 256 "$tmp" | awk '{print $1}'); fi
[ "$actual" = "$expected" ] || { echo "Bootstrap checksum mismatch for $name" >&2; exit 1; }
chmod 755 "$tmp"
"$tmp" install --manifest "$base/manifest.json"
