#!/usr/bin/env bash
# Checks render-packages.sh against fake release files: the manifest is complete and the
# packages carry the real hashes, and a missing asset stops the script.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
assets=$work/assets out=$work/out
mkdir -p "$assets"

for p in windows-x64 windows-arm64; do
  echo "zip $p" > "$assets/synesis-9.9.9-$p.zip"
  echo "exe $p" > "$assets/synesis-$p.exe"
done
for p in linux-x64 linux-arm64 macos-x64 macos-arm64; do
  echo "tar $p" > "$assets/synesis-9.9.9-$p.tar.gz"
done

bash "$here/render-packages.sh" 9.9.9 v9.9.9 owner/repo "$assets" "$out" > /dev/null

expected=$(sha256sum "$assets/synesis-9.9.9-macos-arm64.tar.gz" | cut -d' ' -f1)
grep -q "\"macos-arm64\": {\"url\": \"synesis-9.9.9-macos-arm64.tar.gz\", \"sha256\": \"$expected\"" "$out/manifest.json"
grep -q "sha256 \"$expected\"" "$out/packaging/homebrew/synesis.rb"
expected_exe=$(sha256sum "$assets/synesis-windows-x64.exe" | cut -d' ' -f1)
grep -q "InstallerSha256: $expected_exe" "$out/packaging/winget/Synesis.Synesis.installer.yaml"
grep -q "releases/download/v9.9.9/synesis-windows-arm64.exe" "$out/packaging/winget/Synesis.Synesis.installer.yaml"
grep -q "^version=9.9.9" "$out/packaging/debian/build-deb.sh"
[[ $(grep -c '"url"' "$out/manifest.json") -eq 6 ]]
if grep -rq '@[A-Z_]*@' "$out"; then echo "unfilled name" >&2; exit 1; fi

rm "$assets/synesis-9.9.9-linux-arm64.tar.gz"
if bash "$here/render-packages.sh" 9.9.9 v9.9.9 owner/repo "$assets" "$work/out2" 2> /dev/null; then
  echo "a missing asset did not stop the script" >&2
  exit 1
fi
echo "render-packages: ok"
