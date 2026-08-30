# Bundle installation

Download the GitHub Actions artifact for your platform, extract it, and run the
native installer from its `bin` directory:

- Windows: `bin/synesis-installer.exe`
- Linux/macOS: `bin/synesis-installer`

Running the installer without arguments opens a menu with Install, Repair, and
Uninstall. It operates on the extracted local bundle and does not require a
manifest or network access.

The installer places Synesis under the OS user-data root:

- Windows: `%LOCALAPPDATA%\Synesis`
- Linux: `$XDG_DATA_HOME/Synesis` or `~/.local/share/Synesis`
- macOS: `~/Library/Application Support/Synesis`

Install adds the application and user PATH entry only; it does not install or
configure any provider. Repair reinstalls the verified application payload and
preserves project workspaces, project `.synesis` directories, and provider
metadata. Uninstall removes application files and PATH state by default. Its
metadata option additionally removes the installer-owned Link identity and
administrative state. Project/workspace data is never scanned or deleted by the
installer because it can live anywhere on disk and is user-owned.

The installer uses versioned payloads and an atomic active pointer. Temporary
siblings are named `Synesis.staging-<random>` and `Synesis.rollback` and are
removed after successful activation or uninstall.

Windows adds `%LOCALAPPDATA%\Synesis\bin` to the user PATH without
administrator privileges, preserving unrelated entries case-insensitively and
without truncation. Linux and macOS add a small managed block to `~/.profile`
and update the installer process PATH; a new terminal may be required.

GitHub Actions publishes six artifacts named `synesis-<platform>` such as
`synesis-linux-x64` and `synesis-windows-x64`. Each contains its own native
installer and runnable application bundle.
