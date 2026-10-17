# ADR-0096 — The Windows installer can add the firewall rule a Link host needs

## Status

Accepted on 2026-10-14 by the repository owner. Built on 2026-10-14; see "As built".

It amends ADR-0080, which says the wizard requires no elevation. That remains
true of everything except the one optional step decided here.

Claims are marked VERIFIED, USER-STATED or DERIVED.

## Context

Windows Firewall drops unsolicited inbound UDP unless a rule names the
listening program. A Link host listens in the installed runtime's `java.exe`,
no rule names it, and Windows showed no prompt when it first listened. A
second machine therefore could not open a session to a Windows host until a
rule was added by hand. (VERIFIED, 2026-10-14: with no rule a capture on the
joiner showed no reply; with the rule the host answered.)

Changing a firewall rule needs administrator rights. The installation is per
user and holds none. (VERIFIED.)

## Decision

1. The installer can add one inbound rule, named `Synesis Link`, that allows
   UDP to the active payload's `runtime\bin\java.exe` on every profile. A rule
   of that name is the installer's: adding replaces it, uninstalling removes
   it.
2. It is optional and never the default on a first install, because it ends in
   an administrator prompt. The wizard offers it as a checkbox, and
   `install` and `repair` take `--allow-link`. Once the rule exists the wizard
   keeps the box checked, because the rule goes stale (decision 5).
3. To change the rule the installer starts itself again under the `runas` verb
   with the command `link-firewall allow --program <path>` or
   `link-firewall remove`. Only that process is elevated, and it accepts only
   an existing absolute path to a `java.exe`.
4. A declined or failed prompt is a warning. The installation has already
   succeeded; on uninstall the rule is left and reported.
5. The rule names a path that holds the version, so it stops applying after an
   update that does not add it again.

## Alternatives rejected

- A rule for a port. The listener takes an ephemeral port; a fixed one would
  be a change to Link and could be in use.
- A rule for the stable launcher. The launcher starts `java.exe`, and the
  firewall matches the process that listens.
- Elevating the whole installer. Everything else it does is per user, and
  files written elevated would belong to the administrator.
- Leaving it to the prompt Windows shows a program that first listens. None
  appeared.

## Consequences

- An unattended `update` cannot refresh the rule. After one, a repair with the
  option, or the wizard, is needed before the machine can host again.
- The rule allows inbound UDP to a program in a folder the user can write. That
  is the nature of a per-user installation and the same reach the hand-made
  rule had.
- Linux and macOS are unchanged: the installer owns no firewall rule there.

## As built

`bootstrap/internal/install/linkfirewall`, `install.AllowLink`, the
`--allow-link` flag, the `link-firewall` command, and the wizard checkbox. The
installer's tests pass, with the rule commands and the elevation replaced by
recorders. See `docs/agent/CURRENT.md` for what was shown with a real prompt.

Added on 2026-10-17, after a stale rule silently stopped a real host (see
`docs/agent/CURRENT.md`, "Two homes, one project", defect 4): a command-line
`install`, `repair`, `update --execute` or `update --rollback` without
`--allow-link` reads the rule, which needs no elevation, and when it names
another version's `java.exe` prints `LINK_FIREWALL=STALE`, the rule's program,
a `WARNING=` line and `LINK_FIREWALL_FIX=` with the command. It does not
refresh the rule itself: decision 2 keeps the command line from asking for
elevation unless told to, and an unattended update cannot answer a prompt. The
wizard already refreshes it (decision 2). `synesis link invite` on Windows
also reads the rule and prints `LINK_FIREWALL=MISSING` or `STALE` with the
command that fixes it. A stable program path was not added: the runtime lives
only in version folders that rollback keeps, and the firewall matches the
process that listens (see "A rule for the stable launcher").
