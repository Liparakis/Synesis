# Synesis Link v1 Contract

- Contract revision: 1
- Contract status: ACTIVE
- Implementation permitted: YES

You are the principal protocol and networking engineer responsible for designing and materializing Synesis Link v1 as a production-quality standalone Java project.

Work autonomously in small, verified vertical slices.

Do not stop after planning, scaffolding, architecture documents, or partial implementation. Continue until the repository satisfies the Synesis Link v1 completion criteria or an architecture-changing blocker genuinely requires human input.

Your conversational context is temporary and untrusted.

The repository-local persistence system is the durable source of truth.

# Mandatory startup

Before planning, editing code, researching dependencies, or running broad commands:

1. Read the root `AGENTS.md`.
2. Run:

   `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

3. Read:

   - `docs/agent/CONTRACT.md`
   - `docs/agent/GOAL.md`
   - `docs/agent/STATE.md`
   - `docs/agent/TASKS.md`
   - `docs/agent/CURRENT.md`
   - `docs/agent/DECISIONS.md`
   - `docs/agent/FAILED_ATTEMPTS.md`
   - `docs/agent/TEST_MATRIX.md`
   - `docs/agent/NEXT_SESSION.md`

4. Inspect Git status and the actual repository.
5. Reconcile any stale documentation with repository reality.
6. Continue the exact active task.

Do not trust conversational memory over repository evidence.

# Contract installation

Before product implementation, replace the placeholder content of:

`docs/agent/CONTRACT.md`

with the complete text of this prompt.

Do not summarize it.

Preserve all requirements.

Set:

- Contract revision: 1
- Contract status: ACTIVE
- Implementation permitted: YES

Then reconcile:

- `docs/agent/GOAL.md`
- `docs/agent/TASKS.md`
- `docs/agent/CURRENT.md`
- `docs/agent/STATE.md`
- `docs/agent/TEST_MATRIX.md`
- `docs/agent/NEXT_SESSION.md`

Run the checkpoint validator before implementation.

# Architecture process

Use the installed `constraint-driven-architecture` skill:

- before initial implementation;
- before selecting the QUIC implementation;
- whenever changing module boundaries;
- whenever changing protocol guarantees;
- whenever introducing a major dependency;
- whenever changing identity, authentication, wire format, compatibility, persistence, security, or deployment assumptions;
- whenever implementation begins diverging materially from the agreed architecture.

Record architecture-changing decisions in ADRs.

Do not create or implement the wider Synesis platform.

# Product

The only product is:

# Synesis Link

Synesis Link is a standalone, local-first, direct peer-to-peer networking and authenticated session-liveness library built on QUIC.

The repository itself is `synesis-link`.

Do not create:

- a parent Synesis repository;
- sibling Synesis projects;
- placeholder Synesis modules;
- a project-coordination system;
- agent orchestration;
- project synchronization;
- broader application functionality.

# Mission

Build Synesis Link v1 so that two computers can:

1. create and securely manage long-term Synesis node identities;
2. gather direct-connectivity candidates;
3. export and exchange signed candidate descriptors;
4. establish a direct QUIC connection without manual port forwarding when the network permits it;
5. cryptographically bind the QUIC connection to the expected Synesis node identity;
6. negotiate a compatible Synesis Link protocol version;
7. expose a stable authenticated `PeerSession`;
8. maintain an isolated control path;
9. detect graceful peer shutdown promptly;
10. infer ungraceful connection loss through bounded application-level liveness timeouts;
11. distinguish healthy, uncertain, expired, failed, and gracefully closed sessions;
12. tolerate ordinary packet loss, NAT rebinding, address changes, and QUIC path migration without falsely expiring healthy sessions;
13. reconnect through a new authenticated session;
14. prevent old-session messages from being confused with a new session;
15. expose precise lifecycle events and cancellation signals;
16. open bounded typed QUIC streams for future protocols without implementing those protocols;
17. operate without an authoritative server;
18. operate without a mandatory Synesis-hosted rendezvous service;
19. report honestly when direct peer-to-peer connectivity is impossible under the current network conditions.

Synesis Link must never claim every pair of computers can connect directly.

When both peers are behind restrictive NATs, carrier-grade NAT, or firewalls that prohibit direct paths, direct communication may be impossible without rendezvous or relay infrastructure.

V1 must report that limitation diagnostically rather than hiding it.

# Product boundary

Synesis Link owns:

- node cryptographic identities;
- identity fingerprints;
- node IDs;
- signing and verification;
- identity-storage abstraction;
- connection candidates;
- candidate gathering;
- candidate normalization;
- candidate descriptor creation;
- canonical descriptor encoding;
- descriptor signing and verification;
- candidate ranking;
- candidate-pair generation;
- bounded direct connection attempts;
- QUIC transport integration;
- transport-to-node identity binding;
- protocol negotiation;
- authenticated session establishment;
- session IDs;
- session epochs;
- control-stream framing;
- heartbeats;
- liveness state;
- graceful close;
- abrupt-loss inference;
- reconnect handling;
- path-change reporting;
- typed application-stream establishment;
- connection and stream resource limits;
- transport observability;
- deterministic testing infrastructure;
- a minimal two-peer reference CLI.

Synesis Link does not own:

- projects;
- project membership;
- user accounts;
- organizations;
- tasks;
- task assignment;
- capabilities;
- ownership;
- distributed leases;
- fencing tokens;
- project events;
- project synchronization;
- shared memory;
- canonical project state;
- Git;
- worktrees;
- patches;
- patch review;
- AI agents;
- agent supervision;
- workflow orchestration;
- distributed consensus;
- distributed locks;
- remote execution;
- remote shell access;
- a central coordination server;
- a relay network;
- a DHT;
- a GUI.

Future software may consume Synesis Link APIs.

Synesis Link must not know what that software is.

Do not let higher-level project or agent concepts leak into:

- package names;
- public APIs;
- protocol messages;
- error types;
- tests;
- documentation.

# Core principles

## Direct first

Prefer direct peer-to-peer paths.

Candidate sources may include:

- same-LAN addresses;
- globally routable IPv6;
- automatically mapped IPv4;
- optional server-reflexive addresses;
- manually supplied direct addresses.

No external server may become mandatory for the core library.

## Cryptographic identity over network identity

An IP address is a route, not an identity.

Never authenticate a peer using only:

- IP address;
- hostname;
- TLS success;
- arbitrary certificate possession;
- QUIC connection ID;
- candidate descriptor location.

A Synesis node is identified by control of its long-term identity key.

## QUIC is transport

QUIC provides:

- encrypted transport;
- multiplexed streams;
- reliable ordered delivery within streams;
- congestion control;
- loss recovery;
- connection IDs;
- path validation;
- connection migration.

Synesis Link must still define:

- node identity binding;
- protocol negotiation;
- authenticated session establishment;
- session IDs and epochs;
- control messages;
- application stream types;
- liveness semantics;
- resource limits;
- structured failure behavior.

## No false instant-disconnection claim

Graceful shutdown may be detected promptly when the peer sends an authenticated goodbye or QUIC close signal.

Crashes, power loss, machine sleep, cable removal, blackholed traffic, and network partitions can only be inferred after bounded timeouts.

Never claim instant crash detection.

Document the detection bounds.

## Bounded everything

Bound:

- descriptor size;
- frame size;
- address length;
- candidate count;
- candidate attempts;
- concurrent connection attempts;
- discovery time;
- handshake time;
- control messages;
- heartbeat queues;
- open streams;
- pending stream requests;
- pending callbacks;
- executor queues;
- retries;
- shutdown time;
- native resources;
- memory retained per peer.

No unbounded queues.

## Deterministic protocol logic

Make these controllable where doing so improves testing:

- time;
- scheduler;
- randomness;
- IDs;
- candidate ranking;
- connection attempt ordering;
- retry behavior;
- fault behavior.

Do not hide protocol logic inside untestable transport callbacks.

## Proportional architecture

Start as one Gradle project.

Use package boundaries before Gradle subprojects.

Do not split merely because these names sound clean:

- api;
- core;
- quic;
- testkit;
- demo.

A future module split requires evidence and an ADR.

Acceptable evidence includes:

- publishing a transport-independent API separately;
- isolating native QUIC dependencies;
- enforcing dependency boundaries;
- separately publishing a reusable testkit;
- materially improving compatibility enforcement.

# Technology baseline

Use:

- Java 25;
- Gradle with Kotlin DSL;
- Gradle Wrapper;
- JUnit 5 or the appropriate current JUnit platform;
- a maintained QUIC implementation;
- established cryptographic libraries;
- strict compiler validation;
- strict Javadoc validation;
- static analysis;
- reproducible automated tests;
- dependency locking;
- dependency verification.

Research Java-compatible QUIC implementations using current primary documentation.

Evaluate:

- current maintenance;
- Java 25 compatibility;
- operating-system support;
- native library requirements;
- TLS implementation;
- QUIC version support;
- path migration;
- NAT rebinding behavior;
- stream support;
- control-path isolation;
- lifecycle control;
- shutdown;
- testing support;
- licensing;
- release cadence;
- security maintenance;
- packaging implications;
- replacement cost.

Record the result in an ADR.

Hide implementation-specific QUIC types behind an internal adapter.

Do not recreate:

- QUIC;
- TLS;
- congestion control;
- packet encryption;
- loss recovery;
- cryptographic primitives.

Never use Java object serialization for network data.

# Repository shape

Begin as one Gradle project.

Use a structure similar to:

synesis-link/
â”œâ”€â”€ AGENTS.md
â”œâ”€â”€ README.md
â”œâ”€â”€ LICENSE
â”œâ”€â”€ SECURITY.md
â”œâ”€â”€ CONTRIBUTING.md
â”œâ”€â”€ build.gradle.kts
â”œâ”€â”€ settings.gradle.kts
â”œâ”€â”€ gradlew
â”œâ”€â”€ gradlew.bat
â”œâ”€â”€ gradle/
â”‚   â”œâ”€â”€ wrapper/
â”‚   â”œâ”€â”€ libs.versions.toml
â”‚   â””â”€â”€ verification-metadata.xml
â”œâ”€â”€ docs/
â”‚   â”œâ”€â”€ agent/
â”‚   â”œâ”€â”€ adr/
â”‚   â”œâ”€â”€ protocol/
â”‚   â”‚   â”œâ”€â”€ SYNESIS_LINK_V1.md
â”‚   â”‚   â”œâ”€â”€ WIRE_FORMAT.md
â”‚   â”‚   â”œâ”€â”€ STATE_MACHINES.md
â”‚   â”‚   â””â”€â”€ TEST_VECTORS.md
â”‚   â”œâ”€â”€ security/
â”‚   â”‚   â””â”€â”€ THREAT_MODEL.md
â”‚   â””â”€â”€ operations/
â”‚       â””â”€â”€ TWO_MACHINE_TESTING.md
â”œâ”€â”€ src/
â”‚   â”œâ”€â”€ main/
â”‚   â”‚   â”œâ”€â”€ java/
â”‚   â”‚   â”‚   â””â”€â”€ <base-package>/
â”‚   â”‚   â”‚       â”œâ”€â”€ api/
â”‚   â”‚   â”‚       â”œâ”€â”€ identity/
â”‚   â”‚   â”‚       â”œâ”€â”€ candidate/
â”‚   â”‚   â”‚       â”œâ”€â”€ protocol/
â”‚   â”‚   â”‚       â”œâ”€â”€ session/
â”‚   â”‚   â”‚       â”œâ”€â”€ liveness/
â”‚   â”‚   â”‚       â”œâ”€â”€ transport/
â”‚   â”‚   â”‚       â”œâ”€â”€ observability/
â”‚   â”‚   â”‚       â””â”€â”€ internal/
â”‚   â”‚   â””â”€â”€ resources/
â”‚   â””â”€â”€ test/
â”‚       â”œâ”€â”€ java/
â”‚       â””â”€â”€ resources/
â””â”€â”€ examples/
    â””â”€â”€ cli/

Adapt this only when evidence supports a cleaner structure.

Every Java package must contain `package-info.java`.

# Persistent working loop

At the beginning of every execution:

1. run `scripts/agent-resume.ps1`;
2. read the required durable-memory files;
3. inspect Git status;
4. inspect relevant implementation files;
5. verify documentation against reality;
6. correct drift;
7. continue the exact active task.

For every active task:

1. select the highest-priority unblocked task;
2. keep exactly one primary task ACTIVE;
3. populate `CURRENT.md`;
4. restate acceptance criteria;
5. inspect relevant code and primary documentation;
6. implement the smallest complete vertical slice;
7. write or update Javadocs in the same change;
8. add tests in the same change;
9. run the narrowest relevant test;
10. investigate failures;
11. run the affected broader suite;
12. run formatting;
13. run static analysis;
14. run strict Javadocs;
15. run the full build at a coherent boundary;
16. update protocol and security documentation;
17. update durable memory;
18. run `scripts/agent-checkpoint.ps1`;
19. continue to the next task.

After every meaningful implementation or debugging step, update `CURRENT.md`.

After every completed slice, update:

- STATE.md;
- TASKS.md;
- CURRENT.md;
- TEST_MATRIX.md;
- SESSION_LOG.md;
- DECISIONS.md when applicable;
- FAILED_ATTEMPTS.md when applicable;
- NEXT_SESSION.md.

Before context exhaustion:

1. stop new feature work;
2. finish the current coherent change;
3. run trustworthy verification;
4. update durable memory;
5. write the exact next command;
6. create a checkpoint.

The repository must always be resumable by a completely fresh agent.

# Task evidence rules

A capability may be described as:

- IMPLEMENTED only when production code exists;
- COMPILED only after successful compilation;
- UNIT_TESTED only after relevant unit tests pass;
- INTEGRATION_TESTED only after a real integration path passes;
- MANUALLY_TESTED only when steps and results are recorded;
- TWO_MACHINE_VERIFIED only after execution on two physical computers.

Do not turn assumptions into facts.

A task may be DONE only when:

- implementation exists;
- required Javadocs exist;
- acceptance tests pass;
- affected broader tests pass;
- documentation matches reality;
- verification commands and results are recorded;
- no acceptance criterion remains unverified.

# Javadoc requirements

Documentation quality is a release requirement.

Write meaningful Javadocs for every:

- public class;
- public interface;
- public enum;
- public record;
- public annotation;
- public constructor;
- public method;
- public field;
- public constant;
- public enum constant;
- public record component;
- protected type;
- protected constructor;
- protected method;
- protected field;
- package through `package-info.java`.

Package-private and private elements must also be documented when they contain non-obvious:

- protocol semantics;
- security behavior;
- lifecycle ownership;
- concurrency behavior;
- units;
- resource ownership;
- state-machine invariants;
- wire-format behavior;
- algorithmic reasoning;
- workaround rationale.

Do not add useless comments that merely restate names.

Bad:

```java
/** Gets the value. */
String getValue();

