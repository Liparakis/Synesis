# Maximum-protection tier inventory

This inventory is the current-source targeting boundary for the
`maximum-release` adapter. It is deliberately a scope record, not a claim
that the listed transformations have been executed. The adapter must keep
Tier 0 contracts stable and must select Tier 2/3 methods by the exact
protector configuration and performance evidence for each release.

## Scope

The inventory covers Synesis-owned JVM inputs that enter the CLI or standalone
relay distributions. Third-party Netty, QUIC, JDK, and native runtime inputs
are not rewritten by the Synesis pass unless the selected protector documents
and the release review explicitly approve that treatment. The `web-ui` output
is client code and remains inspectable; it is minified, source-map-free, and
covered by the signed artifact manifest rather than treated as a secret.

## Tier 0 — public contracts and stable boundaries

These names, resources, wire markers, and entrypoints must remain stable or
be explicitly adapted and tested:

| Boundary | Current source or artifact | Protection rule |
|---|---|---|
| CLI process entrypoint | `cli/src/main/java/org/synesis/cli/SynesisCli.java` | Keep the launcher class and `main(String[])`. |
| MCP process bridge | `cli/src/main/java/org/synesis/cli/command/McpCommand.java` and `org.synesis.mcp.SynesisMcpServer` | Keep the reflective class name and invoked entrypoint. |
| CLI command contract | `cli/src/main/java/org/synesis/cli/command/**` | Preserve Picocli command names, option names, parameter metadata, and documented exit behavior. Keep only the annotation/reflection boundary required by the current keep-rule inventory. |
| Relay process entrypoint | `relay/src/main/java/org/synesis/relay/RelayMain.java` | Keep the application launcher and supported argument surface. |
| Browser resource root | `cli/src/main/java/org/synesis/cli/ui/StaticResourceHandler.java`, packaged `web-ui/**` | Preserve the `web-ui/` classpath root and final asset paths; remove production source maps. |
| Control-plane and protocol contracts | `workspace/src/main/java/org/synesis/workspace/transport/control/**`, `link/src/main/java/org/synesis/link/protocol/**` | Preserve documented JSON fields, protocol versions, SLO1/SLA2 links, and stable diagnostic codes. |
| Link/overlay wire markers | `link/src/main/java/org/synesis/link/overlay/**`, `link/src/main/java/org/synesis/link/transport/control/**`, and the `SLK1`/`SLE1`/`SLM1`/`SLT1` markers | Preserve canonical bytes and externally validated limits. |
| Relay wire/auth contract | `relay/src/main/java/org/synesis/relay/RelayWireCodec.java` | Preserve the authenticated hello/ack/frame format and bounded error behavior. |
| Native and resource boundaries | packaged native launchers, `META-INF/native/**`, and native method names | Preserve filenames, resource lookup paths, required JNI names, and third-party native loading contracts. |

## Tier 1 — normal proprietary implementation

The remaining owned implementation is eligible for shrinking, renaming,
package flattening, metadata reduction, and vendor-supported string/constant
protection after the Tier 0 boundary is tested. Initial source areas are:

- `cli/src/main/java/org/synesis/cli/**`, excluding the Tier 0 entrypoint and
  command metadata boundary;
- `coordination/src/main/java/org/synesis/coordination/**`;
- ordinary `workspace` application, lifecycle, persistence, and provider
  implementation outside the Tier 2 decision services below; and
- ordinary `link` session, candidate, and transport implementation outside
  the public protocol and Tier 2 overlay policies.

Tier 1 does not imply virtualization, protected loading, anti-analysis, or
anti-debugging. Those are separate commercial-ring requirements.

## Tier 2 — high-value decision engines

These source areas contain policy and authority decisions that are candidates
for stronger control-flow and constant protection after profiling:

| Engine | Current source candidates | Exclusions |
|---|---|---|
| Workspace authority and continuity | `workspace/src/main/java/org/synesis/workspace/application/provider/SessionAuthorityResolver.java`, `ProviderSessionBindingService.java`, provider continuity services | Do not transform public records or filesystem/resource formats without compatibility tests. |
| Coordination claims and capability lifecycle | `coordination/src/main/java/org/synesis/coordination/domain/ownership/OwnershipRegistry.java`, `domain/capability/**`, `application/WorkIntentMutationPrecondition.java`, and coordination application services | Preserve durable event codecs and public projection field names. |
| Provider lifecycle and review/integration decisions | `workspace/src/main/java/org/synesis/workspace/application/provider/**` and `application/integration/**` | Preserve provider IDs, hook formats, and durable state schemas. |
| Overlay membership and route policy | `link/src/main/java/org/synesis/link/overlay/OverlayMembershipView.java`, `OverlayTopologyPolicy.java`, `OverlayRouteSelector.java`, `OverlayRelayPolicy.java`, and related signed membership/topology records | Do not virtualize Netty/QUIC loops or opaque transit forwarding hot paths by default. |
| Relay admission policy | `relay/src/main/java/org/synesis/relay/OverlayRelayServer.java` and authenticated policy calls | Keep wire codec and bounded socket lifecycle compatible; measure forwarding latency before stronger transformation. |

## Tier 3 — crown-jewel method candidates

Tier 3 is method-scoped, not package-wide. The initial candidate list for a
commercial protector review is:

- `SessionAuthorityResolver.resolve`, `resolveCompleted`, and `resolveReview`;
- `ProviderSessionBindingService.verifyWorkspaceTrust` and the bounded
  decision paths it invokes;
- `OwnershipRegistry.evaluate`;
- capability acceptance/validation decision paths in
  `coordination/src/main/java/org/synesis/coordination/domain/capability/CapabilityRequestProjection.java`
  (`validate`, `apply`, `processAccepted`, and `processValidated`) together
  with the event-validation boundary in
  `coordination/src/main/java/org/synesis/coordination/persistence/PredictionEventStore.java`;
- `OverlayMembershipView.accept`;
- `OverlayRouteSelector.select` and its authorization decision path; and
- `OverlayRelayPolicy.allows` when profiling shows the policy is a meaningful
  analysis target and the relay forwarding path remains outside Tier 3.

The selected protector must prove genuine virtualization and protected
payload handling for at least one intentionally selected Tier 3 method in the
shipped artifact. A renamed method, reflection wrapper, dispatch table, or
compressed JAR is not evidence. The final release record must list the exact
method signatures selected, the protector configuration digest, the
performance result, and the private retrace/evidence locations.

## Required review before a maximum release

For every Tier 2/3 selection, the release review records:

1. the exact class/method scope and why it is proprietary;
2. the required Tier 0 keep/adaptation boundary;
3. startup, authority, routing, Link, relay, provider, memory, and size
   measurements before and after protection;
4. ordinary decompiler/static-analysis observations for the shipped artifact;
5. the vendor evidence for control-flow, virtualization, strings, protected
   loading, analysis-risk handling, and anti-instrumentation; and
6. private mapping/retrace/native-symbol locations that are excluded from the
   customer archive.

Until that review is performed with an installed licensed protector, this
inventory remains planning evidence and no Tier 2/3 ring is `PASS`.
