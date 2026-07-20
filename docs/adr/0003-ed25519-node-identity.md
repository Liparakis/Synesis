# ADR-0003: JDK Ed25519 node identity

- Status: ACCEPTED
- Date: 2026-07-20

## Decision

Use the JDK's Ed25519 key-pair generator and `Signature` implementation. Public keys use their standard X.509 `SubjectPublicKeyInfo` encoding; private keys use PKCS#8 only inside the storage abstraction. The node ID is lowercase hexadecimal SHA-256 over the canonical encoded public key, prefixed with `sl1-`.

## Context

Java 25 documents EdEC/Ed25519 support and the standard `Signature` API. The contract requires a maintained modern signature algorithm, deterministic node IDs, no custom cryptography, and an external secure-storage integration point.

## Consequences

The first implementation has no cryptographic dependency and uses the platform provider. Private keys never appear in public values or logs. The file store remains replaceable by an operating-system or hardware-backed implementation through `IdentityStore`.

## Rejected alternatives

RSA and legacy EC signatures add larger keys or more algorithm choices without a product requirement. Adding a cryptographic library before the JDK primitive is insufficient would add supply-chain and replacement cost.

## Reopen when

An approved platform lacks Ed25519, a hardware-backed key store requires a different operation boundary, or a protocol interoperability requirement supplies evidence for another maintained algorithm.

Sources: [Java 25 EdECPrivateKey](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/interfaces/EdECPrivateKey.html), [Java 25 Signature](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/Signature.html), [Java 25 provider algorithms](https://docs.oracle.com/en/java/javase/25/security/oracle-providers.html).
