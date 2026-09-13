# ADR-0080: Native Windows Setup Wizard for the Bootstrap Installer

- Status: Accepted; SYN-067 complete for current scope
- Date: 2026-09-13
- Decision owners: Synesis local product boundary

## Context

The Windows bootstrap currently opens a console menu when double-clicked. The
menu does not offer a destination picker, does not show progress while bundle
verification and activation run, and makes the metadata-preserving uninstall
choice look like a shell prompt. The installer already owns the safe pieces:
verified local-bundle activation, a stable per-user installation root, user
PATH ownership, metadata-preserving uninstall, and an ownership-checked HKCU
registration for the `synesis://` scheme.

The existing protocol registration is not enough by itself. A custom install
root must remain discoverable for repair and uninstall, the handler must point
at the stable activation helper, and an unrelated registry owner must not be
overwritten. The wizard must also avoid implying that project, repository,
workspace, provider, or daemon state is part of the application payload.

## Decision

The Windows no-argument bootstrap uses a small native Win32 wizard implemented
in the existing Go bootstrap. It has these bounded pages:

1. choose Install/Repair or Uninstall when an installation is present;
2. choose an installation root with an editable path and native Browse button;
3. review the operation, including the exact root and preservation warning;
4. show a non-blocking progress state while the existing operation runs; and
5. show a bounded success or error result.

The default root remains `%LOCALAPPDATA%\Synesis`. A selected custom root is
stored only in the existing Synesis-owned HKCU protocol registration, so a
later wizard invocation can find it without scanning the filesystem. If no
owned registration exists, the default root is the only automatic candidate.
The wizard does not add a machine-wide registration or require elevation.

Install and repair continue to use the existing verified bundle and atomic
versioned activation. After a successful operation, the wizard registers the
owned `synesis://` handler against the selected root; an existing unowned
registration is never replaced. Uninstall uses the existing ownership-safe
removal path and presents its `--remove-metadata` behavior as an unchecked
checkbox. Unchecked uninstall removes application payload and PATH state but
retains installer-owned identity and administrative metadata; checked uninstall
removes that metadata too. Project, workspace, repository, provider, and
daemon state are not scanned or deleted by the wizard.

The Unix no-argument entry point remains the current console menu. Explicit
CLI subcommands remain available for scripted and acceptance workflows.

### Visual treatment follow-up

The wizard uses the existing Synesis product mark from
`web-ui/public/synesis-logo.png`, embedded into the native bootstrap so the
installer remains self-contained. Its client area, controls, footer, and title
bar are deliberately rendered in the mandatory dark theme requested for the
Windows setup experience; there is no theme preference or light-mode branch.
This changes presentation only. Installation, registration, metadata
ownership, and user-data preservation semantics remain those defined above.

The action page uses owner-drawn selection cards rather than stock group-box
and radio controls, so selected and unavailable actions retain the same dark
visual language as the rest of the window. The embedded PNG is composited with
the Win32 `BLENDFUNCTION` value form required by `AlphaBlend`; this keeps the
product mark visible without adding an external asset or GUI framework.

## Consequences

The double-click Windows experience becomes familiar and responsive without
introducing a GUI runtime, a second installer implementation, or an elevation
boundary. The first launch can still take time to verify and activate a large
bundle, but the wizard stays repaintable and explains that work is in progress.
The browser deep-link symptom is addressed for installations performed through
the wizard because Windows now has an owned `synesis://` entry point that routes
through the stable activation helper.

The wizard intentionally does not promise cancellation of an already-running
atomic operation; Cancel is available before execution, and the progress page
keeps the operation bounded and visible until the existing operation returns.

## Rejected alternatives

- A new Electron/WebView installer would add a runtime and packaging surface for
  controls that native Windows dialogs already provide.
- A machine-wide `Program Files` default would require elevation and would make
  per-user PATH and metadata ownership less clear.
- A filesystem crawl to discover custom installations would risk finding and
  operating on unrelated directories; explicit owned registry metadata is the
  narrow discovery boundary.
