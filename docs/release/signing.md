# Release signing

The manifest is signed as detached bytes with Ed25519. The private key is a CI
secret containing base64-encoded PKCS-free 64-byte Ed25519 private-key bytes;
it is never committed or written into an artifact. The bootstrapper pins the
corresponding public key in `bootstrap/main.go`.

The checked-in key is a development placeholder and must be replaced with the
project's protected public key before a public release. Authenticode, Apple
Developer ID signing, and notarization are not implemented.
