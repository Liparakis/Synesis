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
`application.project.execution`, together with `ProjectCommandSpec`, which both
the project command service and the command canonicalizer in `lifecycle.command`
consume; project identity and command admission remain at `application.project`.
The provider registry lives in `application.provider`, where the concrete Claude
Code and Codex integrations are composed, so `provider` holds only the
provider-neutral contracts. Workspace reads live under `application.workspace.read`,
while patching, mutation brokerage and outcome translation live under
`application.workspace.mutation`. The shared readiness predicate remains in
`application.workspace` because session, command, read and mutation flows
all consume it. The Codex native patch router lives in `application.hook`
beside `HookApplicationService`, its only caller.

The local control-plane's Link, membership, route, relay, and network DTOs live
under `org.synesis.workspace.transport.control.network`; HTTP routing and
control-plane operation ownership remain under `transport.control`. Codex App
Server framing, schema, the lifecycle control request envelope and response,
the evidence journal, and the JSONL client live under
`org.synesis.workspace.lifecycle.codex.protocol`. The loopback HTTP adapter
stays in the Codex core package because it calls the host that owns it; there
is no separate HTTP package.

Durable lifecycle checkpoints and idempotency live under
`lifecycle.codex.state`; managed home, authentication, runtime mode and thread
ownership live under `lifecycle.codex.runtime`. The evidence journal sits with
the protocol client that produces its entries, so `state` depends on `protocol`
and not the reverse. The wake admission service and coordinator and the
process-tree terminator are package-private and are called only by the host and
the lifecycle service, so they remain in the Codex core package; the
process-tree supervisor interface and its Windows implementation are only two
classes and stay beside the launcher that uses them. Moving any of these would
require widening visibility.

The package inventory below is generated from the `package` declarations in
`workspace/src` and `mcp/src`.

## Package cycles

`WorkspacePackageArchitectureTest` computes the package import graph of
`workspace/src/main` and compares its cycles with an explicit allow-list: one
group of packages that reach each other, and the pairs among them that import
each other directly. A new cycle, or a package joining the group, fails the
build; removing a cycle means deleting its entry from the test. The remaining
cycles all run through the application facades (`ProjectApplicationService`,
the provider binding and collaboration services, and the readiness service),
which call each other at runtime; breaking them needs a change of those service
contracts rather than a class move.

`lifecycle.process` groups the shared Git command runner with the semantic
index fingerprint. The fingerprint uses a package-private bounded-output
overload, keeping that inspection option internal to the process package.

Cleanup inventory, path verification and the cleanup reason codes live under
`lifecycle.cleanup.inventory`;
eligibility and persisted plans live under `lifecycle.cleanup.plan`; and locks,
journals, quarantine, and execution live under `lifecycle.cleanup.execution`.
The cleanup root retains only cross-stage integration tests.

## Workspace source package map

The lists below show every top-level Java type under its declared package. Most source files contain one top-level type; refresh this section when package ownership changes.

### Production

