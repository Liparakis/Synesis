package org.synesis.cli.command.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.workspace.mutation.AgentOutcomeTranslator;
import org.synesis.workspace.application.workspace.mutation.TranslatedOutcome;
import org.synesis.workspace.application.workspace.mutation.WorkspaceMutationBroker;
import org.synesis.workspace.application.workspace.mutation.WorkspaceMutationBroker.Decision;
import org.synesis.workspace.application.workspace.mutation.WorkspaceMutationBroker.MutationRequest;
import org.synesis.workspace.application.workspace.mutation.WorkspaceMutationBroker.MutationResult;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.filesystem.BoundedFileLock;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Applies one exact-session workspace mutation through the shared Synesis broker.
 */
@Command(name = "mutate", description = "Applies an evaluated workspace mutation through Synesis broker.", mixinStandardHelpOptions = true)
public final class WorkspaceMutateCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = {"--project"}, description = "Project root directory")
    private Path project;

    @Option(names = {"--provider"}, description = "Provider identifier", defaultValue = "codex")
    private String provider;

    @Option(names = {"--session"}, description = "Exact provider session ID", required = true)
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

    private static String normalizeOutputMode(String value) {
        if (value == null) {
            return "default";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("default") || normalized.equals("agent") ? normalized : null;
    }

    private static boolean validRelativeArgument(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")) {
            return false;
        }
        try {
            return !Path.of(value).isAbsolute();
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    private static String normalizeHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("(?i)[0-9a-f]{64}") ? normalized : null;
    }

    @Override
    public Integer call() {
        ProjectLocation location = null;
        try {
            String normalizedOutput = normalizeOutputMode(outputMode);
            if (normalizedOutput == null) {
                return usageError("INVALID_OUTPUT_MODE", "Output mode must be default or agent", null, null);
            }
            outputMode = normalizedOutput;
            if (!validRelativeArgument(target)) {
                return usageError("INVALID_TARGET", "A repository-relative target path is required", null, null);
            }
            String expectedHash = normalizeHash(previousHash);
            if (previousHash != null && !previousHash.isBlank() && expectedHash == null) {
                return usageError("INVALID_PREVIOUS_HASH", "Previous content hash must be SHA-256", null, null);
            }
            if (createOnly && expectedHash != null) {
                return usageError("CONFLICTING_PRECONDITIONS", "Create-only and previous-hash conditions cannot be combined", null, null);
            }

            Path root = project == null ? Path.of(".") : project;
            location = runtime.projectService().locate(root);
            List<Binding> bindings = new ProviderSessionBindingService().list(location, provider);
            if (bindings.isEmpty()) {
                return usageError("SESSION_UNBOUND", "No provider session is bound for: " + provider, location.projectId().toString(), null);
            }
            if (session == null || session.isBlank()) {
                return usageError("SESSION_REQUIRED", "An exact --session is required for mutation", location.projectId().toString(), null);
            }
            Binding binding = bindings.stream().filter(candidate -> session.equals(candidate.sessionId())).findFirst().orElse(null);
            if (binding == null || !"BOUND".equals(binding.status())) {
                return usageError("SESSION_UNBOUND", "The selected provider session is not BOUND", location.projectId().toString(), session);
            }
            if (binding.worktreePath() == null || binding.worktreePath().isBlank()) {
                return usageError("WORKSPACE_UNVERIFIED", "The selected session has no assigned worktree", location.projectId().toString(), binding.sessionId());
            }

            String effectiveContent = content == null ? "" : content;
            String fingerprint = requestFingerprint(location, binding, expectedHash, effectiveContent);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                return applyMutation(location, binding, expectedHash, effectiveContent, fingerprint, null);
            }

            Path evidenceDirectory = location.synesisDirectory().resolve("local").resolve("evidence").resolve(provider);
            Files.createDirectories(evidenceDirectory);
            IdempotencyCache cache = IdempotencyCache.of(evidenceDirectory, idempotencyKey);
            BoundedFileLock lock = cache.lock();
            try (lock) {
                return applyMutation(location, binding, expectedHash, effectiveContent, fingerprint, cache);
            }
        } catch (java.nio.file.InvalidPathException invalidPath) {
            return usageError("INVALID_PATH", "Project or target path is invalid", location == null ? null : location.projectId().toString(), session);
        } catch (ProjectApplicationService.ProjectApplicationException invalidProject) {
            return configurationError(invalidProject.code(), location);
        } catch (ProviderSessionBindingService.BindingException invalidSession) {
            return configurationError(invalidSession.code(), location);
        } catch (IOException localStorageFailure) {
            return configurationError("LOCAL_STORAGE_FAILED", location);
        } catch (Exception failure) {
            emitError("INTERNAL_ERROR", "Unexpected mutation failure", location == null ? null : location.projectId().toString(), session);
            return ExitCodes.INTERNAL;
        }
    }

    private int applyMutation(ProjectLocation location, Binding binding, String expectedHash, String effectiveContent, String fingerprint, IdempotencyCache cache) throws IOException {
        Optional<IdempotencyCache.Cached> cached = cache == null ? Optional.empty() : cache.read();
        if (cached.isPresent()) {
            if (!fingerprint.equals(cached.get().requestFingerprint())) {
                return usageError("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for a different request", location.projectId().toString(), binding.sessionId());
            }
            runtime.terminal().stdout(cached.get().response());
            return ExitCodes.OK;
        }

        MutationRequest request = new MutationRequest(location, provider, binding.sessionId(), target, kind, effectiveContent, true, false, expectedHash, createOnly);
        MutationResult result = new WorkspaceMutationBroker().applyMutation(request);
        String response = formatResult(result, location, binding);
        if (result.success() && cache != null) {
            try {
                cache.write(fingerprint, response);
            } catch (IOException cacheFailure) {
                runtime.terminal().stderr("IDEMPOTENCY_CACHE=WRITE_FAILED MUTATION_APPLIED=true");
                runtime.terminal().stdout(response);
                return ExitCodes.LOCAL_CONFIGURATION;
            }
        }
        runtime.terminal().stdout(response);
        return exitCode(result);
    }

    private String formatResult(MutationResult result, ProjectLocation location, Binding binding) {
        if ("agent".equals(outputMode)) {
            TranslatedOutcome translated = new AgentOutcomeTranslator().translateMutationResult(result, target);
            return translated.publicResponse().toJson().trim();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("RESULT", result.success() ? "SUCCESS" : "DENIED");
        output.put("PROJECT_ID", location.projectId().toString());
        output.put("SESSION_ID", binding.sessionId());
        output.put("ASSIGNED_WORKTREE", binding.worktreePath());
        output.put("WORKSPACE_TRUST", binding.providerTrustState());
        output.put("TARGET", target);
        output.put("HOOK_INTERCEPTED", true);
        output.put("DECISION", result.decision().name());
        output.put("DECISION_REASON_CODE", result.reasonCode());
        output.put("DECISION_MESSAGE", result.message());
        output.put("DECISION_RECORD_ID", result.decisionId());
        output.put("INTERCEPTION_EVIDENCE_SHA256", result.interceptionEvidence());
        output.put("RESULTING_FILE_SHA256", result.updatedRevision());
        output.put("MUTATION_APPLIED", result.success());
        return ProviderJson.write(output).trim();
    }

    private int exitCode(MutationResult result) {
        if (result.success()) {
            return ExitCodes.OK;
        }
        if (result.decision() == Decision.DENY_POLICY || result.decision() == Decision.REQUEST_OWNER || result.decision() == Decision.STALE_CONTEXT || result.decision() == Decision.WORKSPACE_UNVERIFIED
                || result.decision() == Decision.ACTOR_NOT_AUTHORIZED) {
            return ExitCodes.COMMAND_FAILED;
        }
        if (result.decision() == Decision.INVALID_TARGET || result.decision() == Decision.INTERCEPTION_MISSING || result.decision() == Decision.SESSION_UNBOUND) {
            return ExitCodes.USAGE;
        }
        return ExitCodes.INTERNAL;
    }

    private String requestFingerprint(ProjectLocation location, Binding binding, String expectedHash, String effectiveContent) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectId", location.projectId().toString());
        request.put("provider", provider);
        request.put("sessionId", binding.sessionId());
        request.put("bindingVersion", binding.bindingVersion());
        request.put("worktreePath", binding.worktreePath());
        request.put("workspaceTrust", binding.providerTrustState());
        request.put("target", target);
        request.put("kind", kind);
        request.put("content", effectiveContent);
        request.put("previousHash", expectedHash);
        request.put("createOnly", createOnly);
        request.put("outputMode", outputMode);
        return Sha256.hex(ProviderJson.write(request));
    }

    private int usageError(String reasonCode, String message, String projectId, String sessionId) {
        emitError(reasonCode, message, projectId, sessionId);
        return ExitCodes.USAGE;
    }

    private int configurationError(String reasonCode, ProjectLocation location) {
        emitError(reasonCode, "Local project or session state could not be read", location == null ? null : location.projectId().toString(), session);
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    private void emitError(String reasonCode, String message, String projectId, String sessionId) {
        if ("agent".equalsIgnoreCase(outputMode)) {
            TranslatedOutcome translated = new AgentOutcomeTranslator().translateException(new IllegalArgumentException(message != null ? message : reasonCode));
            runtime.terminal().stdout(translated.publicResponse().toJson().trim());
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("RESULT", "FAILED");
        output.put("PROJECT_ID", projectId);
        output.put("SESSION_ID", sessionId);
        output.put("ASSIGNED_WORKTREE", null);
        output.put("WORKSPACE_TRUST", "UNKNOWN");
        output.put("TARGET", target);
        output.put("HOOK_INTERCEPTED", false);
        output.put("DECISION", "UNKNOWN");
        output.put("DECISION_REASON_CODE", reasonCode);
        output.put("DECISION_MESSAGE", message);
        output.put("DECISION_RECORD_ID", null);
        output.put("INTERCEPTION_EVIDENCE_SHA256", null);
        output.put("RESULTING_FILE_SHA256", null);
        output.put("MUTATION_APPLIED", false);
        runtime.terminal().stdout(ProviderJson.write(output));
    }
}
