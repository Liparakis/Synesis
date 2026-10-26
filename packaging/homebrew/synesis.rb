# Template: scripts/release-packages/render-packages.sh fills the names written between at-signs from the release files.
# It belongs in the owner's tap repository (docs/installation/packages.md), as Formula/synesis.rb.
class Synesis < Formula
  desc "Coordination layer for AI coding agents working in one project"
  homepage "https://github.com/@REPO@"
  version "@VERSION@"
  license "AGPL-3.0-only"

  on_macos do
    on_arm do
      url "@BASE_URL@/synesis-@VERSION@-macos-arm64.tar.gz"
      sha256 "@SHA_MACOS_ARM64@"
    end
    on_intel do
      url "@BASE_URL@/synesis-@VERSION@-macos-x64.tar.gz"
      sha256 "@SHA_MACOS_X64@"
    end
  end

  on_linux do
    on_arm do
      url "@BASE_URL@/synesis-@VERSION@-linux-arm64.tar.gz"
      sha256 "@SHA_LINUX_ARM64@"
    end
    on_intel do
      url "@BASE_URL@/synesis-@VERSION@-linux-x64.tar.gz"
      sha256 "@SHA_LINUX_X64@"
    end
  end

  # The archive is a self-contained bundle (own Java runtime); its launcher finds its
  # root from its own path, so the whole bundle goes in libexec and only the launcher is linked.
  def install
    libexec.install Dir["*"]
    bin.write_exec_script libexec/"bin/synesis"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/synesis version")
  end
end
