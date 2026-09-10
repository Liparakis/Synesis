# SYN-055 browser UI rebuild — focused implementation evidence

Date: 2026-09-09

## Scope

The six reference screens were used for presentation only: black/graphite
surfaces, thin rules, compact tables, project-local tabs, selected rows, and
right-side inspectors. The existing authenticated snapshot/SSE and Link
onboarding contract remains the source of truth for content and actions.

The frontend now contains:

- a shared Synesis project shell with Overview, Agents, Coordination, Network,
  and Diagnostics tabs;
- a truthful Local Project Registry view;
- dense agent and Doctor tables with selection inspectors;
- WorkGroup navigation with Claims, Tasks, Capabilities, and Ownership tabs;
- physical-peer, signed-membership, and server-selected-route projections;
- existing invite/join onboarding wired to the control-plane client; and
- explicit empty, UNCONFIGURED, offline, and authority-boundary states.

Unsupported screenshot-only fields such as latency, model settings, charts,
logs, reachability inference, and client-side repair actions were not added.

## Focused verification

All commands were run from web-ui:

| Check | Result |
|---|---|
| npm run typecheck | PASS |
| npm run lint | PASS |
| npm run test | PASS — 2 files, 4 tests |
| npm run build | PASS — Vite production bundle |
| git diff --check | PASS — only Git line-ending warnings |

The forced Windows packaging command
`:cli:runnableInstaller --rerun-tasks --no-daemon --no-configuration-cache`
also passed using the documented command-local loopback workaround. It
produced:

- cli/build/distributions/synesis-windows-x64.exe
  (54,335,434 bytes; SHA-256
  AC15B08BC099C151ACE86ED8BB5AB3CECD746EA2F143E8D4816D876B872D3E72);
- cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip
  (46,523,402 bytes; SHA-256
  1969E79F17CA203C2B71FBB0E53E413512D6B2E85A949A9A2F9FC1266BE82F3).

The source web-ui JAR and the copy inside the platform bundle both hash to
B876CFE78627CE0B1A64812C724275C47B31B67EB7572280313F661789466CAE. The
bundle contains web-ui/index.html and the current hashed JS/CSS assets.

## Environment blockers

The repository-level .\gradlew.bat :web-ui:check --no-daemon attempt did not
reach the web tasks because this host could not establish the Gradle loopback
connection (java.io.IOException: Unable to establish loopback connection).
The installed browser surface was not available to the desktop CUA session, so
the live screenshot/viewport pass and installed-package smoke remain pending.
Installation and PATH were not changed. No backend, packaging configuration, or
remote state was changed.

## Follow-up redesign brief

After the first focused slice, the user supplied an explicit hierarchy
follow-up. The required model is Synesis installation → Projects registry →
selected project → Overview / Agents / Coordination / Network / Diagnostics.
The follow-up removes fake global dashboard/account/integration navigation,
requires `/projects` as the browser home, and requires URL-based project-local
navigation. Inactive, unavailable, and identity-mismatch entries must show
registry truth without invented runtime, repair, or project-switch actions.

This follow-up is a new implementation slice. Its frontend gates, packaged
bundle, and installed/browser screenshots are not claimed by the evidence
above until the hierarchy/routing changes are implemented and verified.

## Continuation

Run the installed-package static/authenticated UI smoke and inspect all six
surfaces at desktop and laptop widths when a browser-capable host is available.

## Follow-up implementation and package verification

The hierarchy follow-up is implemented in the existing web-ui architecture:

- /projects is the normalized global registry route.
- /projects/<id>/<view> is used for the current live project and its five
  project-local tabs.
- The global header no longer exposes fake dashboard, account, profile, or
  integration navigation.
- Inactive, unavailable, identity-mismatch, and non-current-live entries use
  truth-only registry detail pages without start, repair, or project-switch
  actions.
- The bootstrap ordering defect found during browser smoke was fixed so the
  one-time bootstrap hash is consumed before / is normalized to /projects.

Final frontend checks passed from web-ui: typecheck, lint, production build,
and Vitest with 2 files and 5 tests, including route parsing. The latest
Windows package was rebuilt with the process-local JDK loopback workaround:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,436,523 bytes,
  SHA-256 `08659422F003689DBDBC16FF7874FE9A28F04E64F8E36AE274C8D8FB15759F2D`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,524,491 bytes,
  SHA-256 `492AC77824A1DF8CC620853B8445339B1930F246F51168138B53BEAB8824F2A2`

