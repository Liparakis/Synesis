# First two-person project demo

This is the planned CP-W1/CP-W2 operator path. It uses two isolated local
profiles and makes no physical two-machine claim.

## Current commands and stopping point

The current generated launcher can run:

```powershell
$env:SYNESIS_LINK_PROFILE = '<profile>\link'
synesis identity show
synesis host --expect-peer <node-id>
synesis join <signed-invitation>
```

Those commands authenticate Link and run the fixed demo-work exchange. They do
not create `project.conf`, create a signed decision, invoke CP-R4 sync, or
search a record. The existing inspection launcher only works after code has
already created a record:

```powershell
synesis-project-record inspect <records-dir> <project-id> <record-id>
```

The current human flow therefore stops after onboarding.

## CP-W1 operator steps

```powershell
gradlew.bat :workspace:installDist --dependency-verification=strict
$ws = Resolve-Path .\workspace\build\install\synesis-workspace\bin\synesis-workspace.bat
$profileA = Join-Path $env:TEMP 'synesis-demo-a'
$profileB = Join-Path $env:TEMP 'synesis-demo-b'
```

Operator B creates an identity and gives A the printed node ID:

```powershell
& $ws --profile $profileB identity show
```

Operator A creates the project with B explicitly allowed:

```powershell
& $ws --profile $profileA project create --peer <B_NODE_ID>
```

A then creates one signed decision, retaining the printed record ID and
digest:

```powershell
$hash = (Get-FileHash -Algorithm SHA256 .\README.md).Hash.ToLowerInvariant()
& $ws --profile $profileA decision create `
  --title 'Use signed decisions as shared project truth' `
  --rationale 'This is the smallest trustworthy shared record.' `
  --evidence-kind file --evidence-ref README.md --evidence-sha256 $hash
```

Host/join and sync are deliberately deferred to CP-W2. After CP-W2, B will
join one authenticated session, receive the record once, and both profiles
will use the bounded local search and inspection views.

## Safe output

Normal successful output contains stable labels such as `NODE_ID`,
`PROJECT_ID`, `RECORD_ID`, and `DIGEST`. It does not expose private keys,
absolute paths, endpoints, or stack traces. Evidence references are recorded
as logical references only; this slice does not fetch or validate their files.

## Deferred work

No host/join, networking, sync, retries, reconnect, discovery, membership,
workers, leases, autonomy, federation, Obsidian, background process, or
physical-machine claim is included.