- `org.synesis.workspace.agent`: `AgentCapabilityResult`, `AgentMutationResult`, `AgentNextAction`, `AgentReason`, `AgentResponse`, `AgentStatus`, `AgentStatusResult`, `AgentWorkspaceGuidance`
- `org.synesis.workspace.application`: `ProjectApplicationService`
- `org.synesis.workspace.application.agent.completion`: `AgentTaskCancellationService`, `CompleteTaskRequest`, `CompletionOutcome`
- `org.synesis.workspace.application.agent.run`: `AgentRunController`, `ControllerConnections`, `EventLogObserver`, `ProviderAgentProcesses`
- `org.synesis.workspace.application.agent.session`: `AgentSessionContext`, `AgentSessionFailure`, `AgentSessionService`, `AgentTaskIntent`, `CallerAdmission`, `SessionResolutionRequest`
- `org.synesis.workspace.application.agent.workflow`: `AgentNextActionService`, `AgentReviewActionProjection`, `AgentTaskCompletionService`, `AgentWorkflowReducer`, `CapabilityActionProjection`, `CapabilityActor`, `CollaborationResponseMapper`, `CollaborationView`, `LaneCompletionProjection`, `LaneScope`, `LocalCoordinationFallback`, `NextActionKeys`, `NextActionProtocol`, `NextActionRequest`, `NextActionSupport`, `NoChangeCompletionEligibility`, `Publishability`, `RecoveryContinuationProjection`, `ReviewLaneProjection`
- `org.synesis.workspace.application.capability`: `CapabilityRequestService`, `CapabilityResponseService`
- `org.synesis.workspace.application.collaboration`: `CollaborationSnapshot`, `ContractSnapshot`, `LaneIdentities`, `MutationAdmission`, `NoChangeLaneCompletion`, `RecoveryLaneContinuation`, `ReviewSnapshotAccessService`, `ReviewValidationService`, `WorkIntentAnnouncement`, `WorkspaceCollaborationService`
- `org.synesis.workspace.application.constraint`: `ConstraintApplicationService`
- `org.synesis.workspace.application.control`: `ControlBranchAdvancementService`
- `org.synesis.workspace.application.guardrail`: `ActionCheckResult`, `GuardrailApplicationService`
- `org.synesis.workspace.application.hook`: `CodexNativePatchRouter`, `HookApplicationService`
- `org.synesis.workspace.application.integration`: `CapabilityValidationSnapshotAccessService`, `ExternalWorktrees`, `ImplementationPublicationService`, `ImplementationValidationService`, `IntegrationCandidatePolicy`, `IntegrationCompatibilityService`, `IntegrationOrchestrationService`, `IntegrationWorkspaceService`, `ValidationWorkspaceService`, `WorkspaceIntegrationReadinessService`
- `org.synesis.workspace.application.project`: `GitRepositoryFacts`, `ManagedAgentsSection`, `ProjectCommandAdmissionService`, `ProjectCommandService`, `ProjectCreateResult`, `ProjectIdentity`, `ProjectInitResult`, `ProjectInitStatus`, `ProjectLocation`, `ProjectMetadataCodec`
- `org.synesis.workspace.application.project.execution`: `ExecutionRequest`, `ExecutionResult`, `ProjectCommandSpec`, `ProjectProcessExecutor`, `StartedProcessIdentity`
- `org.synesis.workspace.application.provider`: `ProviderApplicationService`, `ProviderBindingFile`, `ProviderBindingStatus`, `ProviderConfigurationFiles`, `ProviderHealthProbes`, `ProviderHookConfiguration`, `ProviderLauncherResolution`, `ProviderManualService`, `ProviderMcpConfigurationService`, `ProviderMetadata`, `ProviderProjectInstructions`, `ProviderRegistry`, `ProviderResults`, `ProviderSessionBindingService`, `ProviderSessionCoordinationFacts`, `ProviderWorkspaceRefresh`, `ProviderWorkspaceVerification`, `ProviderWorktreeAllocation`, `ProviderWorktreeGitFacts`
- `org.synesis.workspace.application.provider.continuity`: `ManagedAttachmentRecord`, `ManagedAttachmentService`, `ManagedAttachmentStore`, `ManagedRuntimeDeathReceipt`, `ManagedRuntimeDeathReceiptStore`, `ProviderContinuityMode`, `ProviderThreadOwnershipRecord`, `ProviderThreadOwnershipStore`, `RuntimeAuthenticator`
- `org.synesis.workspace.application.provider.session`: `ProviderSessionTerminalizationService`, `SessionAuthorityResolver`
- `org.synesis.workspace.application.sync`: `ProjectContextExchange`, `SyncApplicationService`
- `org.synesis.workspace.application.task`: `PublishableChangeDetection`, `SnapshotArtifactPolicy`, `SnapshotGitCommands`, `SnapshotRefLifecycle`, `SnapshotRefs`, `SnapshotRequest`, `SnapshotTreeMaterialization`, `TaskSnapshotService`, `WorktreeChangeInspection`
- `org.synesis.workspace.application.workspace`: `ReadinessResult`, `WorkspacePathPolicy`, `WorkspaceReadinessService`
- `org.synesis.workspace.application.workspace.mutation`: `AgentOutcomeTranslator`, `PatchEdit`, `PatchRequest`, `TranslatedOutcome`, `WorkspaceMutationBroker`, `WorkspacePatchService`
- `org.synesis.workspace.application.workspace.read`: `ReadRequest`, `WorkspaceReadService`
- `org.synesis.workspace.discovery`: `KnownProjectRegistry`
- `org.synesis.workspace.doctor`: `DoctorConfidence`, `DoctorFinding`, `DoctorFindingCode`, `DoctorRecommendation`, `DoctorRenderer`, `DoctorReport`, `DoctorService`, `DoctorSeverity`, `DoctorStatus`
- `org.synesis.workspace.infrastructure.digest`: `Sha256`
- `org.synesis.workspace.infrastructure.filesystem`: `AtomicFiles`, `BoundedFileLock`, `ProjectMetadataStorage`, `TextFileDocument`
- `org.synesis.workspace.infrastructure.json`: `JsonFields`, `ProviderJson`
- `org.synesis.workspace.infrastructure.process`: `ProcessEvidenceState`, `ProcessInspector`
- `org.synesis.workspace.lifecycle`: `AdministrativeJournal`, `AdministrativePlanFiles`, `AdministrativeStateLocator`, `BaselineJournalStore`, `ManagedBaselineTransactionService`, `ManagedPathPolicy`, `ProjectAdministrativeLayout`, `RepositoryPortabilityService`, `RepositoryPrivateStateService`
- `org.synesis.workspace.lifecycle.cleanup.execution`: `CleanupEntryExecutionState`, `CleanupExecutionJournal`, `CleanupExecutionLock`, `CleanupExecutionRecord`, `CleanupExecutionService`, `LifecycleQuarantineService`
- `org.synesis.workspace.lifecycle.cleanup.inventory`: `CleanupReason`, `LifecycleInventoryService`, `LifecyclePathVerifier`, `LifecycleResourceFingerprint`, `LifecycleResourceType`
- `org.synesis.workspace.lifecycle.cleanup.plan`: `CleanupClassification`, `CleanupEligibilityService`, `CleanupPlan`, `CleanupPlanEntry`, `CleanupPlanService`, `CleanupPlanStore`, `PersistedCleanupPlan`, `PersistedCleanupPlanEntry`, `RetentionPolicy`
- `org.synesis.workspace.lifecycle.codex`: `CodexAppServerDirectLaunch`, `CodexAppServerEventListener`, `CodexAppServerLifecycleService`, `CodexAppServerPayloadReader`, `CodexAppServerProcessStreams`, `CodexAppServerProcessTermination`, `CodexLifecycleHttpAdapter`, `CodexWakeAdmissionService`, `CodexWakeCoordinator`, `LifecycleAuthorityVerifier`, `ManagedCodexProcessLauncher`, `ManagedProcessTreeSupervisor`, `ProcessTreeTerminator`, `ProjectRuntimeHost`, `WindowsJobObjectProcessTreeSupervisor`
- `org.synesis.workspace.lifecycle.codex.protocol`: `BoundedProtocolFrameReader`, `CodexAppServerProtocolClient`, `CodexAppServerProtocolSchema`, `CodexEvidenceJournal`, `CodexEvidenceRetention`, `LifecycleControlRequestEnvelope`, `LifecycleControlResponse`
- `org.synesis.workspace.lifecycle.codex.runtime`: `CodexManagedAuthentication`, `CodexManagedRuntimeHome`, `ManagedCodexRuntimeMode`, `ManagedCodexThreadBroker`
- `org.synesis.workspace.lifecycle.codex.state`: `CodexLifecycleStateStore`, `LifecycleIdempotencyLedger`
- `org.synesis.workspace.lifecycle.command`: `CommandDurableFormat`, `CommandFormatException`, `CommandPermanentLock`, `PhysicalWorktreeIdentity`, `ProjectCommandAuthoritySnapshot`, `ProjectCommandCanonicalizer`, `ProjectCommandDiagnostics`, `ProjectCommandMaintenanceService`, `ProjectCommandNamespace`, `ProjectCommandPhase`, `ProjectCommandProcessAnchor`, `ProjectCommandProtectionService`, `ProjectCommandRecord`, `ProjectCommandStore`, `ProjectCommandTerminalResolution`
- `org.synesis.workspace.lifecycle.lease`: `SessionLeasePolicy`, `SessionLeaseRecord`, `SessionLeaseService`, `SessionLeaseState`, `SessionLeaseStore`, `SessionProcessIdentity`
- `org.synesis.workspace.lifecycle.process`: `GitProcessRunner`, `ProcessCommandRunner`, `SemanticIndexFingerprint`
- `org.synesis.workspace.lifecycle.reconciliation`: `ReconciliationAction`, `ReconciliationExecutionJournal`, `ReconciliationExecutionLock`, `ReconciliationExecutionRecord`, `ReconciliationPlan`, `ReconciliationPlanEntry`, `ReconciliationPlanStore`, `ReconciliationService`
- `org.synesis.workspace.lifecycle.recovery`: `RecoverySnapshotService`
- `org.synesis.workspace.lifecycle.repair`: `RepairAction`, `RepairBackupService`, `RepairExecutionJournal`, `RepairExecutionLock`, `RepairPlan`, `RepairPlanEntry`, `RepairPlanStore`, `RepairService`
- `org.synesis.workspace.migration`: `MigrationLayout`, `PostMigrationReplayVerifier`, `ProjectMigrationRestorationService`, `ProjectMigrationService`, `ProviderConfigMigrationService`
- `org.synesis.workspace.project`: `ActionGuardrail`, `ProjectPathResolver`
- `org.synesis.workspace.provider`: `ProviderIntegration`, `ProviderMcpEvidenceTier`, `ProviderSupportLevel`
- `org.synesis.workspace.provider.claude`: `ClaudeCodeHookAdapter`, `ClaudeCodeProviderIntegration`
- `org.synesis.workspace.provider.codex`: `CodexApplyPatchParser`, `CodexHookAdapter`, `CodexProviderIntegration`, `CodexTomlConfiguration`
- `org.synesis.workspace.transport.control`: `ApiFailure`, `ControlPlaneEventHub`, `ControlPlaneEventStream`, `ControlPlaneHttpHandler`, `ControlPlaneHttpJson`, `ControlPlaneLinkOperations`, `ControlPlaneOnboardingEndpoints`, `ControlPlaneProjectEndpoints`, `ControlPlaneReadModel`, `ControlPlaneRequestBoundary`, `ControlPlaneSessions`, `Event`, `LinkRuntimeOwner`
- `org.synesis.workspace.transport.control.network`: `LinkNetworkProjection`, `LinkState`, `NetworkPeer`, `NetworkRoute`, `NetworkSnapshot`, `OverlayEdge`, `OverlayMember`, `OverlaySummary`, `RelayState`

