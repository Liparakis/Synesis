# SYN-051 standalone loopback compatibility investigation

Date: 2026-09-05. Investigation complete; SYN-051 remains ACTIVE / PARTIAL.

## Result

The smallest failing requested primitive is `Selector.open()`, on both
Temurin 25+36-LTS and Temurin 21.0.11+10-LTS. `Pipe.open()` passes on both.
The additional reduction fails at `SocketChannel.connect(UnixDomainSocketAddress)`
after a successful UNIX server bind in the inherited user temporary directory.
It throws `java.net.SocketException: Invalid argument: connect` at
`sun.nio.ch.UnixDomainSockets.connect0` on both JDKs.

Classification: **temporary-path-dependent Windows AF_UNIX compatibility
boundary, reproduced across both tested JDK versions**. It is not supported
as a JDK25-specific regression, an IPv4-versus-IPv6 failure, or a host-wide
TCP outage. The underlying OS/filesystem/process-environment cause remains
unproven. AF_UNIX behavior outside Java was not tested.

Moving only the diagnostic process's automatic UNIX socket directory to
`C:\t\synesis-loopback-probe` makes all 13 cases of the unchanged original
probe pass on both JDKs, including actual selector wakeup and both HTTP servers.
This is primitive-level evidence only, not managed Synesis runtime acceptance.

## Scope and provenance

- Repository started clean at `2c0262e9f6fe02b026a11a391f2bc72a45b36ddc`.
- Production baseline remains `a7697bbb5de83ced8b61b275056f9204e4467fbc`;
  the intervening committed diff contains documentation only.
- Windows 11 build 26200, amd64; Python reports `Windows-11-10.0.26200-SP0`.
- Exact JDK25 executable:
  `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`.
- JDK21 executable:
  `C:\Users\Liparakis\.jdks\temurin-21.0.11\bin\java.exe`.
- Python executable: `C:\Python313\python.exe`, version 3.13.7.
- `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` were absent
  from the process environment. Baseline JVM argument lists are empty.
- Inherited `TEMP` and `TMP`: `C:\Users\LIPARA~1\AppData\Local\Temp`.
  JDK25 `conf/net.properties` has no active UNIX temporary-directory override.
- Diagnostic Java/Python source and class files live only outside the repo,
  under `C:\t\synesis-loopback-probe`. No Synesis classes or libraries loaded.
- No Synesis MCP, provider installation, managed runtime acceptance,
  `prepareFirst`, START, App Server, new/reused Worker A, Worker B, target
  project access, `.synesis` modification, credential access, production edit,
  JDK patch, global option/network/security change, rebuild, or push occurred.

## Method and exact commands

Startup: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
passed; read the active handoff and deferred register. No capability promoted.

The runner compiled once with JDK25 `javac --release 21` and ran all JDK25
baseline cases before any JDK21 case. It reused identical source and class
bytes in all six suites. Each test has an independent child JVM and a 12-second
outer deadline. No timeout occurred. NIO/classic TCP tests bind, connect,
accept, and verify bytes in both directions. The minimal HttpServer cases
create, start, and stop; they do not claim an HTTP request/response test.
The wakeup case calls wakeup followed by select(2000), requiring return within
one second. Baseline failure occurs during its prerequisite Selector.open(),
so baseline wakeup itself was NOT REACHED.

```powershell
& C:\Python313\python.exe C:\t\synesis-loopback-probe\run_probe.py baseline
& C:\Python313\python.exe C:\t\synesis-loopback-probe\run_probe.py ipv4
& C:\Python313\python.exe C:\t\synesis-loopback-probe\run_unix.py
```

Exact child command arrays, executable hashes, timestamps, per-test operations,
PASS/FAIL, exception types, exact messages, and full stack traces are in the
companion raw transcript. A zero runner exit means recording finished, not
that all primitive tests passed. The original baseline runner is unchanged.
The separate UnixSocketProbe was added only after the baseline comparison and
JDK25 source inspection; it was also compiled once and reused across JDKs.

## Baseline comparison

| Independent baseline case | JDK 25 | JDK 21 |
|---------------------------|--------|--------|
| 01-pipe                   | PASS   | PASS   |
| 02-selector               | FAIL   | FAIL   |
| 03-wakeup                 | FAIL   | FAIL   |
| 04-nio-ipv4               | PASS   | PASS   |
| 05-nio-ipv6               | PASS   | PASS   |
| 06-nio-default            | PASS   | PASS   |
| 07-localhost-resolution   | PASS   | PASS   |
| 07-localhost-address-0    | PASS   | PASS   |
| 07-localhost-address-1    | PASS   | PASS   |
| 08-classic-ipv4           | PASS   | PASS   |
| 09-classic-ipv6           | PASS   | PASS   |
| 10-http-ipv4              | FAIL   | FAIL   |
| 11-http-ipv6              | FAIL   | FAIL   |

