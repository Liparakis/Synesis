# Self-hosted Synesis relay

`synesis-relay` is an optional live forwarding process for organizations that
want a reachable intermediary. It is not a Synesis cloud service, project
authority, model runner, task server, or generic TCP/UDP proxy.

The relay authenticates each connecting node with a signed Ed25519 hello and
pins its own relay identity at clients. The operator supplies an allowlist of
project members. Only bounded `SLF1` frames are accepted; the relay forwards
the opaque inner `SLK1`/`SLE1` bytes and never receives application plaintext
or provider credentials.

## Configuration seam

The current standalone entry point exposes the smallest explicit operator
configuration:

```text
--project <UUID>       project namespace to permit
--node <sl1-...>       allowed node; repeat for every project member
--host <host>          bind host, default 0.0.0.0
--port <port>          bind port, default 0 (ephemeral)
--workers <count>      Netty event-loop workers
--identity-dir <path>  relay identity profile directory
```

The relay identity is created or loaded through the existing Link identity
store. The project/node allowlist is local operator state; it is not a
replacement for the signed project membership snapshot used by overlay nodes.
The relay has one process, bounded live queues, per-source frame-rate limits,
an idle timeout, and no durable mailbox. If a destination is not connected,
the result is `NO_ROUTE`.

## Example lifecycle

Build and stage the standalone distribution with the repository's normal Gradle
application task, then launch the generated `synesis-relay` executable with the
project and node allowlist. Record the printed relay node ID and port in the
node configuration, and pin the relay public key at each client.

Direct peer transit remains usable without a relay. Route preference is direct
peer, authorized peer transit, configured live relay, then unreachable. Relay
traffic does not imply reconnect, offline delivery, TURN semantics, or
metadata anonymity.

## Verification boundary

The relay wire codec, in-memory relay policy, real localhost server/client
path, concurrent forwarding, and the standalone `RelayMain` process are
covered by the relay test suite. The current workstation's inherited
environment still fails before Gradle task or Netty event-loop creation with
`Unable to establish loopback connection` / `Invalid argument: connect`.

The configured checks and localhost tests pass when launched with this
process-local environment:

```powershell
$env:TEMP = 'C:\t'
$env:TMP = 'C:\t'
$env:GRADLE_OPTS = '-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
.\gradlew :link:check :relay:check --no-daemon
```

No global settings are changed by this workaround. The result proves the
bounded Synesis relay path, not physical NAT traversal, TURN compatibility,
offline delivery, reconnect, or metadata anonymity.
