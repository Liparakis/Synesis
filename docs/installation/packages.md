# OS packages and the signed release manifest

ADR-0103 stage 4. The repository builds everything that needs no account or key. Four steps need the owner.

## What a `v*` tag produces

The `release-artifacts` workflow, for a pushed `v*` tag, builds each platform archive at the tag's version
(`synesis-<version>-<platform>.zip` on Windows, `.tar.gz` elsewhere) next to the raw installers, then the
`package-release` job runs `scripts/release-packages/render-packages.sh`. It writes into the run's `packages` artifact:

| File | For |
| --- | --- |
| `manifest.json` | The release manifest the installer's `--manifest` and update path read. Artifact URLs are relative, so it is uploaded next to the archives. |
| `packaging/winget/*.yaml` | A winget manifest set (`Synesis.Synesis`), pointing at the raw `.exe` installers. |
| `packaging/homebrew/synesis.rb` | A Homebrew formula for macOS and Linux, pointing at the `.tar.gz` bundles. |
| `packaging/debian/build-deb.sh` | Builds `synesis_<version>_<arch>.deb` from a linux archive (`dpkg-deb` needed). |

The SHA-256 fields come from the files in the release, never from a guess. A missing file or an unfilled name stops
the render. `scripts/release-packages/render-packages-test.sh` checks this and runs in the job first.

## Signing, today

* The manifest is signed with Ed25519 over its exact bytes (`bootstrap/cmd/sign-manifest`). The installer verifies it
  in `releasemanifest.Verify`.
* **While the placeholder key is pinned** (`releasemanifest.PlaceholderTrustRootHex`, used by `bootstrap/main.go`),
  behaviour is what it was before: a signed manifest cannot verify (nobody holds that key's private half), and an
  unsigned manifest is accepted only from a local file URL that declares `developmentOnly`.
* **Once any other key is pinned**, every manifest must carry a valid signature from it. The unsigned development path
  is refused. Acceptance mode (`--acceptance`) is separate and unchanged.
* **Without the signing secret**, the `package-release` job says so in its summary, signs nothing and publishes no
  manifest. The package definitions are still kept. **With the secret but the placeholder still pinned**, the job
  fails, because a manifest signed then could not be verified by any installer. With the secret and a real key
  pinned, it signs, checks the signature against the pinned key, and uploads `manifest.json` and `manifest.json.sig`.

## What the owner does

1. **Create the signing key.** Generate an Ed25519 key pair somewhere private. The private key is the base64 of the
   64-byte Ed25519 private key (Go `ed25519.PrivateKey`); the public key is its 32 bytes as 64 hex characters.
   Keep the private key out of the repository and back it up.
2. **Store it as a GitHub secret** named `SYNESIS_MANIFEST_PRIVATE_KEY_B64` (repository secret).
3. **Pin the public key.** In `bootstrap/main.go`, change `manifestPublicKeyHex` from
   `releasemanifest.PlaceholderTrustRootHex` to the 64-character hex string in quotes. The workflow reads that line to
   check the secret matches. Rebuild and ship that installer first: older installers still carry the placeholder.
4. **Register the package channels** (none is done; nothing has been submitted anywhere):
   * winget: a fork of `microsoft/winget-pkgs`, then a pull request with the rendered `packaging/winget` files for
     each release. Confirm the `PackageIdentifier` and `Publisher` in the templates.
   * Homebrew: a tap repository named `homebrew-<name>`; put the rendered `synesis.rb` in `Formula/`.
   * apt: a repository to host the `.deb` files and an apt signing key (Cloudsmith, Packagecloud, or a self-hosted
     `aptly` or `reprepro` tree). This key is separate from the manifest key.

## Not verified, so check before the first submission

* The winget template runs the raw installer with `install` as its silent switch. This is the embedded installer's
  `install` command; it was not run through winget. Test with `winget validate` and a sandbox install.
* The Homebrew formula and `build-deb.sh` were not run. They rely on the archive layout
  (`synesis-<version>-<platform>/bin/synesis` with the bundled runtime), and the launcher finding its root from its own
  path. A Homebrew or apt install is not managed by the Synesis updater: updates go through the package manager.
* macOS and Windows code signing and notarization are still not implemented (see `docs/release/signing.md`); users
  will see operating system warnings on the raw installers.
