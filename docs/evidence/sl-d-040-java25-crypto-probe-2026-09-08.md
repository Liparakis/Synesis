# SL-D-040 Java 25 crypto compatibility probe

- Date: 2026-09-08
- Scope: API compatibility only; no production source or key material
- Environment: repository checkout at `ca681ca`; JShell version `25`

## Command

A disposable JShell probe generated two X25519 key pairs, derived the shared
secret from both directions, encrypted a six-byte plaintext with
ChaCha20-Poly1305 using associated data, decrypted it, and requested the JDK
HKDF-SHA256 implementation.

## Result

| Capability | Result |
|---|---|
| X25519 key generation | PASS |
| Bidirectional shared-secret equality | PASS; 32 bytes |
| ChaCha20-Poly1305 encryption/decryption | PASS; 22-byte ciphertext |
| Associated-data round trip | PASS |
| `KDF.getInstance("HKDF-SHA256")` | PASS |

## Boundary

This proves that the current Java 25 runtime exposes the required standard
primitive APIs. It does not prove the protocol design, transcript binding,
key lifetime, replay window, membership authorization, wire encoding, or
security of any implementation. Those remain covered by the SL-D-040 contract
and vector tests.
