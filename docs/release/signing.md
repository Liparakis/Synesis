# Release signing

The manifest is signed as detached bytes with Ed25519. The private key is a CI
secret containing base64-encoded PKCS-free 64-byte Ed25519 private-key bytes;
it is never committed or written into an artifact. The bootstrapper pins the
corresponding public key in `bootstrap/main.go`.

The release workflow signs the manifest of a `v*` tag only when the secret exists, and the installer
refuses unsigned manifests once a non-placeholder key is pinned; see `docs/installation/packages.md`.

The checked-in key is a development placeholder and must be replaced with the
project's protected public key before a public release. Authenticode, Apple
Developer ID signing, and notarization are not implemented.

## Build provenance (keyless, built 2026-11-16)

A release run (`workflow_dispatch` or a `v*` tag) attests each raw installer and platform archive with
`actions/attest-build-provenance`: GitHub signs, with the run's own short-lived identity (Sigstore), which
repository, commit and workflow built the file. No key is created, stored or held. Anyone checks a downloaded file
with `gh attestation verify <file> --repo Liparakis/Synesis`. This proves where a file came from; it is not code
signing, so Windows SmartScreen and macOS Gatekeeper still warn. Not yet run: no release run has been made since.

## Code signing: what the owner obtains

None of these is created by an agent; each needs the owner's identity.

* **Windows (Authenticode).** One of: [SignPath Foundation](https://signpath.org/) (free for open-source projects
  under an approved licence; the certificate is issued to the foundation, which is shown as the publisher, and releases
  must credit it), [Azure Artifact Signing](https://azure.microsoft.com/products/artifact-signing) (formerly Trusted
  Signing; about $9.99 a month, identity validation, eligibility for individuals to be checked), or an OV certificate
  from a certificate authority on a hardware token or cloud HSM. **Chosen 2026-11-16: SignPath Foundation**, wired
  in the `bundles` job (below).
* **macOS (Developer ID and notarization).** An Apple Developer Program membership gives a Developer ID Application
  certificate and a notary API key; both become repository secrets.
* **Release manifest.** The Ed25519 manifest key above (`docs/installation/packages.md`).

## Windows signing through SignPath Foundation

On a Windows release run, once the `SIGNPATH_API_TOKEN` secret is set, the `bundles` job uploads the raw installer as a
workflow artifact, submits it with `signpath/github-action-submit-signing-request@v3`, waits for the approver, checks
the returned file has a valid Authenticode signature, and uses it for the attestation and the release upload. Without
the secret the steps are skipped and the installer ships unsigned. The platform `.zip` and the files inside the
installer are not signed by this step. Not yet run.

What the owner does:

1. Apply at <https://signpath.org/apply> (OSI licence: AGPL-3.0-only; MFA on GitHub and SignPath for every member).
2. The policy page is `CODE_SIGNING_POLICY.md`; the repository's home page must link it as "Code signing policy",
   and the release pages should too. Today's `README.md` is a leftover acceptance-project file.
3. In SignPath: add the predefined "GitHub.com" trusted build system to the project, an artifact configuration whose
   root is the installer `.exe` (the upload is not zipped), product name `Synesis`, and a release signing policy with
   the owner as approver.
4. In GitHub (Settings, Secrets and variables, Actions): the secret `SIGNPATH_API_TOKEN`, and the variables
   `SIGNPATH_ORGANIZATION_ID`, `SIGNPATH_PROJECT_SLUG` and `SIGNPATH_SIGNING_POLICY_SLUG`.
5. Make a release run and approve the request in SignPath.

The archive-only native hardening audit records this boundary separately from
the detached manifest signature. Passing PE stripping/trim-path checks does
not make a native launcher signed; production Authenticode, Apple Developer
ID, and notarization authority remain required release inputs.

The existing signer preserves its default `manifest.json` and
`manifest.json.sig` paths and also accepts explicit `--manifest` and
`--signature` paths. The release-only maximum task uses those explicit paths
inside `build/maximum-release/`, then verifies the detached signature against
the public key compiled into `bootstrap/main.go`. This does not create a key,
replace the trust root, or make the commercial protection rings pass; the
private key remains an injected CI secret and the current local key is not a
production release authority.