The source UI JAR and the nested platform-bundle UI JAR both hash to
`594C628D342743BA7B02E993316691547F89C4763EBD1976E183A1354A8D5CB3`; the
nested archive contains `web-ui/index.html` and the current route asset.

An authenticated browser smoke against the fresh local distribution covered
the real Projects registry, live Overview, Agents, Coordination, Network,
Diagnostics, and an INACTIVE registry detail. The current in-app browser has
no viewport override, so the requested 1440x900 and narrow-laptop screenshot
comparison remains pending. No backend, PATH, commit, push, tag, or release
publication was performed.

## 2026-09-10 — shell control removal and bundle verification

Removed the top-bar Live connection control and burger navigation control from
the React shell, along with their unused mobile navigation state and styles.
Project-level LIVE, INACTIVE, UNAVAILABLE, and IDENTITY_MISMATCH badges remain
unchanged because they are registry state, not shell controls.

Command-line verification passed: npm run typecheck, npm run lint, npm run
test (2 files, 5 tests), npm run build, and the forced
:cli:runnableInstaller --rerun-tasks --no-daemon --no-configuration-cache
package build with the documented local JDK loopback workaround. The latest
artifacts are:

- cli/build/distributions/synesis-windows-x64.exe — 53,436,800 bytes,
  SHA-256 0E1C8F255792FDAAAD51C85DDD45DE749EFA0F034DA426F7BBBC40CA417D46D7
- cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip —
  46,524,768 bytes,
  SHA-256 EFBE2870F22EACA466887FE8A6420DB0DEA617FA39253C61D668A397AB63DEFB

The source UI JAR and embedded platform-bundle UI JAR both hash to
5F5C6A4FCAC0CF0C363346F444013B01BBD0837D5931851350C2D9C1617A444C. The
embedded archive contains web-ui/index.html plus the current JS and CSS
assets. No backend, PATH, commit, push, tag, or release publication changed.

## 2026-09-09 — global Projects visual refinement and latest bundle

The global /projects composition was refined without adding product semantics:
the page now presents a larger Projects heading and truthful registry summary,
a restrained three-column list with project-first row hierarchy, secondary
identity/path metadata, explicit non-live state notes, and responsive stacked
rows. No dashboard widgets, fabricated metrics, or unsupported actions were
added. The zero-project message remains compact and discovery-based.

Frontend verification passed again: npm run typecheck, npm run lint, npm run
test (2 files, 5 tests), and npm run build. The forced runnable Windows package
rebuild passed with the process-local JDK loopback workaround:

- cli/build/distributions/synesis-windows-x64.exe — 53,437,074 bytes,
  SHA-256 5FAB8EF37B270592292CB01E398A1C5038A75B2AF0BFC0C90129D7B317E73517
- cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip —
  46,525,042 bytes,
  SHA-256 565138B443B134C70C1F21073805EDE465E05AF6880DD436CE2AA425455996FE

The source UI JAR and embedded platform-bundle UI JAR both hash to
C561E1E0025A1B4A5BC1400143199EAAB3934AAAF5421AC43432CCA33563FBCB. The
nested archive contains web-ui/index.html,
web-ui/assets/index-B9iqmNyT.js, and web-ui/assets/index-CkOoOamk.css.

An authenticated smoke against the freshly rebuilt installed distribution
confirmed /projects, Projects, the installation subtitle, the derived
2 known · 1 live summary, the persisted INACTIVE entry with its truthful
reachability note, and the LIVE entry with Open project. The in-app browser
still exposes no 1440x900 or narrow-laptop viewport control, so that visual
comparison remains pending. No backend, PATH, commit, push, tag, or release
publication was performed.

## 2026-09-10 — installation-level Projects rail

The latest refinement is limited to the global Projects home. It adds a
two-column desktop composition with Projects as the primary list and a compact
Installation / Connections rail. The rail shows only derived known/live/
unavailable/identity-mismatch counts, the real API version, the quiet
runtime-connected indicator, and peer/session projections from the current
runtime. It does not imply global peer aggregation and does not add a
browser-owned Disconnect action because no authority-safe adapter exists.
Project-internal pages were not redesigned.