Both runtimes: 9 PASS / 4 FAIL. Both resolve localhost in this baseline to
`::1` followed by `127.0.0.1`; each returned address independently passed
NIO TCP transfer. Default loopback is IPv4 in these runs.

Every baseline failure has outer type `java.io.IOException`, exact message
`Unable to establish loopback connection`, and cause
`java.net.SocketException: Invalid argument: connect`. The selector test and
wakeup prerequisite fail at `Selector.open()`; both HTTP cases fail at
`HttpServer.create(explicit-address:0,0)`. Binding HTTP explicitly to IPv4
does not avoid the selector's internal UNIX socket connection.

Representative JDK25 causal stack (full traces for both JDKs in raw evidence):

```text
java.io.IOException: Unable to establish loopback connection
    at sun.nio.ch.PipeImpl$Initializer.init(PipeImpl.java:96)
    at sun.nio.ch.PipeImpl.<init>(PipeImpl.java:186)
    at sun.nio.ch.WEPollSelectorImpl.<init>(WEPollSelectorImpl.java:78)
    at sun.nio.ch.WEPollSelectorProvider.openSelector(WEPollSelectorProvider.java:33)
    at java.nio.channels.Selector.open(Selector.java:295)
Caused by: java.net.SocketException: Invalid argument: connect
    at sun.nio.ch.UnixDomainSockets.connect0(Native Method)
    at sun.nio.ch.UnixDomainSockets.connect(UnixDomainSockets.java:126)
    at sun.nio.ch.UnixDomainSockets.connect(UnixDomainSockets.java:122)
    at sun.nio.ch.SocketChannelImpl.connect(SocketChannelImpl.java:950)
    at java.nio.channels.SocketChannel.open(SocketChannel.java:281)
    at sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run(PipeImpl.java:123)
```

Python IPv4 and IPv6 local TCP bind/connect/accept and bidirectional byte
transfer both PASS. This contradicts a host-wide TCP outage classification.

## Source explanation, inspected after the baseline

Read-only excerpts from the exact installed JDK25 `lib/src.zip` are retained
outside the repo as `jdk25-*.java.txt`. Line references below refer to those
installed sources, whose blank-line layout differs from the rendered web view.

- `SelectorProviderImpl` 63-64 and `PipeImpl` 171-172: public Pipe.open()
  uses the TCP pipe constructor.
- `WEPollSelectorProvider` 32-33 and `WEPollSelectorImpl` 70-88: selector
  construction requests a pipe with AF_UNIX preferred for wakeup.
- `PipeImpl` 203-218: a successful UNIX listener bind is returned. The TCP
  fallback catches listener creation/bind failures, not the later connect.
- `PipeImpl` 118-123: the subsequent `SocketChannel.open(sa)` fails.
- `UnixDomainSocketsUtil.getTempDir`: system/network property
  `jdk.net.unixdomain.tmpdir`, then TEMP, then java.io.tmpdir determines the
  automatic socket directory. This supported property justified the process-local
  path experiment; no JDK or global environment file was edited.

Corresponding primary upstream sources:
[PipeImpl at jdk-25+36](https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/windows/classes/sun/nio/ch/PipeImpl.java)
and [WEPollSelectorImpl at jdk-25+36](https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/windows/classes/sun/nio/ch/WEPollSelectorImpl.java).
Search results about other products were not used to attribute this host's cause.

## Process-local experiments and narrower reduction

`-Djava.net.preferIPv4Stack=true` did not fix Selector.open() or either HTTP
case on either JDK. It additionally makes explicit IPv6 NIO bind fail with
`UnsupportedAddressTypeException` (null message), and classic IPv6 bind fail
with `SocketException: Protocol family unavailable`. Each IPv4-only suite
records 6 PASS / 6 FAIL, with only IPv4 returned for localhost. These induced
IPv6 failures are not baseline defects. No IPv6-preference experiment was
warranted after the AF_UNIX stack and healthy baseline IPv6 TCP evidence.

The separate direct UNIX-channel probe has these results on BOTH JDKs:

