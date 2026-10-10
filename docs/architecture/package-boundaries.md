# SYN-009A Package and Module Boundaries

```text
:link
  ↑
:project-record
  ↑
:coordination
  ↑
:workspace
  ↑         ↑
:cli       :mcp
```

The CLI and MCP modules are peer transport adapters.

- `:cli` owns Picocli command parsing and terminal formatting.
- `:mcp` owns stdio JSON-RPC 2.0 frames and MCP tool handlers (`org.synesis.mcp`).
- `:cli` does NOT depend on `:mcp`.
- `:mcp` does NOT depend on `:cli`.
- Both depend on shared application services in `:workspace` (`org.synesis.workspace.application`,
  `org.synesis.workspace.agent`).

The boundary is enforced by `:mcp:architectureCheck` and Gradle dependency locks.

## Workspace package layout

The workspace module keeps its shared project application facade at
`org.synesis.workspace.application.ProjectApplicationService`. Project identity,
location, and initialization result types live under
`org.synesis.workspace.application.project` beside project commands and process
execution.

Agent session admission lives under `application.agent.session`. The
next-action and task-completion services and their package-local no-change
eligibility rule stay together in `application.agent.workflow`. Cancellation
and completion request/outcome types live in `application.agent.completion`.
Provider session commands, authority resolution, and terminalization live
under `application.provider.session`. The shared binding service remains in
`application.provider` because its hook materialization path uses the provider
application service's package-private helper.

Project process requests, results, execution and process identity live under
`application.project.execution`; project identity and command admission remain
at `application.project`. Workspace reads live under `application.workspace.read`,
while patching, mutation brokerage and outcome translation live under
`application.workspace.mutation`. The shared readiness predicate remains in
the workspace package root because session, command, read and mutation flows
all consume it.

The local control-plane's Link, membership, route, relay, and network DTOs live
under `org.synesis.workspace.transport.control.network`; HTTP routing and
control-plane operation ownership remain under `transport.control`. Codex App
Server framing, schema, request envelope, and JSONL client code live under
`org.synesis.workspace.lifecycle.codex.protocol`; the loopback HTTP adapter
and client live under `org.synesis.workspace.lifecycle.codex.http`. Both stay
inside the Codex lifecycle boundary that owns their protocol and runtime.

Durable lifecycle checkpoints, evidence and idempotency live under
`lifecycle.codex.state`; managed home, authentication, runtime mode and thread
ownership live under `lifecycle.codex.runtime`. The wake coordinator and
process-tree launcher/supervisor remain with the Codex host because they use
package-private construction and launcher contracts shared with that host.

The package inventory below is generated from the `package` declarations in
`workspace/src`. Wake and process integration types remain in the Codex core
package for the package-private contracts described above; moving them would
require exposing internal factories or splitting the host's launcher contract.

`lifecycle.process` groups the shared Git command runner with the semantic
index fingerprint. The fingerprint uses a package-private bounded-output
overload, keeping that inspection option internal to the process package.

Cleanup inventory and path verification live under `lifecycle.cleanup.inventory`;
eligibility and persisted plans live under `lifecycle.cleanup.plan`; and locks,
journals, quarantine, and execution live under `lifecycle.cleanup.execution`.
The cleanup root retains only cross-stage integration tests.

## Workspace source package map

The lists below show every top-level Java type under its declared package. Most source files contain one top-level type; refresh this section when package ownership changes.

### Production