Good documentation explains meaning and contract.

Every public method Javadoc must explain, where applicable:

purpose;
non-purpose;
preconditions;
postconditions;
thread safety;
blocking behavior;
lifecycle restrictions;
ownership;
required cleanup;
cancellation;
timeouts;
nullability;
ordering;
idempotency;
security implications;
protocol effects;
emitted events;
state transitions;
failure behavior;
retry safety.

Use complete tags:

@param;
@return;
@throws;
@since 1.0;
@see where genuinely useful.

Every public or protected field and constant must document:

semantic meaning;
valid range;
units;
default behavior;
whether it is a protocol constant or implementation default;
compatibility implications;
security implications where relevant.

Do not leave unexplained numeric constants.

For every record:

document the record;
document every component;
state validation rules;
state normalization behavior;
state equality semantics;
state whether values are safe to log;
state whether values contain secrets.

Every public asynchronous or concurrent type must state whether it is:

immutable and thread-safe;
thread-safe;
conditionally thread-safe;
thread-confined;
not thread-safe.

Document:

callback execution context;
callback concurrency;
callback ordering;
inline completion;
listener races;
shutdown behavior;
cancellation propagation.

Every resource-owning type must document:

what it owns;
who closes it;
whether close is idempotent;
active-operation behavior during close;
close bounds;
post-close behavior;
native-resource involvement.

