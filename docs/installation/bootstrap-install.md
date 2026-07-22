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

The bootstrapper installs one flat stable bundle under the OS user-data root:

- Windows: `%LOCALAPPDATA%\Synesis`
- Linux: `$XDG_DATA_HOME/Synesis` or `~/.local/share/Synesis`
- macOS: `~/Library/Application Support/Synesis`

The root contains `bin/`, `runtime/`, application files, and `VERSION`; its
launcher is `bin/synesis.cmd` on Windows or `bin/synesis` on Unix. A signed
manifest, detached signature, artifact size/SHA-256, safe extraction, staged
launcher validation, atomic activation, and one temporary rollback root are
required. Successful updates never retain old application copies. Uninstall
removes only the stable Synesis root and owned PATH state, preserving user
projects and their `.synesis` directories.

The next install or update migrates the old `current` plus `versions/<version>`
layout only after the new flat bundle validates. Temporary siblings are named
`Synesis.staging-<random>` and `Synesis.rollback` and are removed after
successful activation or uninstall.

Windows adds `%LOCALAPPDATA%\Synesis\bin` to the user PATH without
administrator privileges, preserving unrelated entries case-insensitively and
without truncation. Linux and macOS add a small managed block to `~/.profile`
and update the installer process PATH; a new terminal may be required.

The CI release candidate contains normalized bootstrap names such as
`synesis-bootstrap-linux-x64` and `synesis-bootstrap-windows-x64.exe` beside
the manifest and checksums. A future tagged GitHub Release may publish the
same platform files individually; no public release URL is claimed here.
