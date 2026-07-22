# ADR-0024: Bootstrap trust and update model

## Status

Accepted for SYN-009C.2.

## Decision

Use a Go standard-library bootstrapper with a pinned Ed25519 public key,
detached signatures over exact manifest bytes, SHA-256 artifact checks, bounded
archive extraction, versioned staging, and atomic activation. Keep at least one
previous version and preserve project data on uninstall.

## Context and alternatives

Checksums alone do not authenticate a release. A hosted installer that embeds
Java or duplicates Synesis behavior expands the trust and maintenance surface.
The bootstrapper therefore performs distribution operations only; the Java CLI
remains authoritative for all product behavior.

## Failure behavior

Signature, size, checksum, archive-safety, or version-startup failure leaves the
previous active version untouched and removes staging. Update is idempotent.
Uninstall removes only bootstrap-owned installation state.

## Invalidation

Reconsider the key rotation model before the pinned key expires or a supported
multi-key manifest is required. Do not add background updates without a new
security and lifecycle decision.