Use AutoCloseable where appropriate.

Security-sensitive APIs must document:

trust assumptions;
authenticated and unauthenticated states;
replay implications;
secret handling;
safe logging;
attacker-controlled input;
verification requirements;
failure behavior.

Configure strict documentation verification.

At minimum:

generate Javadocs as part of required verification;
enable strict doclint;
fail on meaningful warnings;
fail on missing public and protected documentation;
fail on invalid tags;
fail on broken links;
verify every package has package-info.java;
verify public APIs do not expose undocumented implementation types.

Record tooling limitations honestly.

Protocol identifier

Use ALPN:

synesis-link/1

Implement explicit protocol-version negotiation.

Define:

supported versions;
preferred version;
minimum compatible version;
incompatible-version behavior;
downgrade protection;
extension negotiation;
unknown-extension handling;
mandatory and optional features.

Incompatible peers must fail deterministically with a structured protocol error.

Do not silently fall back.

Disable QUIC 0-RTT in v1 unless replay safety is formally designed, documented, and tested.

No authority-sensitive or session-establishing message may be accepted through replayable early data.

Node identity

Each node has a long-term asymmetric identity key.

The public API must support:

identity generation;
identity loading;
externally supplied identity operations;
public identity export;
stable node-ID derivation;
signing;
verification;
temporary-secret cleanup where practical;
identifying external secure-storage implementations.

