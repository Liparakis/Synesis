# First two-person project demo

This is the planned CP-W1/CP-W2/CP-W3 operator path. It uses two isolated local
profiles and makes no physical two-machine claim.

## CP-W1/CP-W2/CP-W3 operator steps

### 1. Preparation

Build the distribution and resolve the batch launcher path:

```powershell
gradlew.bat :workspace:installDist --dependency-verification=strict
$ws = Resolve-Path .\workspace\build\install\synesis-workspace\bin\synesis-workspace.bat
$profileA = Join-Path $env:TEMP 'synesis-demo-a'
$profileB = Join-Path $env:TEMP 'synesis-demo-b'
```

### 2. Node identity bootstrap

Operator B bootstraps their profile identity and shares the printed Node ID with Operator A:

```powershell
& $ws --profile $profileB identity show
```

Output:
```text
NODE_ID=sl1-<B_NODE_ID_HEX>
```

### 3. Project Configuration

Operator A creates the project configuration allowlisting Operator B's Node ID:

```powershell
& $ws --profile $profileA project create --peer sl1-<B_NODE_ID_HEX>
```

Output:
```text
NODE_ID=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
PEER_NODE_ID=sl1-<B_NODE_ID_HEX>
PROJECT_CONFIGURED=true
```

### 4. Create Decision

Operator A creates a signed decision, retaining the printed record ID and digest:

```powershell
$hash = (Get-FileHash -Algorithm SHA256 .\README.md).Hash.ToLowerInvariant()
& $ws --profile $profileA decision create `
  --title 'Use signed decisions as shared project truth' `
  --rationale 'This is the smallest trustworthy shared record.' `
  --evidence-kind file --evidence-ref README.md --evidence-sha256 $hash
```

Output:
```text
NODE_ID=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
RECORD_ID=<RECORD_UUID>
REVISION=1
DIGEST=<RECORD_DIGEST_HEX>
STATUS=PROPOSED
SIGNATURE_VALID=true
```

### 5. Host and Sync

#### Option A: Guided Flow (SYN-004)

Operator A starts the sync host with project and record parameters to generate a convenience bundle:

```powershell
& $ws --profile $profileA sync host --project <PROJECT_UUID> --record <RECORD_UUID>
```

Output:
```text
INVITATION=synesis://join/<signed-invitation-link>?project=<PROJECT_UUID>&record=<RECORD_UUID>&host=sl1-<A_NODE_ID_HEX>
```

Operator B can now onboard and sync the record with a single command by passing the parameterized invitation link and confirming the host fingerprint:

```powershell
& $ws --profile $profileB sync join `
  --expect-host sl1-<A_NODE_ID_HEX> `
  'synesis://join/<signed-invitation-link>?project=<PROJECT_UUID>&record=<RECORD_UUID>&host=sl1-<A_NODE_ID_HEX>'
```

Output:
```text
AUTHENTICATED_REMOTE=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
RECORD_ID=<RECORD_UUID>
SYNC_RESULT=APPLIED
```

#### Option B: Manual Flow (SYN-003)

Alternatively, Operator A starts the sync host without specifying a record, generating the default invitation link:

```powershell
& $ws --profile $profileA sync host
```

Output:
```text
INVITATION=synesis://join/<signed-invitation-link>?project=<PROJECT_UUID>&host=sl1-<A_NODE_ID_HEX>
```

Operator B joins the host and syncs the record by passing the invitation link, project ID, record ID, and expected host Node ID:

```powershell
& $ws --profile $profileB sync join `
  --project <PROJECT_UUID> `
  --record <RECORD_UUID> `
  --expect-host sl1-<A_NODE_ID_HEX> `
  'synesis://join/<signed-invitation-link>?project=<PROJECT_UUID>&host=sl1-<A_NODE_ID_HEX>'
```

Output:
```text
AUTHENTICATED_REMOTE=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
RECORD_ID=<RECORD_UUID>
SYNC_RESULT=APPLIED
```

### 6. Search and Inspect

Operator B can now search and inspect the verified synced record locally:

```powershell
& $ws --profile $profileB decision search --text 'truth'
```

Output:
```text
RESULTS=1
RECORD_ID=<RECORD_UUID>
REVISION=1
DIGEST=<RECORD_DIGEST_HEX>
OWNER_NODE_ID=sl1-<A_NODE_ID_HEX>
STATUS=PROPOSED
TITLE=Use signed decisions as shared project truth
RATIONALE=This is the smallest trustworthy shared record.
```

And inspect details of the specific record:

```powershell
& $ws --profile $profileB decision inspect --record <RECORD_UUID>
```

Output:
```text
PROJECT_ID=<PROJECT_UUID>
RECORD_ID=<RECORD_UUID>
VERSION=1
REVISION=1
DIGEST=<RECORD_DIGEST_HEX>
OWNER=sl1-<A_NODE_ID_HEX>
OWNER_NODE_ID=sl1-<A_NODE_ID_HEX>
AUTHOR=sl1-<A_NODE_ID_HEX>
AUTHOR_NODE_ID=sl1-<A_NODE_ID_HEX>
STATUS=PROPOSED
EVIDENCE_DIGEST=<README_SHA256_HEX>
SIGNATURE_VALID=true
```

## Safe output

Normal successful output contains stable labels such as `NODE_ID`, `PROJECT_ID`, `RECORD_ID`, `DIGEST`, `VERSION`, `REVISION`, `EVIDENCE_DIGEST`, and `SIGNATURE_VALID`. It does not expose private keys, absolute paths, endpoints, or stack traces. Evidence references are recorded as logical references only; this slice does not fetch or validate their files.

## Deferred work

No background sync, retries, reconnect, discovery, membership, workers, leases, autonomy, federation, Obsidian integration, or physical-machine claims are included in this module.