The top bar remains minimal: Synesis plus a quiet runtime connection indicator.
There is no hamburger, profile, dropdown, organization control, duplicate
Projects label, or Live pill.

Frontend verification passed with 2 files and 6 tests. The forced Windows
runnable-installer build passed. Latest artifacts:

- cli/build/distributions/synesis-windows-x64.exe — 53,437,599 bytes,
  SHA-256 4A0BB5321EFCEFF1E5841C6DE3E55BD4312C6004A247798B4E08A39743892FD6
- cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip —
  46,525,567 bytes,
  SHA-256 FB8FD623B746618AFD94127778896D126057C5BC767E922C3FC511129D465AE6

The source and embedded UI JARs both hash to
FB6ADEA4ED9F02D10BBF8D92A4EDF427B36B20189E5B407703F1AA5F7E50FFAD. The
embedded archive contains web-ui/index.html,
web-ui/assets/index-D9ubXMg9.js, and web-ui/assets/index-DeUOMKnC.css.

An authenticated installed-browser smoke confirmed the refined /projects
home, runtime connected indicator, installation counts, current-runtime
connections section, and absence of the hamburger and Live pill. The browser
surface still provides no viewport override, so 2560x1440 and narrower
responsive screenshot acceptance remains pending. No backend, PATH, commit,
push, tag, or release publication changed.

## 2026-09-10 — full-window Overview refinement and top-bar cleanup

The latest Overview pass follows the supplied full-window reference more
closely: the desktop content canvas uses the available width, the project
identity header presents title/status alongside labeled Project ID, Local path,
and project context fields, and the status, Coordination, Network, and
authority-boundary sections remain snapshot-backed. The global top bar no
longer renders the runtime-connected indicator; connection state remains only
where it is scoped to the Installation rail.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,744 bytes,
  SHA-256 `E393C88DD1DBE8DADDDFFDD3F44A6E1AD1A898D103BB15C38428563886494DDF`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,712 bytes,
  SHA-256 `AD99EF9EC42B24B72B17626B6D3985D953A2AD1D17CE3666C4EB8723CEEEB123`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,719 bytes with SHA-256
`D10C1E697A6B7DBB076A74072FF75208D12AFE94293E7AFC71A83D980118318A`.
Its current Vite assets are `web-ui/assets/index-D3GeFFpp.js` and
`web-ui/assets/index-_UrIrKmM.css`. A fresh authenticated installed-browser
smoke confirmed the registry, Installation / Connections rail, and that the
top bar contains no runtime-connected text. The available browser still has
no viewport override, so exact 2560x1440 and narrower screenshot comparison
remains pending. No backend, PATH, commit, push, tag, or release publication
changed.

## 2026-09-10 — Diagnostics console refinement and latest package

Diagnostics now uses a compact status/report band with overall status, a
one-line summary, report ID, localized report timestamp, and Critical / Errors /
Warnings / Info counts. The repetitive Findings report card was removed. The
dense findings table appears immediately below, and selecting a finding opens
the existing right-side inspector with expanded explanation, recommendation,
confidence, component, code, severity, and text-only repair availability.
Healthy state is quiet; findings retain severity emphasis. Safe enum labels such
as `no_action` and `CONFIRMED` are translated only at the UI boundary; the
backend values are unchanged and no repair action was invented.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,023 bytes,
  SHA-256 `F7B215BBF9D78E76D99621D654358EF452E86B792B73157A7E24907E70711FC7`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,991 bytes,
  SHA-256 `F1449E512EA5979493AE29412862D8D97FB2CBE4D2ED34F42D7656B09585F15C`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 80,009 bytes with SHA-256
`7E5588E1E91956E07B43645B1A98192CC62E4FDDC7E93D48D385CE3965AAD340`.
The available browser still has no viewport override, so exact screenshot
comparison remains pending. No backend, PATH, commit, push, tag, or release
publication changed.

## 2026-09-10 — Diagnostics final status-label polish and package

Finalized safe Diagnostics status presentation: real HEALTHY and OK values are
shown as Healthy, while degraded/error overall Doctor states remain emphasized
even if severity counts are zero. The backend enums and repair availability
model remain unchanged.