- `org.synesis.workspace.agent`: `AgentCapabilityResult`, `AgentMutationResult`, `AgentNextAction`, `AgentReason`, `AgentResponse`, `AgentStatus`, `AgentStatusResult`, `AgentWorkspaceGuidance`
- `org.synesis.workspace.application`: `ProjectApplicationService`
- `org.synesis.workspace.application.agent.completion`: `AgentTaskCancellationService`, `CompleteTaskRequest`, `CompletionOutcome`
- `org.synesis.workspace.application.agent.session`: `AgentSessionContext`, `AgentSessionService`, `AgentTaskIntent`, `SessionResolutionRequest`
- `org.synesis.workspace.application.agent.workflow`: `AgentNextActionService`, `AgentReviewActionProjection`, `AgentTaskCompletionService`, `AgentWorkflowReducer`, `CollaborationResponseMapper`, `NextActionRequest`, `NoChangeCompletionEligibility`
- `org.synesis.workspace.application.capability`: `CapabilityRequestService`, `CapabilityResponseService`
- `org.synesis.workspace.application.collaboration`: `CollaborationSnapshot`, `ContractSnapshot`, `ReviewSnapshotAccessService`, `ReviewValidationService`, `WorkspaceCollaborationService`
- `org.synesis.workspace.application.constraint`: `ConstraintApplicationService`
- `org.synesis.workspace.application.control`: `ControlBranchAdvancementService`
- `org.synesis.workspace.application.guardrail`: `ActionCheckResult`, `GuardrailApplicationService`
- `org.synesis.workspace.application.hook`: `HookApplicationService`
- `org.synesis.workspace.application.integration`: `ImplementationPublicationService`, `ImplementationValidationService`, `IntegrationCompatibilityService`, `IntegrationOrchestrationService`, `IntegrationWorkspaceService`, `ValidationWorkspaceService`, `WorkspaceIntegrationReadinessService`
- `org.synesis.workspace.application.project`: `ProjectCommandAdmissionService`, `ProjectCommandService`, `ProjectCommandSpec`, `ProjectCreateResult`, `ProjectIdentity`, `ProjectInitResult`, `ProjectInitStatus`, `ProjectLocation`
- `org.synesis.workspace.application.project.execution`: `ExecutionRequest`, `ExecutionResult`, `ProjectProcessExecutor`, `StartedProcessIdentity`
- `org.synesis.workspace.application.provider`: `ProviderApplicationService`, `ProviderManualService`, `ProviderMcpConfigurationService`, `ProviderSessionBindingService`
- `org.synesis.workspace.application.provider.continuity`: `ManagedAttachmentRecord`, `ManagedAttachmentService`, `ManagedAttachmentStore`, `ManagedRuntimeDeathReceipt`, `ManagedRuntimeDeathReceiptStore`, `ProviderContinuityMode`, `ProviderThreadOwnershipRecord`, `ProviderThreadOwnershipStore`, `RuntimeAuthenticator`
- `org.synesis.workspace.application.provider.session`: `ProviderSessionTerminalizationService`, `SessionAuthorityResolver`
- `org.synesis.workspace.application.sync`: `ProjectContextExchange`, `SyncApplicationService`
- `org.synesis.workspace.application.task`: `SnapshotArtifactPolicy`, `TaskSnapshotService`
- `org.synesis.workspace.application.workspace`: `ReadinessResult`, `WorkspaceReadinessService`
- `org.synesis.workspace.application.workspace.mutation`: `AgentOutcomeTranslator`, `PatchEdit`, `PatchRequest`, `TranslatedOutcome`, `WorkspaceMutationBroker`, `WorkspacePatchService`
- `org.synesis.workspace.application.workspace.read`: `ReadRequest`, `WorkspaceReadService`
- `org.synesis.workspace.discovery`: `KnownProjectRegistry`
- `org.synesis.workspace.doctor`: `DoctorConfidence`, `DoctorFinding`, `DoctorFindingCode`, `DoctorRecommendation`, `DoctorRenderer`, `DoctorReport`, `DoctorService`, `DoctorSeverity`, `DoctorStatus`
- `org.synesis.workspace.infrastructure.digest`: `Sha256`
- `org.synesis.workspace.infrastructure.filesystem`: `AtomicFiles`, `ProjectMetadataStorage`, `TextFileDocument`
- `org.synesis.workspace.infrastructure.json`: `ProviderJson`
- `org.synesis.workspace.infrastructure.process`: `ProcessEvidenceState`, `ProcessInspector`
- `org.synesis.workspace.lifecycle`: `AdministrativeStateLocator`, `ManagedBaselineTransactionService`, `ManagedPathPolicy`, `RepositoryPortabilityService`, `RepositoryPrivateStateService`
- `org.synesis.workspace.lifecycle.cleanup.execution`: `CleanupEntryExecutionState`, `CleanupExecutionJournal`, `CleanupExecutionLock`, `CleanupExecutionRecord`, `CleanupExecutionService`, `LifecycleQuarantineService`
- `org.synesis.workspace.lifecycle.cleanup.inventory`: `LifecycleInventoryService`, `LifecyclePathVerifier`, `LifecycleResourceFingerprint`, `LifecycleResourceType`
- `org.synesis.workspace.lifecycle.cleanup.plan`: `CleanupClassification`, `CleanupEligibilityService`, `CleanupPlan`, `CleanupPlanEntry`, `CleanupPlanService`, `CleanupPlanStore`, `CleanupReason`, `PersistedCleanupPlan`, `PersistedCleanupPlanEntry`, `RetentionPolicy`
- `org.synesis.workspace.lifecycle.codex`: `CodexAppServerLifecycleService`, `CodexWakeAdmissionService`, `CodexWakeCoordinator`, `ManagedCodexProcessLauncher`, `ManagedProcessTreeSupervisor`, `ProcessTreeTerminator`, `ProjectRuntimeHost`, `WindowsJobObjectProcessTreeSupervisor`
- `org.synesis.workspace.lifecycle.codex.http`: `CodexLifecycleHttpAdapter`, `CodexLifecycleHttpClient`
- `org.synesis.workspace.lifecycle.codex.protocol`: `BoundedProtocolFrameReader`, `CodexAppServerProtocolClient`, `CodexAppServerProtocolSchema`, `LifecycleControlRequestEnvelope`
- `org.synesis.workspace.lifecycle.codex.runtime`: `CodexManagedAuthentication`, `CodexManagedRuntimeHome`, `ManagedCodexRuntimeMode`, `ManagedCodexThreadBroker`
- `org.synesis.workspace.lifecycle.codex.state`: `CodexEvidenceJournal`, `CodexEvidenceRetention`, `CodexLifecycleStateStore`, `LifecycleIdempotencyLedger`
- `org.synesis.workspace.lifecycle.command`: `CommandDurableFormat`, `CommandFormatException`, `CommandPermanentLock`, `PhysicalWorktreeIdentity`, `ProjectCommandAuthoritySnapshot`, `ProjectCommandCanonicalizer`, `ProjectCommandDiagnostics`, `ProjectCommandMaintenanceService`, `ProjectCommandNamespace`, `ProjectCommandPhase`, `ProjectCommandProcessAnchor`, `ProjectCommandProtectionService`, `ProjectCommandRecord`, `ProjectCommandStore`, `ProjectCommandTerminalResolution`
- `org.synesis.workspace.lifecycle.lease`: `SessionLeasePolicy`, `SessionLeaseRecord`, `SessionLeaseService`, `SessionLeaseState`, `SessionLeaseStore`, `SessionProcessIdentity`
- `org.synesis.workspace.lifecycle.process`: `GitProcessRunner`, `ProcessCommandRunner`, `SemanticIndexFingerprint`
- `org.synesis.workspace.lifecycle.reconciliation`: `ReconciliationAction`, `ReconciliationExecutionJournal`, `ReconciliationExecutionLock`, `ReconciliationExecutionRecord`, `ReconciliationPlan`, `ReconciliationPlanEntry`, `ReconciliationPlanStore`, `ReconciliationService`
- `org.synesis.workspace.lifecycle.recovery`: `RecoverySnapshotService`
- `org.synesis.workspace.lifecycle.repair`: `RepairAction`, `RepairBackupService`, `RepairExecutionJournal`, `RepairExecutionLock`, `RepairPlan`, `RepairPlanEntry`, `RepairPlanStore`, `RepairService`
- `org.synesis.workspace.migration`: `ProjectMigrationService`, `ProviderConfigMigrationService`
- `org.synesis.workspace.project`: `ActionGuardrail`, `ProjectPathResolver`
- `org.synesis.workspace.provider`: `ProviderIntegration`, `ProviderMcpEvidenceTier`, `ProviderRegistry`, `ProviderSupportLevel`
- `org.synesis.workspace.provider.claude`: `ClaudeCodeHookAdapter`, `ClaudeCodeProviderIntegration`
- `org.synesis.workspace.provider.codex`: `CodexApplyPatchParser`, `CodexHookAdapter`, `CodexNativePatchRouter`, `CodexProviderIntegration`, `CodexTomlConfiguration`
- `org.synesis.workspace.transport.control`: `ControlPlaneEventHub`, `ControlPlaneHttpHandler`, `ControlPlaneLinkOperations`, `ControlPlaneReadModel`, `Event`, `LinkRuntimeOwner`, `OnboardingControlPlaneOperations`
- `org.synesis.workspace.transport.control.network`: `LinkNetworkProjection`, `LinkState`, `NetworkPeer`, `NetworkRoute`, `NetworkSnapshot`, `OverlayEdge`, `OverlayMember`, `OverlaySummary`, `RelayState`, `RelaySummary`

