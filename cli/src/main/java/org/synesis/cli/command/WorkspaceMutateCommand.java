package org.synesis.cli.command;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;
import org.synesis.workspace.application.WorkspaceMutationBroker;
import org.synesis.workspace.application.WorkspaceMutationBroker.Decision;
import org.synesis.workspace.application.WorkspaceMutationBroker.MutationRequest;
import org.synesis.workspace.application.WorkspaceMutationBroker.MutationResult;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-level agent-facing workspace mutation command.
 */
@Command(name = "mutate", description = "Applies an evaluated workspace mutation through Synesis broker.", mixinStandardHelpOptions = true)
public final class WorkspaceMutateCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = {"--project"}, description = "Project root directory")
    private Path project;

    @Option(names = {"--provider"}, description = "Provider identifier", defaultValue = "codex")
    private String provider;

    @Option(names = {"--session"}, description = "Session ID")
    private String session;

    @Option(names = {"--target"}, description = "Target repository-relative path", required = true)
    private String target;

    @Option(names = {"--kind"}, description = "Mutation kind/tool name", defaultValue = "write_file")
    private String kind;

    @Option(names = {"--previous-hash"}, description = "Expected previous content SHA-256 hash")
    private String previousHash;

    @Option(names = {"--create-only"}, description = "Require target file to not exist prior to write")
    private boolean createOnly;

    @Option(names = {"--content"}, description = "New file content")
    private String content;

    @Option(names = {"--idempotency-key"}, description = "Idempotency key")
    private String idempotencyKey;

    @Option(names = {"--output"}, description = "Output format (default, agent)", defaultValue = "default")
    private String outputMode;

    /**
     * Creates the workspace mutate command.
     *
     * @param runtime CLI runtime
     */
    public WorkspaceMutateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(bytes));
    }

    @Override
    public Integer call() {
        try {
            if (target == null || target.isBlank() || Path.of(target)
                    .isAbsolute() || target.startsWith("/") || target.startsWith("\\")) {
                emitError("FAILED", "INVALID_TARGET", "Arbitrary absolute write paths are rejected", null, null, null);
                return ExitCodes.USAGE;
            }

            Path root = project == null ? Path.of(".") : project;
            ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(root);

            ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
            var bindings = bindingService.list(location, provider);
            if (bindings.isEmpty()) {
                emitError("FAILED",
                        "SESSION_UNBOUND",
                        "No bound session for provider: " + provider,
                        location.projectId()
                                .toString(),
                        null,
                        null);
                return ExitCodes.USAGE;
            }

            ProviderSessionBindingService.Binding binding = (session != null && !session.isBlank())
                    ? bindings.stream()
                      .filter(b -> session.equals(b.sessionId()))
                      .findFirst()
                      .orElse(null)
                    : bindings.getLast();

            if (binding == null || !"BOUND".equals(binding.status())) {
                emitError("FAILED",
                        "SESSION_UNBOUND",
                        "Provider session is not BOUND",
                        location.projectId()
                                .toString(),
                        session,
                        null);
                return ExitCodes.USAGE;
            }

            if (binding.worktreePath() == null || binding.worktreePath()
                    .isBlank()) {
                emitError("FAILED",
                        "WORKSPACE_UNVERIFIED",
                        "Assigned worktree is UNASSIGNED",
                        location.projectId()
                                .toString(),
                        binding.sessionId(),
                        null);
                return ExitCodes.USAGE;
            }

            Path assignedWorktree = Path.of(binding.worktreePath())
                    .toAbsolutePath()
                    .normalize();

            // Handle idempotency key caching
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Path idempotencyFile = location.synesisDirectory()
                        .resolve("local")
                        .resolve("evidence")
                        .resolve(provider)
                        .resolve("idempotency-" + idempotencyKey + ".json");
                if (Files.isRegularFile(idempotencyFile)) {
                    String cachedJson = Files.readString(idempotencyFile, StandardCharsets.UTF_8);
                    runtime.terminal()
                            .stdout(cachedJson.trim());
                    return ExitCodes.OK;
                }
            }

            // Verify previous hash condition
            Path targetFile = assignedWorktree.resolve(target)
                    .toAbsolutePath()
                    .normalize();
            if (previousHash != null && !previousHash.isBlank()) {
                if (!Files.exists(targetFile)) {
                    emitError("DENIED",
                            "HASH_MISMATCH",
                            "Previous content hash mismatch: target file does not exist",
                            location.projectId()
                                    .toString(),
                            binding.sessionId(),
                            binding.worktreePath());
                    return 1;
                }
                String actualHash = sha256(Files.readAllBytes(targetFile));
                if (!previousHash.equalsIgnoreCase(actualHash)) {
                    emitError("DENIED",
                            "HASH_MISMATCH",
                            "Previous content hash mismatch",
                            location.projectId()
                                    .toString(),
                            binding.sessionId(),
                            binding.worktreePath());
                    return 1;
                }
            }

            // Verify create-only condition
            if (createOnly && Files.exists(targetFile)) {
                emitError("DENIED",
                        "FILE_ALREADY_EXISTS",
                        "Create-only condition failed: file already exists",
                        location.projectId()
                                .toString(),
                        binding.sessionId(),
                        binding.worktreePath());
                return 1;
            }

            String fileContent = content == null ? "" : content;

            // Apply mutation via WorkspaceMutationBroker
            WorkspaceMutationBroker broker = new WorkspaceMutationBroker();
            MutationRequest req = new MutationRequest(location, provider, target, kind, fileContent, true, false);
            MutationResult res = broker.applyMutation(req);

            String resultingHash = null;
            if (res.success() && res.mutatedPath() != null && Files.exists(res.mutatedPath())) {
                resultingHash = sha256(Files.readAllBytes(res.mutatedPath()));
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("RESULT", res.success() ? "SUCCESS" : "DENIED");
            output.put("PROJECT_ID",
                    location.projectId()
                            .toString());
            output.put("SESSION_ID", binding.sessionId());
            output.put("ASSIGNED_WORKTREE", binding.worktreePath());
            output.put("WORKSPACE_TRUST", binding.providerTrustState());
            output.put("TARGET", target);
            output.put("HOOK_INTERCEPTED", true);
            output.put("DECISION",
                    res.decision()
                            .name());
            output.put("DECISION_REASON_CODE", res.reasonCode());
            output.put("DECISION_MESSAGE", res.message());
            output.put("DECISION_RECORD_ID", res.decisionId());
            output.put("INTERCEPTION_EVIDENCE_SHA256", res.interceptionEvidence());
            output.put("RESULTING_FILE_SHA256", resultingHash);
            output.put("MUTATION_APPLIED", res.success());

            String jsonOutput = ProviderJson.write(output);
            if ("agent".equalsIgnoreCase(outputMode)) {
                org.synesis.workspace.application.AgentOutcomeTranslator translator = new org.synesis.workspace.application.AgentOutcomeTranslator();
                org.synesis.workspace.application.TranslatedOutcome translated = translator.translateMutationResult(res, target);
                runtime.terminal()
                        .stdout(translated.publicResponse()
                                .toJson()
                                .trim());
            } else {
                runtime.terminal()
                        .stdout(jsonOutput.trim());
            }

            // Save idempotency cache if key provided
            if (idempotencyKey != null && !idempotencyKey.isBlank() && res.success()) {
                Path idempotencyFile = location.synesisDirectory()
                        .resolve("local")
                        .resolve("evidence")
                        .resolve(provider)
                        .resolve("idempotency-" + idempotencyKey + ".json");
                Files.createDirectories(idempotencyFile.getParent());
                Files.writeString(idempotencyFile, jsonOutput + System.lineSeparator(), StandardCharsets.UTF_8);
            }

            if (res.success()) {
                return ExitCodes.OK;
            } else if (res.decision() == Decision.DENY_POLICY || res.decision() == Decision.REQUEST_OWNER
                    || res.decision() == Decision.STALE_CONTEXT || res.decision() == Decision.WORKSPACE_UNVERIFIED
                    || res.decision() == Decision.ACTOR_NOT_AUTHORIZED) {
                return 1;
            } else if (res.decision() == Decision.INVALID_TARGET || res.decision() == Decision.INTERCEPTION_MISSING
                    || res.decision() == Decision.SESSION_UNBOUND) {
                return ExitCodes.USAGE;
            } else {
                return ExitCodes.INTERNAL;
            }
        } catch (Exception failure) {
            emitError("FAILED", "INTERNAL_ERROR", failure.getMessage(), null, null, null);
            return ExitCodes.INTERNAL;
        }
    }

    private void emitError(String result,
            String reasonCode,
            String message,
            String projectId,
            String sessionId,
            String worktree) {
        if ("agent".equalsIgnoreCase(outputMode)) {
            org.synesis.workspace.application.AgentOutcomeTranslator translator = new org.synesis.workspace.application.AgentOutcomeTranslator();
            org.synesis.workspace.application.TranslatedOutcome translated = translator.translateException(
                    new IllegalArgumentException(message != null ? message : reasonCode));
            runtime.terminal()
                    .stdout(translated.publicResponse()
                            .toJson()
                            .trim());
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("RESULT", result);
        output.put("PROJECT_ID", projectId);
        output.put("SESSION_ID", sessionId);
        output.put("ASSIGNED_WORKTREE", worktree);
        output.put("WORKSPACE_TRUST", "WORKSPACE_UNVERIFIED");
        output.put("TARGET", target);
        output.put("HOOK_INTERCEPTED", false);
        output.put("DECISION", "UNKNOWN");
        output.put("DECISION_REASON_CODE", reasonCode);
        output.put("DECISION_MESSAGE", message);
        output.put("DECISION_RECORD_ID", null);
        output.put("INTERCEPTION_EVIDENCE_SHA256", null);
        output.put("RESULTING_FILE_SHA256", null);
        output.put("MUTATION_APPLIED", false);
        runtime.terminal()
                .stdout(ProviderJson.write(output));
    }
}