Use a maintained modern signature algorithm.

Do not invent cryptography.

Create an ADR covering:

algorithm;
provider;
key format;
public-key encoding;
private-key encoding;
node-ID derivation;
fingerprint display;
signature encoding;
operating-system support;
replacement strategy.
Node ID

Derive the node ID deterministically from canonical public-key material.

Specify:

hash algorithm;
encoding;
text representation;
case rules;
parsing;
validation;
collision assumptions;
fingerprint format.
Identity storage

Provide a narrow storage abstraction.

A default file implementation is acceptable only when:

file permissions are restricted where supported;
writes are atomic;
corruption is detected;
unsafe overwrite is prevented;
private keys never appear in logs;
plaintext limitations are documented.

Leave clean integration points for:

operating-system key stores;
hardware-backed keys;
external signing systems.

Do not implement the…1779 tokens truncated… a new session ID.

Define:

who creates epochs;
whether epochs persist;
restart behavior;
duplicate behavior;
wraparound;
ordering;
security role;
compatibility role.

An expired session must never return to LIVE.

Old queued work must not silently migrate to a new session.

The caller must receive a clear new-session event and decide how to revalidate higher-level state.

Synesis Link does not perform higher-level revalidation.

Control stream

Reserve a small isolated control stream for:

hello;
hello acknowledgement;
identity proof;
identity acknowledgement;
limit negotiation;
heartbeat;
heartbeat acknowledgement;
graceful goodbye;
warnings;
protocol errors;
controlled shutdown.

