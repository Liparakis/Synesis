# Bootstrap installation

The installer scripts require a configured artifact host; this repository does
not claim ownership of `get.synesis.dev`.

```powershell
$env:SYNESIS_BOOTSTRAP_BASE_URL = 'https://your-controlled-host.example/synesis'
irm "$env:SYNESIS_BOOTSTRAP_BASE_URL/install.ps1" | iex
```

```bash
SYNESIS_BOOTSTRAP_BASE_URL=https://your-controlled-host.example/synesis \
  curl -fsSL "$SYNESIS_BOOTSTRAP_BASE_URL/install.sh" | sh
```

The bootstrapper uses user-local versioned state, verifies the detached
manifest signature, checks the artifact size and SHA-256, starts the bundled
Java runtime, and only then activates the version. Uninstall preserves user
projects and their `.synesis` directories.

The CI release candidate contains normalized bootstrap names such as
`synesis-bootstrap-linux-x64` and `synesis-bootstrap-windows-x64.exe` beside
the manifest and checksums. A future tagged GitHub Release may publish the
same platform files individually; no public release URL is claimed here.
