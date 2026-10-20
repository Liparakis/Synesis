# Bundle installation

Download the GitHub Actions artifact for your platform, extract it, and run the
native installer from its `bin` directory:

- Windows: `bin/synesis-installer.exe`
- Linux/macOS: `bin/synesis-installer`

Running the Windows installer without arguments opens a native setup wizard
with Install/Repair and Uninstall choices. The wizard has a destination-folder
textbox, a Browse button, Back/Next/Cancel navigation, a review page, and a
visible progress state while the verified local bundle is activated. It uses
the extracted local bundle and does not require a manifest or network access.
The Windows surface reuses the Synesis product mark and is always rendered in
the dark theme; there is no light-mode or theme-selection option.
Unix installers retain the console menu.

The default installation root is the OS user-data root:

- Windows: `%LOCALAPPDATA%\Synesis`
- Linux: `$XDG_DATA_HOME/Synesis` or `~/.local/share/Synesis`
- macOS: `~/Library/Application Support/Synesis`

Install adds the application and user PATH entry only; it does not install or
configure any provider. On Windows, a successful wizard install or repair also
registers the owned per-user `synesis://` handler. While the installation is
present, a custom installation root is retained in that owned registration for
later repair/uninstall discovery.

Another computer can open a Link session to a Windows machine only if Windows
Firewall has a rule for the installed runtime. The wizard's "Let other
computers connect to this one" checkbox, which is ticked by default (ADR-0103),
or `--allow-link` on `install` and `repair`, adds it; Windows asks for
administrator approval, which is the only elevation the installer ever
requests. The rule names the installed version, so every wizard install, repair
or update that keeps the box ticked adds it again, and it is removed on
uninstall. Declining the prompt leaves the installation complete (ADR-0096). A
command-line install, repair, update or rollback without the option prints
`LINK_FIREWALL=STALE` and the command that fixes the rule when it names another
version, and `synesis link invite` says the same, or that the rule is missing,
and prints the exact `netsh` command as `FIX=` for an administrator terminal;
Synesis never runs it.

Repair reinstalls the verified application payload and preserves project
workspaces, project `.synesis` directories, and provider metadata. Uninstall
removes application files, the owned URI registration, and PATH state by
default. Its optional metadata checkbox additionally removes the installer-
owned Link identity and administrative state. Project/workspace/repository data
and unrecognized files inside a chosen installation root are never deleted by
metadata removal.

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
