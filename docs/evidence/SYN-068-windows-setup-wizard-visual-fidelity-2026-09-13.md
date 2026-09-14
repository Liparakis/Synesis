# SYN-068 Windows setup wizard visual fidelity evidence — 2026-09-13

## Scope

This presentation-only follow-up brings the native Windows setup wizard closer
to the supplied dark Synesis mockup. The image was used as visual reference
only; its example words, paths, and values were not treated as product
instructions.

The existing SYN-067 installer behavior remains unchanged: verified bundle
activation, repair, metadata-preserving uninstall, owned per-user
`synesis://` registration, PATH ownership, user-data preservation, and the
deferred-show/thread-affinity launch boundary are still the active semantics.

## Visual changes

- Enlarged the reference composition to a 1400×980 client design and added
  bounded work-area scaling for smaller displays.
- Matched the dark near-black client, quiet diagonal upper-right backdrop,
  larger rounded Synesis logo panel, and spaced wordmark.
- Replaced plain capability bullets with native-drawn gear/link/database
  glyphs and separate aligned capability rows.
- Reworked action choices as dark owner-drawn selection cards with radio
  indicators, selected/disabled states, and visible keyboard focus treatment.
- Matched the subdued footer rule, warning triangle/separator, and blue primary
  versus dark secondary action hierarchy.

No installer operation, registry write, PATH update, daemon shutdown, project
scan, repository change, workspace change, or user-data deletion was performed.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Bootstrap tests | PASS | `go test ./...` from `bootstrap` |
| Bootstrap vet | PASS | `go vet .` from `bootstrap` |
| Unix source compatibility | PASS | `GOOS=linux GOARCH=amd64 go test -c` |
| Packaged Windows installer | PASS | `:cli:runnableInstaller --rerun-tasks --no-configuration-cache --no-daemon` |
| Diff hygiene | PASS | `git diff --check` for the SYN-068 files |
| Fresh no-argument launch | PASS | `Synesis Setup`, `VISIBLE=True`, `HUNG=False`, `WM_NULL_DELIVERED=True`, native window 1416×1019 |
| Rendered action-page inspection | PASS | final bounded native `PrintWindow` capture inspected at 1416×1019; cards, logo, icons, footer, warning, and button hierarchy visible |

## Artifact provenance

- Single-file installer: `cli/build/distributions/synesis-windows-x64.exe`
- SHA-256: `2FFB281ABD83D1A9B6DFA451F884277357BBB66FB990AA8AB86AAFBE8B7C222E`

## Boundaries

This evidence proves a fresh local Windows launch and rendered action-page
inspection. It does not claim that Install, Repair, or Uninstall was executed,
that the installed user state was modified, or that the pixels are identical
to the supplied mockup at every Windows DPI or display size.

## 2026-09-14 compact window and native icon continuation

The follow-up reduced the normal desktop window to 912x666 including the title
bar while retaining work-area scaling for smaller displays. The linked resource
object `bootstrap/synesis-icon_windows_amd64.syso` supplies the same Synesis mark
to the large and small class icons. The live window reported a nonzero class
icon handle, and resource extraction from the native installer produced
`#1_0000.ico`; the self-extracting `.exe` is copied from that native installer
before its verified bundle is appended, so the Explorer icon is retained.

The current artifact is
`cli/build/distributions/synesis-windows-x64.exe` with SHA-256
`5B86349F9F88B358FE1D3023D0C686DEC4AF6DD54B554628437CD5C1B606D91C`.

Focused bootstrap tests and vet, Linux source compilation, the forced runnable
installer build, a visible/responsive no-argument launch
(`VISIBLE=True`, `HUNG=False`, `WM_NULL_DELIVERED=True`), and the compact native
capture passed. No Install, Repair, or Uninstall action was executed.