### Tests

- `org.synesis.workspace`: `AgentNextActionServiceTest`, `CapabilityNegotiationTest`, `CodexHookOwnershipTest`, `MultiChatLogicalWorkspaceTest`, `ProjectCommandServiceTest`, `ProviderApplicationServiceTest`, `ProviderSessionBindingServiceTest`, `RepositoryPrivateStateServiceTest`, `Syn037CompletionValidationTest`, `TaskIntegrationServiceTest`, `WorkspaceMutationBrokerTest`, `WorkspacePatchServiceTest`, `WorkspaceReadServiceTest`
- `org.synesis.workspace.agent`: `AgentResponseTest`, `AgentWorkflowReducerTest`
- `org.synesis.workspace.application`: `ProjectApplicationServiceTest`, `ProjectReplicaProcessTest`
- `org.synesis.workspace.application.agent.completion`: `TaskCancellationTest`
- `org.synesis.workspace.application.agent.session`: `AgentSessionServiceTest`
- `org.synesis.workspace.application.agent.workflow`: `AgentTaskCompletionReviewPolicyTest`, `ReviewAdmissionOrderIndependenceTest`, `SessionTerminalStateUnavailableTest`
- `org.synesis.workspace.application.integration`: `IntegrationCompatibilityServiceTest`, `IntegrationOrchestrationServiceTest`, `IntegrationQueueTest`, `ValidationWorkspaceTest`
- `org.synesis.workspace.application.project.execution`: `ProjectProcessExecutorTest`
- `org.synesis.workspace.application.provider`: `ProviderManualServiceTest`, `ProviderSessionTerminalizationServiceTest`
- `org.synesis.workspace.application.provider.continuity`: `ManagedAttachmentServiceTest`, `ProviderThreadOwnershipStoreTest`
- `org.synesis.workspace.application.sync`: `ProjectContextExchangeTest`
- `org.synesis.workspace.application.task`: `SnapshotArtifactPolicyTest`
- `org.synesis.workspace.application.workspace.mutation`: `AgentOutcomeTranslatorTest`
- `org.synesis.workspace.architecture`: `WorkspaceApplicationPackageArchitectureTest`, `WorkspacePackageArchitectureTest`
- `org.synesis.workspace.discovery`: `KnownProjectRegistryTest`
- `org.synesis.workspace.doctor`: `DoctorServiceTest`
- `org.synesis.workspace.infrastructure.digest`: `Sha256Test`
- `org.synesis.workspace.infrastructure.filesystem`: `ProjectMetadataStorageTest`, `TextFileDocumentTest`
- `org.synesis.workspace.infrastructure.json`: `ProviderJsonTest`
- `org.synesis.workspace.infrastructure.process`: `ProcessInspectorTest`
- `org.synesis.workspace.lifecycle`: `AdministrativeStateLocatorTest`, `ManagedBaselineTransactionServiceTest`, `ManagedPathPolicyTest`, `RepositoryPortabilityServiceTest`
- `org.synesis.workspace.lifecycle.cleanup`: `NoForceAndSafetyArchitectureTest`, `ValidationAndIntegrationCleanupTest`
- `org.synesis.workspace.lifecycle.cleanup.execution`: `JournalAndRestartTest`, `LockAndConcurrencyTest`, `QuarantineTest`, `TemporaryFileCleanupTest`, `WorkerCleanupTest`
- `org.synesis.workspace.lifecycle.cleanup.inventory`: `LifecyclePathVerifierTest`
- `org.synesis.workspace.lifecycle.cleanup.plan`: `CleanupEligibilityServiceTest`, `PlanPersistenceTest`, `StalePlanTest`
- `org.synesis.workspace.lifecycle.codex`: `CodexLifecycleWaitControlTest`, `CodexWakeAdmissionServiceTest`, `CodexWakeCoordinatorTest`, `ProcessTreeTerminatorTest`, `ProjectRuntimeHostWakeTest`, `WindowsJobObjectProcessTreeSupervisorTest`
- `org.synesis.workspace.lifecycle.codex.protocol`: `BoundedProtocolFrameReaderTest`, `CodexAppServerProtocolClientTest`, `CodexAppServerProtocolSchemaTest`, `LifecycleControlRequestEnvelopeTest`
- `org.synesis.workspace.lifecycle.codex.runtime`: `CodexManagedRuntimeHomeTest`, `ManagedCodexThreadBrokerTest`
- `org.synesis.workspace.lifecycle.codex.state`: `CodexEvidenceJournalTest`, `LifecycleIdempotencyLedgerTest`
- `org.synesis.workspace.lifecycle.command`: `CommandLockProbe`, `ProjectCommandNamespaceSpikeTest`
- `org.synesis.workspace.lifecycle.lease`: `LeaseTest`, `StaleAndGraceTest`, `TerminalLeaseStateTest`
- `org.synesis.workspace.lifecycle.process`: `ProcessCommandRunnerTest`, `ProcessCommandRunnerChild`, `SemanticIndexFingerprintTest`
- `org.synesis.workspace.lifecycle.reconciliation`: `AbandonmentTest`, `ReconciliationPlanTest`, `RecoveryLifecycleTest`
- `org.synesis.workspace.lifecycle.recovery`: `RecoverySnapshotServiceTest`
- `org.synesis.workspace.lifecycle.repair`: `RepairPlanTest`
- `org.synesis.workspace.migration`: `PostMigrationReplayVerifierTest`, `ProjectMigrationRestorationServiceTest`, `ProjectMigrationServiceTest`, `ProviderConfigMigrationServiceTest`
- `org.synesis.workspace.provider.claude`: `ClaudeCodeHookAdapterTest`
- `org.synesis.workspace.provider.codex`: `CodexApplyPatchParserTest`, `CodexHookAdapterTest`, `CodexHookPerformanceTest`
- `org.synesis.workspace.test`: `PortableTestCommand`, `PortableTestProcess`, `ProviderTestSupport`, `TestGit`
- `org.synesis.workspace.transport.control`: `ControlPlaneHttpHandlerTest`, `ControlPlaneReadModelTest`, `LinkNetworkProjectionTest`, `LinkRuntimeOwnerControlPlaneTest`

