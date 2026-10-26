#!/usr/bin/env bash
# Renders the release manifest and the OS package definitions for one release.
#
#   render-packages.sh <version> <tag> <repo> <assets-dir> <out-dir>
#
# <assets-dir> holds the release files: synesis-<version>-<platform>.zip (Windows) or .tar.gz
# for each of the six platforms, and the raw installers synesis-windows-x64.exe and
# synesis-windows-arm64.exe. Every @NAME@ in packaging/ is filled from them; a missing
# asset or an unfilled name stops the script, so a package never ships with a blank hash.
# The manifest is written once, byte for byte, because its signature covers those bytes.
set -euo pipefail

version=$1 tag=$2 repo=$3 assets=$4 out=$5
root=$(cd "$(dirname "$0")/../.." && pwd)
base="https://github.com/${repo}/releases/download/${tag}"
platforms="windows-x64 windows-arm64 linux-x64 linux-arm64 macos-x64 macos-arm64"

[[ $version =~ ^[A-Za-z0-9._-]+$ ]] || { echo "version has characters a path cannot hold" >&2; exit 1; }
mkdir -p "$out"

sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { wc -c < "$1" | tr -d ' '; }
upper() { echo "$1" | tr 'a-z-' 'A-Z_'; }

subst=()
entries=""
for platform in $platforms; do
  ext=tar.gz
  [[ $platform == windows-* ]] && ext=zip
  file="synesis-${version}-${platform}.${ext}"
  [[ -f $assets/$file ]] || { echo "missing release asset: $file" >&2; exit 1; }
  digest=$(sha "$assets/$file")
  subst+=(-e "s|@SHA_$(upper "$platform")@|${digest}|g")
  [[ -n $entries ]] && entries+=$',\n'
  entries+="    \"${platform}\": {\"url\": \"${file}\", \"sha256\": \"${digest}\", \"size\": $(size "$assets/$file")}"
done
for platform in windows-x64 windows-arm64; do
  file="synesis-${platform}.exe"
  [[ -f $assets/$file ]] || { echo "missing release asset: $file" >&2; exit 1; }
  subst+=(-e "s|@SHA_EXE_$(upper "$platform")@|$(sha "$assets/$file")|g")
done
subst+=(-e "s|@VERSION@|${version}|g" -e "s|@TAG@|${tag}|g" -e "s|@BASE_URL@|${base}|g" -e "s|@REPO@|${repo}|g")

# Artifact URLs are relative: the installer resolves them against the manifest's own URL, so the
# manifest must be uploaded next to the archives.
{
  printf '{\n  "schemaVersion": 1,\n  "channel": "stable",\n  "version": "%s",\n' "$version"
  printf '  "publishedAt": "%s",\n  "minimumBootstrapVersion": "0.1.0",\n  "developmentOnly": false,\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "artifacts": {\n%s\n  }\n}\n' "$entries"
} > "$out/manifest.json"

while IFS= read -r -d '' template; do
  target="$out/${template#"$root"/}"
  mkdir -p "$(dirname "$target")"
  sed "${subst[@]}" "$template" > "$target"
  if grep -n '@[A-Z0-9_]*@' "$target"; then
    echo "unfilled name in $target" >&2
    exit 1
  fi
done < <(find "$root/packaging" -type f -print0)
echo "rendered manifest.json and packaging/ into $out"