Large payloads must never use it.

The control path must have:

strict message-size limits;
framing;
parser limits;
unknown-message behavior;
malformed-message behavior;
version behavior;
backpressure;
bounded queues;
deterministic shutdown.

Do not assume QUIC multiplexing automatically gives control priority.

Test control progress during large data transfers.

Wire framing

Define a versioned safe frame format containing only required fields such as:

message type;
message version;
payload length;
payload;
flags.

Specify:

byte order;
maximum length;
zero length;
unknown types;
unknown versions;
truncation;
overflow;
duplicates;
replay-sensitive messages;
extension handling.

Untrusted parsing must:

validate before allocation;
reject overflow;
reject oversized data;
avoid recursion bombs;
avoid unbounded collections;
return structured errors;
avoid process crashes.
Typed application streams

Allow callers to open bounded identified streams.

A stream opening must declare:

protocol or stream identifier;
version;
optional correlation ID;
declared size where known;
negotiated limits;
bounded metadata.

Generic categories may include:

SYNC;
REQUEST;
ARTIFACT;
DIAGNOSTIC;
CUSTOM.

These categories must not implement higher-level semantics.

Every stream needs:

explicit acceptance or rejection;
concurrent limit;
metadata limit;
lifecycle ownership;
shutdown behavior;
cancellation behavior;
structured rejection reason.

Do not add task, project, patch, file, agent, or ownership APIs.

Liveness state machine

Implement:

CONNECTING;
LIVE;
SUSPECT;
EXPIRED;
CLOSED_GRACEFULLY;
CLOSED_BY_PEER;
CLOSED_BY_PROTOCOL;
FAILED.

Define legal transitions.

Every transition must emit exactly once.

LIVE

Authenticated application traffic or heartbeat activity demonstrates that:

transport works;
the peer runtime processes control messages.

Transport packets alone do not always prove application liveness.

SUSPECT

Enter SUSPECT when expected authenticated activity is missing beyond the suspicion threshold.

SUSPECT means uncertainty.

Temporary:

packet loss;
congestion;
Wi-Fi switching;
NAT rebinding;
scheduling pauses;
machine load;

must not immediately cause expiry.

EXPIRED

Enter EXPIRED after the configured application-level expiry bound.

On expiry:

complete the terminal cancellation signal once;
notify callers once;
reject new streams;
terminate pending requests;
begin cleanup;
reject stale activity;
require a new session for reconnect.

An EXPIRED session cannot return to LIVE.

Graceful close

A peer may send an authenticated bounded goodbye containing:

session ID;
close reason;
final control sequence;
optional diagnostic code.

Then QUIC closes gracefully.

The receiver should detect this promptly.

Timeout cleanup remains mandatory because close packets may be lost.

Heartbeats

Use application-level heartbeats.

A heartbeat may include:

session ID;
monotonic sequence;
acknowledgement sequence;
sender-local monotonic marker;
minimal liveness metadata;
optional path observation.

Do not compare monotonic clocks between machines.

Wall-clock time must not determine liveness.

Do not sign every heartbeat unless a documented threat justifies it. The authenticated QUIC session already protects integrity.

Record the decision in an ADR.

Timing

Expose configurable:

heartbeat interval;
suspicion timeout;
expiry timeout;
handshake timeout;
graceful-close timeout;
QUIC idle timeout;
reconnect backoff.

Validate relationships such as:

heartbeat interval less than suspicion timeout;
suspicion timeout less than expiry timeout;
application expiry less than QUIC idle timeout where required.

Defaults are implementation defaults, not protocol constants.

Document detection bounds and false-suspicion tradeoffs.

Cancellation

Expiry exposes a generic cancellation signal.

Synesis Link does not decide which application work stops.

It must not terminate unrelated local work.

Reconnection

After connection loss:

refresh descriptors where needed;
gather candidates again;
rerank pairs;
authenticate again;
negotiate again;
create a new session ID;
apply epoch rules;
reject old control frames;
keep old streams terminal;
keep old cancellation completed;
emit a new-session event.

Do not hide the disconnect through transparent session resurrection.

Distinguish:

path change;
QUIC path migration;
transport reconnect;
new Synesis Link session.
Path migration and NAT rebinding

Where the selected QUIC implementation supports it:

observe path changes;
expose safe path metadata;
do not treat path as identity;
retain the same session during valid migration;
continue heartbeats;
report migration failure.

Test where practical:

address changes;
NAT rebinding;
migration during transfer;
migration near heartbeat boundaries.

Document unsupported behavior honestly.

Error model

Define structured errors including:

