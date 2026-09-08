# SYN-053 installed browser UI evidence — 2026-09-08

## Boundary and baseline

- Backend baseline `2be88cf` was clean and pushed to `origin/master` before
  any UI source was added.
- The UI is local work only. No UI commit was pushed.
- The active task is `SYN-053`; ADR-0069 records the accepted architecture.

## Source and frontend gates

| Check | Result |
|---|---|
| `npm run typecheck` | PASS |
| `npm run lint -- --max-warnings 0` | PASS |
| `npm run test -- --run` | PASS — 2 files, 4 tests |
| `npm run build` | PASS |
| Production output | PASS — 0.52 kB HTML, 19.72 kB CSS, 222.03 kB JS; gzip 0.31/5.01/68.50 kB |
| `:web-ui:check :coordination:compileJava :cli:compileJava` | PASS |
| `StaticResourceHandlerTest` | PASS |
| `:cli:installDist` | PASS; existing configuration-cache warning remains on `nativeMcpLauncher` |

The npm lockfile is committed with the module. `node_modules`, `dist`, and
Gradle output are ignored. The built bundle contains no CDN, Google Fonts,
unpkg, or jsDelivr reference; the only URL constants are React diagnostic and
standard SVG namespace strings.

## Installed HTTP acceptance

The installed distribution was run with the documented process-local Windows
JDK loopback workaround against the disposable initialized Git project
`C:\t\synesis-control-plane-acceptance-20260908`.

| Check | Result |
|---|---|
| `synesis ui --no-browser` ready line | PASS; reports `controlPlaneRoute=/api/v1` and `uiRoute=/` |
| `synesis ui` browser startup path | PASS — packaged run emitted `SYNESIS_UI_OPENED` |
| `GET /` | PASS — 200 HTML, `Cache-Control: no-cache` |
| Static asset | PASS — 200 JavaScript, 222027 bytes |
| Security headers | PASS — self-only CSP and `X-Content-Type-Options: nosniff` |
| Authenticated snapshot | PASS — one real served project |
| `GET /api/v1/events` | PASS — 200, first event `snapshot`, snapshot data follows |
| Node/Vite runtime dependency | PASS — installed command runs from the Java distribution only |

The bootstrap value is one-use: the browser exchanged it for a session, and
the browser URL no longer contained the fragment afterward.

## Browser functional and visual pass

The installed page was opened in the Codex in-app browser and inspected at
the available desktop viewport. The pass covered:

- Dashboard rendered the real disposable project name and live runtime
  summary.
- Projects rendered the real project path and ID; no placeholder project was
  present.
- Agents and WorkGroups rendered explicit empty states.
- Peers & network rendered `UNCONFIGURED`, zero authenticated peers, and
  `DISABLED` relay state with no inferred membership or routes.
- Diagnostics rendered the real Doctor result as `HEALTHY` with one info
  finding and no errors or warnings.
- Create invite returned a real SLO1 invitation and operation ID.
- Join consumed that invitation and returned a real SLA2 answer and operation
  state.
- The single-runtime host-accept attempt reported `host operation is not
  available`; it did not claim a completed connection.
- The dashboard and network layouts were visually inspected for clipping,
  contrast, navigation clarity, responsive card structure, and readable
  status treatment. The narrow available viewport remained scrollable rather
  than clipping the required content.

## Known limits

- The CLI intentionally has no signed membership-authority source, so a real
  installed CLI instance cannot truthfully show configured overlay membership
  or relay connectivity. This is an existing authority boundary, not a UI
  fixture substitution.
- The broad historical workspace/CLI fixture suite still contains unrelated
  failures; the focused UI, packaging, control-plane, Link, relay, Javadoc,
  and deferred gates are the evidence for this slice.
- The final UI changes remain local until the user separately authorizes a
  push.
