# SYN-055 final browser artistic-direction pass

The 2026-09-10 pasted brief authorizes visual polish across Projects, Overview,
Agents, Coordination, Network, and Diagnostics. It supersedes earlier requests
for an uncapped desktop width and no top-bar runtime indicator.

## Result

- Neutral grayscale text/surfaces, quieter healthy and neutral states, and
  stronger warning/failure contrast. Zero diagnostic severity counts are neutral.
- 15px body type, 13px principal metadata/table labels, a 34px project title,
  and consistent controls, focus treatment, selected rows, and 140ms transitions.
- 1480px content cap for Projects, Overview, Agents, and Diagnostics;
  1760px for Coordination and Network. Laptop layouts retain bounded table
  scrolling, wrapped technical values, and responsive columns.
- One visible Projects back link, one project identity block, attached tabs,
  and understated runtime reachability text with a 5px dot. No extra navigation.
- Compact Overview metrics, selected WorkGroup/row edge markers, diagnostic
  display labels, full-value native titles for truncated identity metadata.
- Corrected accessible list markup, the Network actions header, and nested
  interactive controls in the Connections rail. Existing actions are preserved.
- Development preview mode remains stable through navigation; its fixture
  import still has a compile-time DEV guard and is absent from production.

Production changes are confined to `web-ui/src/app/App.tsx` and
`web-ui/src/styles.css`. No backend, endpoint, DTO, authority, route destination,
network protocol, or fixture-data file changed. Existing modal inspectors,
onboarding actions, and disabled termination behavior are preserved.

## Verification

Command-local environment (not shipped or installed globally):

```powershell
$env:TEMP = 'C:\t'
$env:TMP = 'C:\t'
$env:GRADLE_OPTS = '-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
.\gradlew.bat :web-ui:check :cli:installDist --no-daemon --max-workers=1 --no-configuration-cache --console=plain
```

PASS: frontend typecheck, lint, 12 tests in two files, production build,
resource JAR, and local CLI distribution. This is a focused frontend/package
gate, not a full backend suite or a newly installed Windows installer.

The actual distribution ran `synesis.bat ui --project
C:\Users\Liparakis\Desktop\Test --no-browser --port 18765
--duration-seconds 3600` with the same temporary directory workaround and
`JAVA_OPTS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
Playwright authenticated through the real bootstrap/session API and exercised
the packaged same-origin UI. Final packaged verification uses no mocked API or
asset interception. Bootstrap values are excluded from this evidence.

| Surface | 2560x1440 content | 1920x1080 content | 1280x800 content |
|---|---:|---:|---:|
| Projects | 1480px | 1480px | 1184px |
| Overview | 1480px | 1480px | 1184px |
| Agents | 1480px | 1480px | 1184px |
| Coordination | 1760px | 1760px | 1184px |
| Network | 1760px | 1760px | 1184px |
| Diagnostics | 1480px | 1480px | 1184px |

Browser zoom/device scale: 100%/1. All 18 screen/viewport combinations have
document width equal to viewport width. The same 18 combinations were also
checked using the existing development-only populated fixture. Real runtime
screens show actual empty agents/WorkGroups/peers, healthy Doctor state, and a
remembered inactive project; they do not pretend the fixture is live data.

All six screens were visually reviewed together at both requested desktop
sizes and the laptop width. Checks include navigation, project back control,
search with no matches, inactive detail, inspector Enter/Escape handling, and
existing unit-tested modal/termination interactions. No page JavaScript errors.
Automated axe checks at 1920x1080 report zero violations on all six real-data
screens and all six populated-fixture screens. This is an automated scan, not
a complete accessibility certification. Reduced-motion CSS is retained.

Local generated evidence (under the ignored build directory):

- `build/ui-art-direction/qa.cjs`: browser QA inventory and runner.
- `build/ui-art-direction/build.log`: complete final Gradle gate output.
- `build/ui-art-direction/packaged-checks.json`: actual runtime results.
- `build/ui-art-direction/fixture-checks.json`: development stress results.
- `build/ui-art-direction/asset-checks.json`: SHA-256 comparison of packaged
  index/JS/CSS against the current production output; all match, no mock chunk.
- `build/ui-art-direction/packaged-{2560,1920,1280}-{projects,overview,agents,coordination,network,diagnostics}.png`.
- `build/ui-art-direction/fixture-*`: explicitly development-only screenshots.
- `build/ui-art-direction/packaged-1920-all.png` and `packaged-2560-all.png`:
  six-screen comparison sheets.

## Resolved verification issues

1. The default Java environment failed loopback startup. The previously
   documented command-local temporary-directory workaround passed.
2. Gradle's dependency refresh initially hit a Windows lock on Vite's running
   esbuild executable. Stopping the owned preview server released it; the
   subsequent locked dependency installation and build passed.
3. An initial bootstrap expired during setup. A fresh owned runtime and prompt
   authentication passed; no authentication bypass was introduced.
4. Accessibility scans identified an empty action header, listitem on article,
   and nested connection-row buttons. All were corrected and rescanned.

No commit, push, remote publication, global installation replacement, or new
deferred capability is part of this pass. The requested visual slice is
complete; the single SYN-055 task remains the administrative review record.
