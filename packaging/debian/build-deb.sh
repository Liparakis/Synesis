#!/usr/bin/env bash
# Builds synesis_<version>_<arch>.deb from the release's linux tar.gz.
#
#   build-deb.sh <linux-x64|linux-arm64> <bundle.tar.gz> <out-dir>
#
# The bundle goes to /opt/synesis and only the launcher is linked into /usr/bin. The package
# is for apt repositories the owner hosts (docs/installation/packages.md). Needs dpkg-deb.
set -euo pipefail

platform=$1 archive=$2 out=$3
version=@VERSION@
case $platform in
  linux-x64) arch=amd64 ;;
  linux-arm64) arch=arm64 ;;
  *) echo "unsupported platform: $platform" >&2; exit 1 ;;
esac

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/opt/synesis" "$stage/usr/bin" "$stage/DEBIAN"
tar -xzf "$archive" -C "$stage/opt/synesis" --strip-components=1
ln -s /opt/synesis/bin/synesis "$stage/usr/bin/synesis"
cat > "$stage/DEBIAN/control" <<CONTROL
Package: synesis
Version: ${version}
Architecture: ${arch}
Maintainer: Synesis <https://github.com/@REPO@>
Section: devel
Priority: optional
Homepage: https://github.com/@REPO@
Description: Coordination layer for AI coding agents working in one project
CONTROL
mkdir -p "$out"
dpkg-deb --build --root-owner-group "$stage" "$out/synesis_${version}_${arch}.deb"