### Tests

- `org.synesis.workspace`: `AgentNextActionServiceTest`, `CapabilityNegotiationTest`, `CodexHookOwnershipTest`, `MultiChatLogicalWorkspaceTest`, `ProjectCommandServiceTest`, `ProviderApplicationServiceTest`, `ProviderSessionBindingServiceTest`, `RepositoryPrivateStateServiceTest`, `Syn037CompletionValidationTest`, `TaskIntegrationServiceTest`, `WorkspaceMutationBrokerTest`, `WorkspacePatchServiceTest`, `WorkspaceReadServiceTest`
- `org.synesis.workspace.agent`: `AgentResponseTest`, `AgentWorkflowReducerTest`
- `org.synesis.workspace.application`: `ProjectApplicationServiceTest`, `ProjectReplicaProcessTest`
- `org.synesis.workspace.application.agent.completion`: `TaskCancellationTest`
- `org.synesis.workspace.application.agent.run`: `AgentRunControllerTest`, `ControllerConnectionsTest`, `HeadlessSessionContractTest`
- `org.synesis.workspace.application.agent.session`: `AgentSessionServiceTest`
- `org.synesis.workspace.application.agent.workflow`: `AgentTaskCompletionReviewPolicyTest`, `DerivedWorkflowIdentifierTest`, `ReviewAdmissionOrderIndependenceTest`, `SessionTerminalStateUnavailableTest`
- `org.synesis.workspace.application.capability`: `DerivedCapabilityIdentifierTest`
- `org.synesis.workspace.application.collaboration`: `DerivedIdentifierTest`
- `org.synesis.workspace.application.control`: `ControlBranchAdvancementServiceTest`
- `org.synesis.workspace.application.hook`: `CodexNativePatchRouterTest`
- `org.synesis.workspace.application.integration`: `BlockedAttemptIdentifierTest`, `ImplementationPublicationServiceTest`, `IntegrationCompatibilityServiceTest`, `IntegrationOrchestrationServiceTest`, `IntegrationQueueTest`, `ValidatedContentCarriedTest`, `ValidationWorkspaceTest`
- `org.synesis.workspace.application.project`: `ProjectLocationTest`
- `org.synesis.workspace.application.project.execution`: `ProjectProcessExecutorTest`
- `org.synesis.workspace.application.provider`: `ProviderBindingFileFormatTest`, `ProviderHookConfigurationFormatTest`, `ProviderLauncherSelectionTest`, `ProviderManagedFilesFormatTest`, `ProviderManualServiceTest`, `ProviderSessionTerminalizationServiceTest`
- `org.synesis.workspace.application.provider.continuity`: `ManagedAttachmentServiceTest`, `ProviderContinuityRecordFormatTest`, `ProviderThreadOwnershipStoreTest`
- `org.synesis.workspace.application.sync`: `ProjectContextExchangeTest`
- `org.synesis.workspace.application.task`: `SnapshotArtifactPolicyTest`
- `org.synesis.workspace.application.workspace`: `WorkspacePathPolicyTest`
- `org.synesis.workspace.application.workspace.mutation`: `AgentOutcomeTranslatorTest`
- `org.synesis.workspace.architecture`: `WorkspaceApplicationPackageArchitectureTest`, `WorkspacePackageArchitectureTest`
- `org.synesis.workspace.discovery`: `KnownProjectRegistryTest`
- `org.synesis.workspace.doctor`: `DoctorFindingFormatTest`, `DoctorJournalClassificationTest`, `DoctorServiceTest`
- `org.synesis.workspace.infrastructure.digest`: `Sha256Test`
- `org.synesis.workspace.infrastructure.filesystem`: `BoundedFileLockTest`, `ProjectMetadataStorageTest`, `TextFileDocumentTest`
- `org.synesis.workspace.infrastructure.json`: `ProviderJsonTest`
- `org.synesis.workspace.infrastructure.process`: `ProcessInspectorTest`
- `org.synesis.workspace.lifecycle`: `AdministrativeStateLocatorTest`, `LifecycleExecutionJournalFormatTest`, `LifecyclePlanStoreFormatTest`, `ManagedBaselineTransactionServiceTest`, `ManagedPathPolicyTest`, `RepositoryPortabilityServiceTest`
- `org.synesis.workspace.lifecycle.cleanup`: `NoForceAndSafetyArchitectureTest`, `ValidationAndIntegrationCleanupTest`
- `org.synesis.workspace.lifecycle.cleanup.execution`: `JournalAndRestartTest`, `LockAndConcurrencyTest`, `QuarantineTest`, `TemporaryFileCleanupTest`, `WorkerCleanupTest`
- `org.synesis.workspace.lifecycle.cleanup.inventory`: `LifecyclePathVerifierTest`
- `org.synesis.workspace.lifecycle.cleanup.plan`: `CleanupEligibilityServiceTest`, `PlanPersistenceTest`, `StalePlanTest`
- `org.synesis.workspace.lifecycle.codex`: `CodexLifecycleWaitControlTest`, `CodexWakeAdmissionServiceTest`, `CodexWakeCoordinatorTest`, `ManagedCodexProcessLauncherTest`, `ProcessTreeTerminatorTest`, `ProjectRuntimeHostWakeTest`, `WindowsJobObjectProcessTreeSupervisorTest`
- `org.synesis.workspace.lifecycle.codex.protocol`: `BoundedProtocolFrameReaderTest`, `CodexAppServerProtocolClientTest`, `CodexAppServerProtocolSchemaTest`, `CodexEvidenceJournalTest`, `LifecycleControlRequestEnvelopeFormatTest`, `LifecycleControlRequestEnvelopeTest`
- `org.synesis.workspace.lifecycle.codex.runtime`: `CodexManagedRuntimeHomeTest`, `ManagedCodexThreadBrokerTest`
- `org.synesis.workspace.lifecycle.codex.state`: `CodexLifecycleStateFormatTest`, `LifecycleIdempotencyLedgerTest`
- `org.synesis.workspace.lifecycle.command`: `CommandCanonicalFormTest`, `CommandLockProbe`, `ProjectCommandNamespaceSpikeTest`, `ProjectCommandStoreFormatTest`
- `org.synesis.workspace.lifecycle.lease`: `LeaseTest`, `SessionLeaseStoreFormatTest`, `StaleAndGraceTest`, `TerminalLeaseStateTest`
- `org.synesis.workspace.lifecycle.process`: `ProcessCommandRunnerChild`, `ProcessCommandRunnerTest`, `SemanticIndexFingerprintTest`
- `org.synesis.workspace.lifecycle.reconciliation`: `AbandonmentTest`, `ReconciliationExecutionLockTest`, `ReconciliationPlanTest`, `RecoveryLifecycleTest`
- `org.synesis.workspace.lifecycle.recovery`: `RecoverySnapshotServiceTest`
- `org.synesis.workspace.lifecycle.repair`: `RepairExecutionTest`, `RepairPlanTest`
- `org.synesis.workspace.migration`: `PostMigrationReplayVerifierTest`, `ProjectMigrationRestorationServiceTest`, `ProjectMigrationServiceTest`, `ProviderConfigMigrationServiceTest`
- `org.synesis.workspace.provider.claude`: `ClaudeCodeHookAdapterTest`
- `org.synesis.workspace.provider.codex`: `CodexApplyPatchParserTest`, `CodexHookAdapterTest`, `CodexHookPerformanceTest`
- `org.synesis.workspace.test`: `PortableTestCommand`, `PortableTestProcess`, `ProviderTestSupport`, `TestGit`
- `org.synesis.workspace.transport.control`: `ControlPlaneHttpHandlerTest`, `ControlPlaneReadModelTest`, `LinkNetworkProjectionTest`, `LinkRuntimeOwnerControlPlaneTest`