Final verification passed: typecheck, lint, 6 tests, Vite production build,
and forced Windows runnable-installer packaging. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,075 bytes,
  SHA-256 `DAE16E19CDB123DD84D79D7503A156E015CC03BA9FF5E322DCF4327080C97516`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,527,043 bytes,
  SHA-256 `0A5FDEB1F1EAA6FB86D33BF0998465B7D789547A3C32D0505A04BFAB21DF1B32`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 80,042 bytes with SHA-256
`CC2215C47434421EFB3C59F4645C7492892AB4FACAFFC0A727943E1661671FA2`.

## 2026-09-10 — shared project header styling and latest package

Agents, Coordination, Network, and Diagnostics now reuse the same project
identity header as Overview: title/status on the left, labeled Project ID and
Local path metadata on the right, wide-screen spacing, and no project context
block. Tab-specific content and actions were not changed.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,495 bytes,
  SHA-256 `E95C5DCAC0239CDFA838F29F762C642A79939D5114FFCA47F19C3DD78EFC9ED1`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,463 bytes,
  SHA-256 `C18FE06D8C94EBEB01C491A78167D50191B1E65A8A6349CFACDB9018F51A0259`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,476 bytes with SHA-256
`A2E32A0C01B5DEDD42788CDD7824F43BCABC10CD77DCD7A3C249DC6C7DE4F5F8`.
The available browser still has no viewport override, so exact screenshot
comparison remains pending. No backend, PATH, commit, push, tag, or release
publication changed.

## 2026-09-10 — Overview authority note removal and latest package

Removed the Authority boundary strip from the Overview page as requested. The
Network view retains its own scoped note because it explains the read-only
projection boundary for that view. No other Overview composition or control
behavior changed.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,613 bytes,
  SHA-256 `AFB3056D59D8D95AA57CDC390A362C93A402BCBF6F2F175E0E55D39F169EEA36`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,581 bytes,
  SHA-256 `B4C73D5B0C3E949BB892A05ECECCECF6059C3472E866DE3B3BA10A132119EB53`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,590 bytes with SHA-256
`7B518E79AC61A7E5FEAA99E7F90C919DDF1F2AF7D6A446F23A783ACBBA25B2C5`.
No backend, PATH, commit, push, tag, or release publication changed.

## 2026-09-10 — Projects page refinement

### Connection termination popup and mock scroll slice

The global Connections rail and each project Network peer row now show a red
bin icon that opens a confirmation popup. DIRECT and PEER_TRANSIT routes show
the requested momentary downstream-connectivity warning; RELAY routes do not.
The confirmation action remains disabled because the existing control-plane
contract has no terminate-connection command. The development-only fixture
contains 14 known projects and 8 peers, and live accessibility checks passed
on both popup surfaces.

## 2026-09-10 — Network row detail refinement

Removed the standalone Server-selected routes widget from Network. A peer row
now opens Connection detail with the existing route, path, membership,
session, health, authentication, and usability projections. Live accessibility
checks confirmed the widget is absent and clicking a peer row opens the popup.

The global Projects page now searches the projected project registry by name,
identity, local path, or status. Its right rail contains only Connections;
each peer exposes View network, and each shows a disabled Terminate end
control because no matching backend command is available. The site header and
registry heading are sticky only on the Projects route. Live mock-browser
checks confirmed the filtered state and per-peer controls. Typecheck, lint,
11 tests, production build, and `git diff --check` passed.

## 2026-09-10 — Detail popup refinement

The persistent right-side detail panels were removed from Agents,
Coordination, and Network. Agent selection, Coordination View details, and
Network Membership/Relay detail now open modal dialogs over the page. Network
no longer includes the Authority boundary note. Live mock-browser evidence
confirmed the initial Network layout and all three popup flows. Frontend
typecheck, lint, 9 tests, production build, and `git diff --check` passed.

## 2026-09-10 — Local mock-data development harness

Added a Vite-only `?mock=1` mode with a local snapshot fixture for fast
frontend iteration without reinstalling the Windows package. Live browser
smoke passed at:

- `http://127.0.0.1:5173/projects/proj_test/overview?mock=1`
- `http://127.0.0.1:5173/projects/proj_test/diagnostics?mock=1`