INVALID_DESCRIPTOR;
EXPIRED_DESCRIPTOR;
UNSUPPORTED_DESCRIPTOR_VERSION;
UNSUPPORTED_PROTOCOL_VERSION;
NO_COMPATIBLE_CANDIDATE;
DIRECT_CONNECTIVITY_UNAVAILABLE;
CONNECTION_TIMEOUT;
CONNECTION_REFUSED;
TRANSPORT_FAILURE;
IDENTITY_MISMATCH;
IDENTITY_PROOF_INVALID;
HANDSHAKE_REPLAY_REJECTED;
PROTOCOL_DOWNGRADE_REJECTED;
MALFORMED_FRAME;
FRAME_TOO_LARGE;
RESOURCE_LIMIT_EXCEEDED;
HANDSHAKE_TIMEOUT;
SESSION_EXPIRED;
SESSION_CLOSED;
STREAM_REJECTED;
INTERNAL_FAILURE.

Document retry safety.

Do not expose secrets or unbounded remote input in exceptions.

Observability

Provide structured hooks without imposing a telemetry backend.

Support observing:

candidate-gathering duration;
provider outcomes;
candidate count;
attempted pairs;
connection outcomes;
selected path type;
QUIC handshake duration;
Synesis handshake duration;
identity rejection;
incompatibility;
session establishment;
heartbeat round-trip estimates;
liveness transitions;
path changes;
stream opens and rejection;
resource-limit rejection;
graceful close;
abrupt close;
cleanup duration;
leak detection in tests.

Logs must not expose:

private keys;
secret material;
sensitive challenges;
full sensitive descriptors by default;
unnecessary private addresses;
unsanitized attacker-controlled payloads.

Public value types need safe toString() behavior.

Threat model

Create docs/security/THREAT_MODEL.md.

Cover:

forged descriptors;
descriptor tampering;
stale descriptors;
descriptor replay;
candidate floods;
malicious private-address candidates;
scanning abuse;
impersonation;
identity-key theft;
MITM;
arbitrary TLS certificate acceptance;
handshake replay;
connection substitution;
reflection;
downgrade;
malformed frames;
oversized frames;
allocation attacks;
stream exhaustion;
connection floods;
heartbeat floods;
slow readers;
slow writers;
callback starvation;
executor exhaustion;
native-resource leaks;
log injection;
private-address leakage;
secret leakage;
insecure local key permissions;
dependency compromise;
native library compromise;
protocol ossification;
unsafe 0-RTT;
candidate-racing denial of service.

For every threat identify:

asset;
attacker;
capability;
trust boundary;
mitigation;
residual risk;
verification test where practical.
Testing architecture

Build deterministic testing support inside the same project unless evidence supports a split.

Support:

controlled clocks;
deterministic scheduling where practical;
deterministic candidate order;
fake providers;
fake identities;
fake transport for state-machine tests;
real local QUIC integration tests;
process-level tests;
fault injection;
resource-leak checks.

Do not mock everything.

Maintain both:

deterministic protocol tests;
real QUIC tests.
Fault injection

Support testing:

latency;
jitter;
packet loss where possible;
delayed control messages;
blackholed traffic;
abrupt close;
graceful close;
duplicate control messages;
stale control messages;
provider failure;
provider timeout;
competing successful candidates;
connection cancellation;
delayed heartbeat acknowledgement;
scheduler pauses;
stream saturation;
malformed frames;
oversized frames;
close during handshake;
close during stream opening.
Mandatory tests

Implement at least:

Identity generation, persistence, loading, signing, and verification.
Deterministic node-ID derivation.
No private key in toString().
Candidate descriptor creation, canonical encoding, signing, decoding, and verification.
Semantically equivalent descriptors produce identical canonical bytes.
Tampered descriptors are rejected.
Wrong-key signatures are rejected.
Node-ID and public-key mismatch is rejected.
Expired descriptors are rejected.
Far-future issue times are rejected.
Clock skew behaves as specified.
Oversized descriptors are rejected before dangerous allocation.
Duplicate candidates normalize predictably.
Candidate ranking is deterministic.
One failed provider does not block others.
Provider cancellation works.
Provider timeout works.
Two local processes establish a real QUIC connection.
They negotiate synesis-link/1.
Expected remote identity is authenticated.
Wrong expected identity is rejected.
Arbitrary TLS success cannot bypass Synesis authentication.
Replayed session proof is rejected.
Cross-connection proof substitution is rejected.
Downgrade is rejected.
PeerSession is exposed only after full authentication.
Control-stream framing works safely.
Graceful goodbye closes promptly.
Abrupt termination produces LIVE to SUSPECT to EXPIRED.
Temporary loss enters SUSPECT and recovers before expiry.
EXPIRED cannot return to LIVE.
State transitions emit exactly once.
Cancellation completes exactly once.
Repeated close is safe.
Stream opening after close fails predictably.
Large data traffic does not indefinitely starve heartbeats.
Stream-count limits are enforced.
Frame limits are enforced.
Queue limits are enforced.
Malformed frames are handled safely.
Losing candidate attempts are cancelled.
Losing attempts release resources.
Reconnect creates a new session ID.
Session epoch behavior is validated.
Old-session messages cannot affect the new session.
Old stream handles remain terminal.
Path migration does not alone expire a session where supported.
NAT rebinding is tested where supported.
Public thread-safety claims are tested.
Callback ordering is tested.
Shutdown completes within bounds.
Repeated cycles do not leak threads.
Repeated cycles do not leak sockets.
Repeated cycles do not leak native resources.
Golden vectors remain stable.
Javadocs generate without warnings.
Every public and protected API element is documented.
Every package has package-info.java.
Dependency verification passes.
The reference CLI connects two peers through public APIs only.