| Socket location                                             | Bind | Connect and byte transfer         |
|-------------------------------------------------------------|------|-----------------------------------|
| Automatic inherited TEMP (`LIPARA~1`)                       | PASS | FAIL at connect, Invalid argument |
| Explicit `C:\Users\Liparakis\AppData\Local\Temp\u-<unique>` | PASS | FAIL at connect, Invalid argument |
| Explicit `C:\t\synesis-loopback-probe\u-<unique>`           | PASS | PASS                              |

The expanded username also failing means an 8.3 spelling alone is not a
demonstrated explanation. No claim is made that path length, permissions,
filesystem redirection, packaging, Defender, or a particular Windows bug is
the root cause.

Finally, original LoopbackProbe source/class unchanged:

```text
-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe
```

Both JDKs: **13 PASS / 0 FAIL**. This includes Selector.open(), actual
wakeup/select return, both TCP families, and both minimal HttpServer cases.
The directory override existed only in individual diagnostic JVM arguments.

## Cleanup limits

All child JVMs returned; no probe timeout or managed process launch occurred.
Direct UNIX socket files in the probe directory were successfully removed.
The four direct sockets bound in user TEMP failed deletion after connect failed,
with `java.nio.file.FileSystemException: <path>: The file cannot be accessed
by the system`. The complete cleanup traces are recorded separately after the
primary failure in the raw logs. Their exact created filenames are:

- `C:\Users\LIPARA~1\AppData\Local\Temp\socket_1798055927`
- `C:\Users\Liparakis\AppData\Local\Temp\u-0f9b61bb`
- `C:\Users\LIPARA~1\AppData\Local\Temp\socket_1593380799`
- `C:\Users\Liparakis\AppData\Local\Temp\u-b0369396`

They may remain; no forced deletion, permission change, or system repair was
attempted. Failed selector probes can also leave automatic socket entries:
JDK PipeImpl suppresses deletion IOException in its finally block, so those
unreported names and cleanup success are unknown. This note does not claim
complete temporary-file cleanup. Production/target state was not involved.

## Verification and stop decision

All six suite manifests have identical original source and class SHA-256.

| Diagnostic artifact     | SHA-256                                                            |
|-------------------------|--------------------------------------------------------------------|
| `LoopbackProbe.java`    | `cb6a04c3a9403234c04baee2a2c475ff2ddbc21793e20d08f210fe3f3e9ea4c9` |
| `LoopbackProbe.class`   | `7cd18ed9c202056f01f5dc579d6590dd4ef7018f84b065815e49f089ba4c0ec6` |
| `UnixSocketProbe.java`  | `22217dbe0279b347994487f7bff1856b28b9fe7e2e66b9d8d53842ae142d96fb` |
| `UnixSocketProbe.class` | `ebe439d8a63e9dbc300ed978f1bda3fcab4da2ec8797a1dac9c8788a725e2c47` |
| `run_probe.py`          | `d7b93e06d9e14f72882ae6dafb387d7cf4b7271470b20bff440a5ff545882b33` |
| `run_unix.py`           | `4473ae5d4f114df2a091e4426dbc9ccdf1567ecf9992235a5303d83105fd2f4a` |

Raw evidence: [complete command/result/trace transcript](SYN-051-standalone-loopback-compatibility-2026-09-05-raw.txt).
Diagnostics remain at `C:\t\synesis-loopback-probe` for inspection.
Repository changes are documentation/evidence/checkpoint only. No production
test suite or managed runtime acceptance was run; neither is needed to claim
this standalone result. Deferred register reviewed: no capability activated.

Changing to JDK21 alone is NOT justified as a remedy: its default probe fails
identically, and this investigation does not establish Java21 compatibility
for the existing Java25 production distribution. A separately authorized fresh
Worker-A run on the existing JDK25 with an explicitly bounded UNIX socket
directory is justified for consideration by the standalone evidence, but is
not performed or pre-authorized here. It must preserve the same-live-caller
prepareFirst/START contract and first-material-failure stop rule, and must not
reuse historical lanes. No Worker B or broader acceptance is implied.

Immediate next action: review this compatibility evidence and obtain separate
authorization for one new Worker-A run on JDK25 with a process-local UNIX socket
directory and matching primitive preflight; do not launch it in this slice.

Final documentation checks: `git diff --check` PASS; checkpoint/deferred validator
PASS (9 entries); subsequent resume PASS, recognizing SYN-051 and CP-0683.
Tracked diff and untracked file inventory are confined to docs/agent and
docs/evidence. HEAD unchanged; changes left uncommitted.