The mock path is gated by `import.meta.env.DEV`; the normal and packaged paths
remain real-control-plane-backed. Typecheck, lint, and 8 tests passed. This
slice did not rebuild the installer because it is specifically a development
workflow improvement.

## 2026-09-10 — Header layout correction package

Restored the original project header treatment: project name and runtime status
on the left, Project ID and Local path in the right rail. The icon-only
back-arrow button remains beside the title and routes to Projects.

Verification passed: typecheck, lint, 8 tests, production build, forced
runnable-installer build, and `git diff --check`.

Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,900 bytes,
  SHA-256 `8634A5509DAB992D95E14EFF57D94F572C032E9C8D0FF2357BFE20DDB886BD8A`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,527,868 bytes,
  SHA-256 `4A7C6818AE196BE57077474AB66B2FEE7A22B62C6A065C6241F4B134CF9007A0`
- `cli/build/install/synesis/lib/web-ui-0.1.0-SNAPSHOT.jar` — 80,875 bytes,
  SHA-256 `9343C0782BB4B54BE2279377F366248AFD8983A83F7EDF196EB91D35E4FAA131`

No backend, PATH, commit, push, tag, or release publication changed.

## 2026-09-10 — Icon-only project-header back control package

The project-header control now shows only the back-arrow icon, with no visible
text. It retains the accessible `Back to projects` label, 44px hit target, and
existing Projects-home route callback. The project name/status metadata rail
remains unchanged.

Verification passed: typecheck, lint, 8 tests, production build, forced
runnable-installer build, and `git diff --check`.

Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,949 bytes,
  SHA-256 `30C11F915C32A8B820A7FAF055A26E4B30C1753B9D26F47FD00A1FD82A8BF6DC`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,527,917 bytes,
  SHA-256 `8268A54F14CE6B134A3D98D1B94294A07DA65DC1B519B89571620A4B3B6D66BF`
- `cli/build/install/synesis/lib/web-ui-0.1.0-SNAPSHOT.jar` — 80,921 bytes,
  SHA-256 `88BC78A7834FD2DDC7971E49DF4F4D62AC3D00795BB3E43C7B2BF9468B575E1B`

No backend, PATH, commit, push, tag, or release publication changed.

## 2026-09-10 — wide Overview scaling and tab strip cleanup

Removed the desktop `1560px` content cap so the Overview uses the available
wide-screen canvas and scales appropriately on the user's 1440p monitor. The
project tab strip now hides its visible scrollbar while retaining horizontal
overflow behavior at narrow widths.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,630 bytes,
  SHA-256 `D8D37762CB0E69BC4CBCC8883DF3AF33AFF14CDB490116927377D5D2334DF940`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,598 bytes,
  SHA-256 `C452A82BBB29BD5AE4D8EE8B1A6E18E5E03A32F9A98530B4E4C4ADDDEB92AAA1`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,607 bytes with SHA-256
`5B0800DA6C564520B472418B5F6DC123DB4E16F641FC6723E9AFDD574D1ACA28`.
The available browser has no viewport override, so exact 2560x1440 and
narrower screenshot comparison remains pending. No backend, PATH, commit,
push, tag, or release publication changed.

## 2026-09-10 — Overview header cleanup and latest package

Removed the Overview header's top-right project context block as requested.
The Local path icon now has explicit inset from the vertical divider. The
full-width Overview composition, snapshot-backed metrics, Coordination and
Network panels, and top bar without the runtime-connected indicator remain in
place.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,610 bytes,
  SHA-256 `045659A4DC877A314CE3581725909ED78CCDE056D91909DE17B9A8C0576842BC`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,578 bytes,
  SHA-256 `0037B20FF5F8790C46FC3C43CB11E0EFCBF0D84FDBA6E7ADA5537542348A0FDC`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,584 bytes with SHA-256
`ABCDE8B9A580C0F2CEF20D1B2E929AF73C8AF286D2604F9524FCD74D5C98B952`.
A fresh authenticated installed-browser smoke confirmed the registry,
Installation / Connections rail, and the top bar without runtime-connected
text. The available browser still has no viewport override, so exact
2560x1440 and narrower screenshot comparison remains pending. No backend,
PATH, commit, push, tag, or release publication changed.