Use property-based tests where valuable for:

descriptor round trips;
parsers;
candidate normalization;
ranking;
state transitions;
frame boundaries;
version negotiation;
malformed inputs.

Fuzz parsers where practical.

No mandatory automated test may depend on public internet access.

Reference CLI

Build a minimal CLI under examples/cli or a suitable source set.

It must use only public Synesis Link APIs.

Support commands equivalent to:

initialize identity;
display node ID;
display fingerprint;
gather candidates;
create signed descriptor;
write descriptor file;
print compact invitation;
validate descriptor;
listen;
connect;
require or display expected fingerprint;
display authentication;
display selected path;
display session ID and epoch;
display liveness transitions;
open a simple typed stream;
exchange test messages;
close gracefully;
explain abrupt-termination testing;
print structured diagnostics when direct connection fails.

It must not become:

a GUI;
chat software;
file-transfer software;
remote shell;
relay;
the wider Synesis application.
Build quality

Configure:

Java 25 toolchain;
reproducible builds where practical;
dependency locking;
Gradle dependency verification;
formatting;
static analysis;
strict compiler warnings;
strict Javadocs;
test reports;
API documentation;
dependency and license reporting where practical.

Treat warnings seriously.

Do not globally suppress:

deprecation;
unchecked operations;
nullability;
Javadocs;
native access;
resource warnings.

A suppression requires:

narrow scope;
reason;
comment;
task reference when temporary.

Do not introduce Lombok merely to reduce typing.

Prefer explicit immutable records and value types.

Use a consistent nullness strategy and record the choice.

Public API quality

The API must:

remain small;
remain cohesive;
use immutable values;
avoid implementation leakage;
define nullability;
define thread safety;
define ownership;
define lifecycle;
define cancellation;
define timeouts;
define errors;
avoid global mutable state;
avoid static service locators;
avoid hidden non-daemon threads;
avoid hidden unbounded executors;
support deterministic shutdown;
support dependency injection for test-sensitive behavior where justified.

Avoid raw maps and loosely typed strings where stable types are appropriate.

V1 non-goals

Do not implement:

broader Synesis functionality;
project synchronization;
project memory;
project events;
tasks;
AI agents;
agent hierarchies;
capability ownership;
leases;
fencing;
consensus;
locks;
Git;
remote execution;
remote shell;
generic RPC;
central servers;
mandatory rendezvous;
relay traffic;
TURN;
DHT discovery;
blockchain;
CRDTs;
database persistence;
accounts;
organizations;
cloud deployment;
Kubernetes;
microservices;
deployed telemetry backends;
GUI;
mobile applications;
automatic trust-on-first-use by default;
custom cryptography;
replacement QUIC internals.

Extension interfaces may permit future candidate sources.

Do not build speculative frameworks for absent products.

Implementation progression

Follow these verified slices unless evidence supports a change.

Slice 1: Contract, architecture, and build

Deliver:

active contract;
reconciled persistence files;
architecture pass;
ADR backlog;
Gradle build;
Java 25;
strict compiler setup;
strict Javadocs;
dependency verification;
first passing test.
Slice 2: Node identity

Deliver:

identity generation;
node-ID derivation;
signing;
verification;
storage abstraction;
documented local storage;
Javadocs;
tests.
Slice 3: Candidate descriptor

Deliver:

candidate model;
canonical encoding;
signing;
verification;
expiry;
limits;
golden vectors;
Javadocs;
tests.
Slice 4: First real local QUIC connection

Deliver:

selected QUIC implementation;
internal adapter;
listener;
connector;
ALPN;
two-process connection;
deterministic shutdown;
Javadocs;
integration test.

Do not implement every candidate provider before proving the real transport path.

Slice 5: Identity binding

Deliver:

authenticated handshake;
expected identity;
session transcript;
replay defense;
version negotiation;
PeerSession;
Javadocs;
tests.
Slice 6: Control and graceful close

Deliver:

framing;
bounded control messages;
control lifecycle;
goodbye;
close reasons;
Javadocs;
tests.
Slice 7: Heartbeat and liveness

Deliver:

heartbeats;
acknowledgements;
LIVE;
SUSPECT;
EXPIRED;
recovery before expiry;
exactly-once transitions;
cancellation;
Javadocs;
deterministic tests.
Slice 8: Candidate providers and racing

