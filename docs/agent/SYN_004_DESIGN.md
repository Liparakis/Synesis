# SYN-004: Minimal Guided Workspace Demo Flow Design

This document details the design for SYN-004, focusing on reducing the two-person workspace demo flow to the fewest safe operator steps.

---

## 1. Exact Operator Flow

The guided demo flow consists of the following streamlined steps:

### Step 1: Operator B Identity Show
Operator B bootstraps their profile identity and shares their Node ID with Operator A:
```powershell
& $ws --profile $profileB identity show
```
*Output:*
```text
NODE_ID=sl1-<B_NODE_ID_HEX>
```

### Step 2: Operator A Project Creation
Operator A creates a project allowlisting Operator B's Node ID:
```powershell
& $ws --profile $profileA project create --peer sl1-<B_NODE_ID_HEX>
```
*Output:*
```text
NODE_ID=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
PEER_NODE_ID=sl1-<B_NODE_ID_HEX>
PROJECT_CONFIGURED=true
```

### Step 3: Operator A Decision Creation
Operator A creates a signed decision record:
```powershell
& $ws --profile $profileA decision create --title 'Signed Truth' --rationale 'Sample record' --evidence-kind text --evidence-ref refA --evidence-sha256 <SHA256>
```
*Output:*
```text
RECORD_ID=<RECORD_UUID>
REVISION=1
DIGEST=<DIGEST_HEX>
STATUS=PROPOSED
```

### Step 4: Operator A Sync Host (Single Copyable Link)
Operator A starts the sync host, specifying the target project ID and record ID to share:
```powershell
& $ws --profile $profileA sync host --project <PROJECT_UUID> --record <RECORD_UUID>
```
*Output:*
```text
INVITATION_LINK=synesis://join/<signed-invitation-payload>?project=<PROJECT_UUID>&record=<RECORD_UUID>&host=sl1-<A_NODE_ID_HEX>
```
*Note:* A single copyable link is generated. It embeds the Link invitation payload along with project ID, record ID, and host Node ID query parameters.

### Step 5: Operator B Sync Join (Zero-Config Connection)
Operator B joins and performs the sync by executing a single command passing the invitation link:
```powershell
& $ws --profile $profileB sync join 'synesis://join/<signed-invitation-payload>?project=<PROJECT_UUID>&record=<RECORD_UUID>&host=sl1-<A_NODE_ID_HEX>'
```
*Output:*
```text
PINNED_HOST=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
RECORD_ID=<RECORD_UUID>
SYNC_RESULT=APPLIED
```

---

## 2. Command Contract

### `sync host` Subcommand
- **Arguments**:
  - `--project <uuid>`: The target project ID (UUID).
  - `--record <uuid>`: The target record ID (UUID).
- **Behavior**:
  - Validates that project ID and record ID are well-formed UUIDs.
  - Generates the Link invitation string using the existing `OnboardingSeam`.
  - Composes and prints the single invitation URI on standard output:
    `INVITATION_LINK=synesis://join/<invitation>?project=<project-id>&record=<record-id>&host=<host-node-id>`
  - Listens for a single connection, authenticates the peer, and handles the sync request from B.

### `sync join` Subcommand
- **Arguments**:
  - `<invitation-uri>`: The full connection URI (positional parameter).
- **Behavior**:
  - Parses the connection URI scheme and extracts:
    - `invitation`: Path segment of the URI.
    - `project`: `project` query parameter.
    - `record`: `record` query parameter.
    - `host`: `host` query parameter.
  - Checks if the project is configured locally (refuses if not configured).
  - Enforces host Node ID pinning: connects using the extracted `invitation` and verifies that the host's public key matches the pinned `host` Node ID.
  - Replicates the specific `record` under `project` via the CP-R4 sync protocol.
  - Returns `0` on `APPLIED` or `DUPLICATE` outcome. Returns `10` on any failure.

---

## 3. Security Implications

- **Host Pinning Integrity**: The host Node ID is embedded inside the connection URI. B's client enforces that the remote node's public identity matches this pinned Node ID during the handshake. If an attacker intercepts the invitation link and attempts to masquerade as the host, the connection will fail to authenticate because the attacker does not control the private key matching the host Node ID.
- **URI Tampering**: If an attacker tampers with the URI (e.g. replacing the host Node ID with their own), B's client will connect to the attacker. However, this is identical to manual flag tampering and is mitigated by sharing the connection link over trusted out-of-band communication channels (e.g. Signal, direct file copy).
- **Secret Redaction**: No private keys or absolute paths are exposed in the URI or console logs.

---

## 4. Failure Behavior & Contextual Hints

Failed operations must output a clean error code (`10`) and provide stderr hints to guide the developer:

1. **Host Pinning Failure** (attacker or wrong host identity):
   - *Error*: `ERROR=AUTH_FAILED`
   - *Stderr Hint*: `HINT: The host node public identity did not match the expected pinned host Node ID.`
2. **Project Not Configured**:
   - *Error*: `ERROR=PROJECT_NOT_CONFIGURED`
   - *Stderr Hint*: `HINT: Run 'project create --peer <host-node-id>' first to authorize the sync.`
3. **Mismatched Peer Config**:
   - *Error*: `ERROR=PEER_MISMATCH`
   - *Stderr Hint*: `HINT: The peer configured for this project does not match the host node ID.`
4. **Malformed Connection Link**:
   - *Error*: `ERROR=USAGE`
   - *Stderr Hint*: `HINT: The connection link is invalid. Ensure all query parameters (project, record, host) are present.`
5. **Network / Timeout**:
   - *Error*: `ERROR=TRANSPORT_FAILED`
   - *Stderr Hint*: `HINT: Connection timed out or was refused. Check your network or firewall settings and ensure the host is running.`

---

## 5. Verification & Test Plan

- **Unit Tests**:
  - Test URI parsing and parameter extraction of the connection link (including malformed link handling).
  - Test validation of project ID, record ID, and node ID bounds during URI parsing.
- **Integration Tests**:
  - Add process-level launcher tests verifying the complete single-link flow.
  - Verify that sync fails and renders the exact expected `HINT` messages when host pinning mismatches, project config is missing, or peer allowlist disagrees.
  - Verify exit codes are strictly mapped (exit 0 on success, exit 10 on expected failures).

---

## 6. Non-Goals

- No changes to wire protocol formats (`SDR1` or `SRP1`).
- No continuous or background sync loop; synchronization remains an on-demand, operator-driven one-shot command.
- No network auto-discovery, multi-peer membership, or gossip protocols.
- No database storage, custom indexing, or GUI components.
