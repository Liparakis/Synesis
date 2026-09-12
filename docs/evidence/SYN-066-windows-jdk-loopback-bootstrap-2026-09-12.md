# SYN-066 — Windows JDK loopback bootstrap compatibility

Date: 2026-09-12
Status: COMPLETE FOR CURRENT SCOPE

## Problem

After the front-page redesign was rebuilt in the source tree, the installed
Windows launcher still used an older UI payload. Rebuilding the web UI exposed
a second workstation-specific failure: the bundled JDK could not create the
local HTTP server when its Unix-domain socket temporary path was the default
long Windows temporary path. The resulting `synesis ui` failure was a daemon
bootstrap rejection, not a project or registry failure.

## Bounded change

- The generated Windows launcher now creates the short `%PUBLIC%\s`
  directory and passes `-Djdk.net.unixdomain.tmpdir=%SYNESIS_UNIX_SOCKET_DIR%`
  before the bundled JVM starts.
- Direct Java entry points create the same bounded fallback when no explicit
  JDK property is present; an explicit property is preserved.
- Daemon-spawned child runtimes inherit the property from their parent.
- No protocol, lifecycle, registry, browser, Link, network, or project-data
  behavior was changed.

## Verification

1. Focused regression:
   `:cli:test --tests org.synesis.cli.SynesisCliParsingTest.configuresWindowsLoopbackSocketDirectoryWithoutOverwritingExplicitValue`
   passed.
2. Full packaging:
   `:cli:runnableInstaller --rerun-tasks` completed successfully, including
   the current `web-ui:npmCi`, `web-ui:webUiBuild`, resource processing, and
   Windows installer tasks.
3. Supported installer repair completed with `REPAIR_RESULT=SUCCESS`.
4. In a clean shell without `JAVA_TOOL_OPTIONS`, installed
   `synesis coordination serve --project . --port 0 --duration-seconds 3`
   returned `COORDINATION_SERVE_READY`.
5. Installed `synesis ui --no-browser --project .` exited successfully.
6. Installed `synesis ui --project .` returned `SYNESIS_UI_OPENED`.

The successful runtime process used the repaired current payload and included
the launcher property `-Djdk.net.unixdomain.tmpdir=C:\Users\Public\s`.

## Scope and remaining limits

This evidence establishes local Windows/JDK loopback bootstrap compatibility on
the current workstation. It does not claim cross-network connectivity,
universal provider enforcement, or any deferred Synesis Link capability.
No commit or push was made.