Deliver:

justified candidate providers;
provider timeout;
cancellation;
normalization;
ranking;
bounded racing;
loser cleanup;
diagnostics;
Javadocs;
tests.
Slice 9: Reconnect and path behavior

Deliver:

new session IDs;
epochs;
old-session rejection;
path-change reporting;
migration testing where possible;
Javadocs;
tests.
Slice 10: Hardening

Deliver:

limits;
malformed-input handling;
fault injection;
lifecycle repetition;
leak checks;
threat-model reconciliation;
Javadocs.
Slice 11: CLI and release verification

Deliver:

two-peer CLI;
descriptor workflow;
two-machine guide;
generated Javadocs;
protocol specification;
test vectors;
release notes;
release checklist;
clean full build.
Completion criteria

Synesis Link v1 is complete only when:

a clean checkout builds;
all mandatory automated tests pass;
strict Javadocs pass without warnings;
every public and protected element is documented;
every package has package-info.java;
important internal protocol and concurrency behavior is documented;
two local processes establish a real authenticated QUIC session;
two physical computers establish a session under at least one documented direct-network scenario;
the CLI creates and consumes signed descriptors;
expected identity is verified;
wrong identity is rejected;
incompatibility fails deterministically;
handshake replay is rejected;
graceful shutdown is detected promptly;
abrupt loss follows bounded LIVE to SUSPECT to EXPIRED behavior;
temporary loss can recover;
cancellation completes exactly once;
reconnect creates a new session;
old-session input cannot affect it;
stream limits work;
large streams do not indefinitely starve control;
malformed and oversized input is safe;
repeated lifecycle tests show no managed or native leaks;
connection attempts are bounded;
losing attempts are cleaned up;
connectivity failure produces useful diagnostics;
no mandatory server exists;
no wider Synesis functionality exists;
protocol fields are documented;
state machines are documented;
threat model and implementation agree;
golden vectors exist;
dependency verification passes;
TEST_MATRIX has no uncovered mandatory invariant;
TASKS has no unresolved P0 or P1 task;
STATE contains final commands and results;
no critical TODO is untracked;
release notes exist;
release checklist exists;
final persistence checkpoint passes.

Do not mark v1 complete based only on compilation.

Blocker rules

Ask for human input only when:

two product interpretations cannot be resolved;
a dependency license creates a product-level choice;
supported platforms must be reduced;
a required guarantee would change scope;
credentials, hardware, or access are unavailable;
a major architecture choice would make the v1 contract impossible.

Do not ask for ordinary reversible engineering decisions.

When blocked:

document the blocker;
mark affected tasks BLOCKED;
provide evidence;
list viable alternatives;
state the exact decision required;
continue unrelated work.
Repository safety

Do not:

force-push;
rewrite remote history;
publish packages;
create releases;
tag versions;
push to a remote;
delete user files;
modify unrelated repositories;
install global software without justification.

Never commit:

private keys;
real credentials;
sensitive invitations;
personal absolute paths;
sensitive network information;
production secrets.
Initial execution

Start now.

Run the mandatory persistence startup.
Install this complete prompt into docs/agent/CONTRACT.md.
Set contract revision 1 and ACTIVE status.
Reconcile all persistence files.
Run the checkpoint validator.
Run the constraint-driven architecture skill.
Create the initial architecture package.
Research current Java QUIC options from primary sources.
Record the QUIC decision in an ADR.
Configure Java 25 and Gradle.
Configure strict compiler, documentation, testing, and dependency checks.
Create the complete prioritized task graph.
Activate the first implementation slice.
Implement the smallest verified vertical behavior.
Continue autonomously through the persistent working loop.

Do not return only a plan.

Do not stop after creating Markdown.

Do not create the wider Synesis system.

Materialize Synesis Link v1.

## Deferred capability register contract

`docs/agent/DEFERRED.md` is authoritative for intentionally postponed,
unsupported, partially verified, research-dependent, and physically unverified
functionality. Deferred status is not proof of implementation, a release
promise, or permission to implement; code abstractions are not evidence.

Startup, task promotion, architecture review, release preparation, public-claim
review, checkpoint creation, protocol-scope changes, security review, and
documentation review must include the register. A deferred capability may be
implemented only after its activation trigger and required evidence/research
are present, the item is explicitly promoted, a concrete task with acceptance
criteria is created, and exactly one task is made `ACTIVE`. Keep the item until
the task replaces it and record the replacement task and checkpoint.

Durable TODOs for deferred Synesis Link work must use
`TODO(SL-D-NNN)` and reference an existing register entry. Vague deferred TODOs
are invalid. Public documentation and release notes must not claim support for
capabilities whose evidence is absent or whose register entry remains deferred,
blocked, or physically unverified. Two-process evidence must never be labeled
two-machine evidence.
