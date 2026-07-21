# SYN-005: Project-wide Reconciliation Protocol Design (Revised)

This document details the design for SYN-005, specifying the Project Reconciliation Protocol (PRP1) over a single authenticated Link session.

---

## 1. Protocol Flow & Sequential Phases

PRP1 is a sequential, client-orchestrated, bidirectional protocol with the following fixed phases:

1. **Authenticated Project Validation**: The client and host exchange and validate project IDs.
2. **Exchange Validated-Head Inventories**:
   - Each inventory entry contains `recordId`, `headVersion` (revision), and `headDigest`.
   - Inventory entries include only fully validated current heads.
   - If a peer has local corrupt records, it must identify them separately, preventing a `SUCCESS` convergence state.
3. **Compute Reconciliation Plans**: The client determines which records require upload, download, quarantine, or no action.
4. **Joiner Transfer**: The joiner uploads required contiguous revision chains (starting after host's known version, or at revision 1 if absent) to the host.
5. **Host Transfer**: The host downloads required contiguous revision chains (starting after joiner's known version, or at revision 1 if absent) to the joiner.
6. **Exchange Final Inventories & Classify Convergence**: The client swaps inventories with the host again. If they match exactly, a `SUCCESS` outcome is emitted.

---

## 2. Message Schemas (PRP1)

PRP1 messages are binary-encoded using a dedicated magic prefix to isolate them from single-record SRP1 messages.

### Envelope Structure
- **MAGIC**: `32-bit integer` -> `0x50525031` ("PRP1" in ASCII).
- **VERSION**: `16-bit integer` -> `1`.
- **KIND**: `8-bit integer` ->:
  - `1` (`VALIDATE_PROJECT`): Requests project ID validation.
  - `2` (`VALIDATE_PROJECT_ACK`): Acknowledges project ID validation.
  - `3` (`INVENTORY_EXCHANGE`): Sends validated-head inventory and corrupt records count.
  - `4` (`INVENTORY_EXCHANGE_ACK`): Acknowledges inventory and returns remote inventory.
  - `5` (`RECORD_BATCH`): Sends a batch of contiguous records/revisions.
  - `6` (`RECORD_BATCH_ACK`): Acknowledges batch storage.
  - `7` (`DOWNLOAD_REQUEST`): Requests specific contiguous revisions of a record.
  - `8` (`DOWNLOAD_RESPONSE`): Host returns the requested contiguous revisions.
  - `9` (`FINAL_INVENTORY`): Client sends final inventory to verify convergence.
  - `10` (`FINAL_INVENTORY_ACK`): Host returns its final inventory.
  - `11` (`ERROR`): Reports a protocol or validation error.

---

## 3. Reconciliation Matrix & Action Rules

When comparing inventories, each record ID is evaluated as follows:

- **Absent Locally / Present Remotely (Rev R)**: Download revisions `1..R`.
- **Present Locally (Rev L) / Absent Remotely**: Upload revisions `1..L`.
- **Present Locally (Rev L) / Present Remotely (Rev R, L == R, digests match)**: Duplicate. No action.
- **Present Locally (Rev L) / Present Remotely (Rev R, L < R, digests match)**: Stale Local. Download revisions `L+1..R`.
- **Present Locally (Rev L) / Present Remotely (Rev R, L > R, digests match)**: Ahead Local. Upload revisions `R+1..L`.
- **Present Locally (Rev L) / Present Remotely (Rev R, digests mismatch)**: Divergent. Valid conflicts are quarantined without replacing the local head.

---

## 4. Verification & Validation Rules

Before storing any received revision, it must be independently validated against the following rules:
- **Schema & Project Validation**: The project ID must match.
- **Owner & Author Validation**: Node identities must be valid and allowed by ProjectConfig.
- **Signature Verification**: Crytographic Ed25519 signature must match.
- **Predecessor & Continuity Validation**: Revision sequence must be contiguous (e.g. R must follow R-1) and the parent digest must match.
- **Quarantine**: If the revision represents a valid divergent head (i.e. valid signatures but different history), it is quarantined to prevent local head pollution.
- **Malformed Rejection**: Malformed, unsigned, or unverifiable input is immediately rejected and never written or quarantined.
- **No Deletion**: Valid local-only records are never deleted.
- **Safety Limits (Hard Session Bounds)**:
  - Max inventory entries: `1_000`
  - Max revisions per record: `100`
  - Max session bytes transferred: `10 MB`

---

## 5. Failure & Exit Semantics

- **Partial Progress**: Connection loss preserves already written durable mutations but returns `UNKNOWN`/`PARTIAL`.
- **Exit Code**: Non-zero (`10` or expected CLI code) if convergence fails.
- **Convergence Classify**: `SUCCESS` is emitted ONLY if final inventories match exactly and there are no local corruptions.
