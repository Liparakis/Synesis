# Project record v1 — CP-R2 decision bytes

## Scope

CP-R2 defines one local, signed record type: `decision`. It has no network
messages, sync loop, peer allowlist, background behavior, or second record
type. CP-R4 will consume the verified Link application stream later.

## Canonical `SDR1` encoding

All integers are big-endian. Text is strict UTF-8 with an unsigned 16-bit byte
length. The complete record is capped at 16,384 bytes and consists of:

```text
magic "SDR1" (4)
schema version (1 byte = 1)
project UUID (16)
record UUID (16)
record type "decision" (8)
revision (signed 64-bit, > 0)
predecessor marker (1; 0 only for revision 1, otherwise 32-byte SHA-256)
owner node ID, author node ID
status (1: PROPOSED, ACCEPTED, REJECTED, SUPERSEDED)
createdAtMillis, updatedAtMillis (signed 64-bit UTC milliseconds)
title (1..512 UTF-8 bytes)
rationale (1..4096 UTF-8 bytes)
evidence count (1..8)
  repeated: kind (1..64), reference (1..1024), SHA-256 digest (32)
owner public key (1..256 bytes, Ed25519 X.509)
signature (1..128 bytes, Ed25519 over every preceding byte)
```

The owner and author are equal in v1. The owner ID must be the lowercase
`sl1-` SHA-256 identifier derived from the embedded public key. Evidence is
sorted by kind, reference, and digest before encoding. Timestamps must have
millisecond precision and `updatedAt` may not precede `createdAt`.

The record digest is SHA-256 of the complete signed bytes. A successor carries
the immediately preceding complete-record digest; wall-clock time is never a
freshness or ordering authority.

## Local storage

Each profile uses:

```text
<profile>/decisions/<record-id>/<revision>.sdr
<profile>/heads/<record-id>.head
```

The immutable revision is written to a sibling temporary file, forced, and
atomically moved into place before the head is replaced the same way. Startup
validates every chain and reconstructs a missing or older head from durable
revisions. Corrupt bytes fail recovery; they are never silently selected.

## Inspection

The JDK-only `synesis-project-record` launcher supports:

```text
synesis-project-record inspect <profile-dir> <project-id> <record-id>
```

It prints stable IDs, revision, digest, status, provenance, bounded text,
evidence count, and signature validity. It never prints private key material,
network endpoints, or an absolute profile path.

## CP-R4 bounded exchange

Project configuration is strict UTF-8 text containing one `projectId=<uuid>`
line and zero or more `peer=<sl1-node-id>` lines, capped at 4,096 bytes and 32
peers. The Link application-stream payload is one canonical `SRP1` message,
also capped at 4,096 bytes. Its only kinds are:

```text
SYNC_REQUEST: project UUID, record UUID, known revision, optional 32-byte digest
RECORD: unsigned-16 length and canonical SDR1 bytes
RESULT: outcome, record UUID, resulting revision, optional 32-byte digest
ERROR: error class and strict UTF-8 text capped at 256 bytes
```

The exchange is one-shot. The receiver first checks the authenticated Link
remote node against the explicit allowlist, then checks project ID, owner and
author binding, signature, bounds, revision, and predecessor digest before
mutating its local head. Equal signed bytes produce `DUPLICATE`; a lower
revision produces `REMOTE_STALE`; a valid divergent same-version or gap record
produces `CONFLICT` and is stored below `conflicts/` without changing the head.
Trust or project failures produce `REJECTED`. A transport close before a
result is observed produces `UNKNOWN`; no retry or background behavior is
implied.