## MCP source package map

MCP keeps a root server entrypoint, connection-scoped application logic, and an independent stdio transport. `McpProtocolHandler` owns the persistent connection binding, lease state, JSON-RPC lifecycle, and authority-gated tool dispatch. `McpProjectRootAuthority` isolates root verification; `McpToolCall` validates the untrusted tools/call envelope; and `McpCollaborationProjection` serializes typed collaboration records at the JSON boundary.

### Production

- `org.synesis.mcp`: `SynesisMcpServer`
- `org.synesis.mcp.application`: `McpCollaborationProjection`, `McpCoordinationPayload`, `McpCoordinationRequest`, `McpCoordinationResponse`, `McpCoordinationTools`, `McpDurableCommand`, `McpIntegrationCheckEvidence`, `McpJsonRpcResponses`, `McpManagedAdmission`, `McpProjectRootAuthority`, `McpProjectRootSelection`, `McpProtocolHandler`, `McpRecoveryReconciliation`, `McpRootCandidates`, `McpSessionEstablishment`, `McpSessionLease`, `McpTaskArguments`, `McpToolArguments`, `McpToolCall`, `McpWorkspaceTools`
- `org.synesis.mcp.transport.stdio`: `McpFrameReader`, `McpStdioServer`

### Tests

- `org.synesis.mcp`: `SynesisMcpServerTest`
- `org.synesis.mcp.application`: `CodexLinkedWorktreeRootSelectionRegressionTest`, `McpAdmissionRecoveryTest`, `McpCleanTransportRecoveryTest`, `McpMandatoryReviewPolicyTest`, `McpPackageArchitectureTest`, `McpProviderTestSupport`, `McpRecoveryContinuationTest`, `McpServerTest`, `McpStage2BSlice1Test`, `McpSyn028ContractTest`, `McpSyn039NoChangeCompletionTest`, `McpSyn039RejectedContinuationTest`, `McpSyn039SliceTest`, `McpToolCallTest`, `McpToolCatalogTest`, `McpToolMalformedArgumentsTest`, `Slice4FailureScenariosTest`, `SyntheticTwoProcessCollaborationTest`, `TwoProcessCapabilityNegotiationProcessTest`
- `org.synesis.mcp.transport.stdio`: `McpFrameReaderTest`