## MCP source package map

MCP keeps a root server entrypoint, connection-scoped application logic, and an independent stdio transport. `McpProtocolHandler` owns the persistent connection binding, lease state, JSON-RPC lifecycle, and authority-gated tool dispatch. `McpProjectRootAuthority` isolates root verification; `McpToolCall` validates the untrusted tools/call envelope; and `McpCollaborationProjection` serializes typed collaboration records at the JSON boundary.

### Production

- `org.synesis.mcp`: `SynesisMcpServer`
- `org.synesis.mcp.application`: `McpCollaborationProjection`, `McpProjectRootAuthority`, `McpProtocolHandler`, `McpToolCall`
- `org.synesis.mcp.transport.stdio`: `McpFrameReader`, `McpStdioServer`

### Tests

- `org.synesis.mcp`: `SynesisMcpServerTest`
- `org.synesis.mcp.application`: `CodexLinkedWorktreeRootSelectionRegressionTest`, `McpAdmissionRecoveryTest`, `McpMandatoryReviewPolicyTest`, `McpPackageArchitectureTest`, `McpProviderTestSupport`, `McpRecoveryContinuationTest`, `McpServerTest`, `McpStage2BSlice1Test`, `McpSyn028ContractTest`, `McpSyn039NoChangeCompletionTest`, `McpSyn039RejectedContinuationTest`, `McpSyn039SliceTest`, `McpToolCallTest`, `McpToolCatalogTest`, `Slice4FailureScenariosTest`, `SyntheticTwoProcessCollaborationTest`, `TwoProcessCapabilityNegotiationProcessTest`
- `org.synesis.mcp.transport.stdio`: `McpFrameReaderTest`
