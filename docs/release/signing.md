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
