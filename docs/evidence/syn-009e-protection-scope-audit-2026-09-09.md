# SYN-009E protection-scope and keep-rule audit — 2026-09-09

Classification: **PASS / CURRENT-SOURCE SCOPE EVIDENCE; COMMERCIAL
TRANSFORMATION NOT EXECUTED**.

This is a read-only audit of the current Synesis source and release inventories.
It verifies that the planned protection boundary still names real source paths
and method signatures before a licensed protector is introduced. It does not
prove that any commercial ring has been applied.

## Inputs

- Referenced brief: `C:\Users\Liparakis\.codex\attachments\3aa44bec-cbc3-45bc-a38b-c9ed3be9ee98\pasted-text-1.txt`.
- Brief SHA-256: `B5D067ABC48D9B78B9CFABDE06E04A7C82E236D7B9944CD9077A49955EEE458C`.
- Checkout: `master`, HEAD `50ca7f7f`.
- Scope documents: `docs/release/protection-tier-inventory.md` and
  `docs/release/protection-keep-rules.md`.

## Source-presence results

- **21/21** Tier 0, Tier 2, and Tier 3 source paths or directories named by
  the inventory exist at the current checkout.
- **12/12** method-scoped Tier 3 candidates have a corresponding current
  method declaration: `SessionAuthorityResolver.resolve`,
  `resolveCompleted`, and `resolveReview`; `ProviderSessionBindingService.verifyWorkspaceTrust`;
  `OwnershipRegistry.evaluate`; `CapabilityRequestProjection.validate`,
  `apply`, `processAccepted`, and `processValidated`; `OverlayMembershipView.accept`;
  `OverlayRouteSelector.select`; and `OverlayRelayPolicy.allows`.
- `PredictionEventStore` is retained in the inventory as an event-validation
  boundary; the inventory does not claim a separate method-scoped Tier 3
  signature for it.

The path and signature results confirm that the planning inventory is not
pointing at stale or invented source locations. They do not decide which
methods a vendor can safely virtualize or prove that the selected methods are
performance-appropriate.

## Compatibility-boundary discovery

The selected owned Java inputs were searched for the boundaries called out by
the keep-rule inventory:

| Boundary | Current finding | Release implication |
|---|---|---|
| Reflective class loading | `McpCommand` and `ReadinessInspector` use `Class.forName` | Keep the MCP entrypoint and the QUIC readiness lookup compatible; repeat against the full commercial input set. |
| Resource loading | `VersionPlaceholderCommand` and `StaticResourceHandler` load classpath resources | Preserve `synesis-build.properties` and the `web-ui/` resource root. |
| `ServiceLoader` registrations | None found in the selected owned Java/resource source inputs | Keep the existing `META-INF/services` directory boundary, but do not claim a full dependency-graph audit. |
| Java `native` declarations | None found in the selected owned Java source inputs | The lite `native <methods>` rule remains a future narrow contract; third-party Netty/native and Go launcher audits remain separate. |
| Java reflection APIs | `McpCommand`, `ReadinessInspector`, and `PostMigrationReplayVerifier` | Preserve only the concrete reflective boundaries; no global application keep is justified. |

The actual CLI and relay ProGuard files contain no broad
`-keep org.synesis.**` directive. The keep-rule inventory mentions that pattern
only as a prohibited policy, while the concrete rules keep named entrypoints,
Picocli metadata, resource directories, and future native members narrowly.

## Boundary and limitation

This audit is preparatory evidence for keep-rule review and Tier 3 selection.
The commercial protector must repeat the compatibility scan over its complete
input closure, provide vendor-specific configuration, and pass shipped
artifact acceptance. No source-presence result here changes Rings 1–6 from
`BLOCKED`, and Ring 7 remains a partial release-pipeline seam.