## 2026-09-10 — project Overview refinement and latest bundle

The live project Overview was refined to match the supplied reference
composition: project identity metadata and context, a five-metric Project
status panel, Coordination and Network panels, and an explicit read-only
authority boundary note. Values remain derived from the current snapshot;
providers, runtime participants, WorkGroups, authenticated peers, Doctor, and
network counts are not screenshot fixtures. The global Projects hierarchy,
quiet runtime indicator, removed burger/Live controls, and installation rail
remain intact.

Frontend verification passed: typecheck, lint, 6 tests, and Vite production
build. The forced Windows runnable-installer build passed with the documented
command-local loopback workaround. Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,438,508 bytes,
  SHA-256 `8EA758BB4510C689D473E4A32EE7E69F6964F157A9D0A20E123825DE0BB91426`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,526,476 bytes,
  SHA-256 `457D56A31DC5F74A33C4115E2A7AEF176921249BC7803C357D54D45C74D02F5A`

The packaged `lib/web-ui-0.1.0-SNAPSHOT.jar` is 79,484 bytes with SHA-256
`3F9C26AE8453CB28A6442ADB41F22E64A113D0128C22954FE40449B17434CD18`; it
contains `web-ui/assets/index-CN6lwjew.js` and
`web-ui/assets/index-q6559jJy.css`. A fresh installed narrow-browser session
authenticated against the rebuilt package and confirmed the registry,
Installation / Connections rail, and quiet runtime indicator. Direct
deep-linking cannot be counted as Overview browser smoke because the
one-time bootstrap token is consumed by the entry session; source and route
checks pass. The browser surface still provides no 2560x1440 or narrower
viewport override, so screenshot comparison remains pending. No backend,
PATH, commit, push, tag, or release publication changed.

## 2026-09-10 — Diagnostics modal inspector package

Diagnostics now opens finding details in a modal popup. Previous and next
arrows remain visible and are disabled at the list boundaries; keyboard arrow
navigation, Escape close, backdrop dismissal, and close-button focus are
supported. The full-width findings table remains visible underneath. No Doctor
backend values or repair actions changed.

Verification passed: typecheck, lint, 7 tests, production build, forced
runnable-installer build, and `git diff --check`.

Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,789 bytes,
  SHA-256 `2E27A93A755541C514D1447CB97B70B12BA733CAA4B3261F8246360121CC7B32`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,527,757 bytes,
  SHA-256 `0BD5B65668ADAD283072615D92DC3ACDE71087F7E655FD6BD76E0C2E386B54EA`
- `cli/build/install/synesis/lib/web-ui-0.1.0-SNAPSHOT.jar` — 80,772 bytes,
  SHA-256 `9778A4E50EE3A450B1CB9F15BFA184B815BB1104D2B7E673E762D5994547815B`

The available browser still has no viewport override, so exact 2560x1440 and
narrower screenshot comparison remains pending. No backend, PATH, commit, push,
tag, or release publication changed.

## 2026-09-10 — Project header navigation package

The shared live-project header now places the project name and runtime status
in the right metadata rail with Project ID and Local path. A visible Back to
projects button occupies the former title area and routes to the global
Projects registry. The existing breadcrumb remains unchanged.

Verification passed: typecheck, lint, 8 tests, production build, forced
runnable-installer build, and `git diff --check`.

Latest artifacts:

- `cli/build/distributions/synesis-windows-x64.exe` — 53,439,936 bytes,
  SHA-256 `07AFDC650BD05E7D9D991F6020DE36AEAFDCB117AFE4E18D42B1007F6EEC34AB`
- `cli/build/distributions/synesis-0.1.0-dev.local-windows-x64.zip` —
  46,527,904 bytes,
  SHA-256 `A8A4A7BBF3DE8FEC6C4467B5BE704C1823A34B5A4C7F9E02154B6A20C0561102`
- `cli/build/install/synesis/lib/web-ui-0.1.0-SNAPSHOT.jar` — 80,906 bytes,
  SHA-256 `20B86CF0FBFA7334ABFF91493F8093B61366425B74FE490532A19AEB1F0422CA`

No backend, PATH, commit, push, tag, or release publication changed.
