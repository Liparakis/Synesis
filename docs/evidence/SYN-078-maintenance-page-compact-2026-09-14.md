# SYN-078 compact maintenance screen evidence

- Scope: presentation-only maintenance-screen copy, size, spacing, and typography update.
- Native capture: `C:/t/setup-main-larger-details.png`.
- Observed client window: `744x549` overall native setup window.
- Visible copy matches the requested title, subtitle, setup rows, action cards, installed path label, and Continue/Cancel buttons. The version line is not visible.
- The title control is taller than the title font, the Setup includes details use the normal body face in taller rows, and the footer buttons sit slightly lower without losing their alignment with the installed path. The action cards retain their consistent separation and shared right edge; no visible clipping occurred in the settled capture.
- Verification: `bootstrap` `go test ./...` PASS; `go vet .` PASS; Gradle `:cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon` PASS.
- Canonical installer: `cli/build/distributions/synesis-windows-x64.exe`; SHA-256 `CF61DD88EA9B1F3609E54A402F624890B9D4654336C3E20C52B3CC2FC4D97A4C`.
- No install, update, repair, uninstall, metadata removal, PATH change, or registry operation was executed.
