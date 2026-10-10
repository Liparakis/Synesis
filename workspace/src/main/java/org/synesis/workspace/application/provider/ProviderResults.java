package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService.ProviderResult;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderRegistry;

/**
 * Builds the {@link ProviderResult} values that install, status, and uninstall report, and adds the
 * session-binding and interception evidence to them. The field names and their insertion order are
 * the machine-readable output that callers and the CLI parse, so they are spelled in this one
 * class. It is separate from {@link ProviderApplicationService} so the service decides what
 * happened and this class alone decides how it is reported.
 */
final class ProviderResults {

    private ProviderResults() {
    }

    static ProviderResult decorate(ProjectLocation location, String provider, ProviderResult result, ProviderSessionBindingService.BindingResult ensured) {
        Map<String, String> values = new LinkedHashMap<>(result.values());
        try {
            var bindings = new ProviderSessionBindingService().list(location, provider);
            values.put("SESSION_BINDING", bindings.isEmpty() ? "UNBOUND" : bindings.stream().allMatch(binding -> binding.status().equals("REVOKED")) ? "REVOKED" : "BOUND");
            if (!bindings.isEmpty()) {
                var binding = ensured == null ? bindings.getLast() : ensured.binding();
                values.put("SESSION_ID", binding.sessionId());
                values.put("SUPERVISOR_ID", binding.supervisorId());
                values.put("WORKER_ID", binding.workerId());
                values.put("SESSION_PROJECT_ID", binding.projectId());
                values.put("SESSION_NODE_ID", binding.nodeId());
                String workspaceTrust = binding.worktreePath() == null ? "WORKSPACE_UNVERIFIED" : binding.providerTrustState();
                values.put("SESSION_TRUST", workspaceTrust);
                values.put("WORKSPACE_TRUST", workspaceTrust);
                values.put("SESSION_WORKSPACE", binding.worktreePath() == null ? "UNASSIGNED" : "ASSIGNED");
                boolean hasEvidence = hasProvenInterception(location, provider, binding.sessionId());
                values.put("SESSION_INTERCEPTION", hasEvidence ? "PROVEN" : "UNPROVEN");
                values.put("NATIVE_MUTATION_INTERCEPTION", "UNPROVEN");
                values.put("BROKERED_MUTATION_AVAILABLE", "true");
                values.put("BROKERED_MUTATION_VALIDATED", Boolean.toString(hasEvidence));
                values.put("ASSIGNED_WORKTREE", binding.worktreePath() == null ? "UNASSIGNED" : binding.worktreePath());
                values.put("ACTIVE_WORKSPACE", hasEvidence && binding.worktreePath() != null ? binding.worktreePath() : "UNPROVEN");
                values.put("HOOK_INTERCEPTED", Boolean.toString(hasEvidence));
                values.put("DECISION", hasEvidence ? "ALLOW" : "UNKNOWN");
                values.put("MUTATION_WITHOUT_ALLOW_POSSIBLE", Boolean.toString(!hasEvidence));
                if ("codex".equals(provider) && "VERIFIED".equals(binding.providerTrustState())) {
                    values.put("CODEX_PROVIDER_STATUS", "BROKERED_MUTATION_READY");
                }
                values.put("BRANCH", binding.branch() == null ? "UNASSIGNED" : binding.branch());
                values.put("BASE_COMMIT", binding.baseCommit());
                values.put("WORKTREE_BINDING_STATUS", "VERIFIED".equals(binding.verificationState()) ? "BOUND" : binding.creationState());
                values.put("WORKSPACE_TRUST_STATUS", binding.verificationState());
                boolean fallback = ensured != null ? ensured.fallbackEvidence() : new ProviderSessionBindingService().isFallbackEvidence(location, provider, binding);
                values.put("SESSION_EVIDENCE", fallback ? "FALLBACK" : "EXPLICIT");
            }
            values.put("CONTINUITY_MODE", continuityMode(location, provider));
        } catch (Exception failure) {
            values.put("SESSION_BINDING", "BROKEN");
        }
        return new ProviderResult(result.exitCode(), values);
    }

    private static String continuityMode(ProjectLocation location, String provider) {
        ProviderIntegration integration = ProviderRegistry.find(provider);
        if (integration == null || !integration.supportsManagedContinuity()) {
            return "SESSION_BOUND";
        }
        Path directory = location.synesisDirectory().resolve("local/runtime/managed-continuity");
        if (!Files.isDirectory(directory)) {
            return "SESSION_BOUND";
        }
        try (var paths = Files.list(directory)) {
            boolean managed = paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json")).map(path -> {
                try {
                    return new org.synesis.workspace.application.provider.continuity.ManagedAttachmentStore(path).read().orElse(null);
                } catch (IOException invalid) {
                    return null;
                }
            }).anyMatch(record -> record != null && provider.equals(record.provider()) && record.status() != ManagedAttachmentRecord.Status.TERMINAL);
            return managed ? "MANAGED_CONTINUITY" : "SESSION_BOUND";
        } catch (IOException unavailable) {
            return "SESSION_BOUND";
        }
    }

    private static boolean hasProvenInterception(ProjectLocation location, String provider, String sessionId) {
        try {
            Path dir = location.synesisDirectory().resolve("local").resolve("evidence").resolve(provider);
            if (!Files.isDirectory(dir)) {
                return false;
            }
            try (var paths = Files.list(dir)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    Object parsed = ProviderJson.parse(Files.readString(path));
                    if (parsed instanceof Map<?, ?> map) {
                        if (sessionId.equals(map.get("sessionId")) && Boolean.TRUE.equals(map.get("hookIntercepted")) && "ALLOW".equals(map.get("decision"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static ProviderResult result(ProviderIntegration provider, String state, ProviderIntegration.SyntheticCheck synthetic, Path config, Path profile, String mcpStatus) {
        return simple("PROVIDER_INSTALL_RESULT",
                state,
                0,
                "PROVIDER",
                provider.id(),
                "SUPPORT_LEVEL",
                provider.supportLevel().name(),
                "MCP_EVIDENCE_TIER",
                provider.mcpEvidenceTier().name(),
                "CONFIG_PATH",
                config.toString(),
                "PROFILE_PATH",
                profile.toString(),
                "MANAGED_HOOK_PRESENT",
                "true",
                "SYNTHETIC_CHECK",
                synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED",
                "MCP_CONFIG_STATUS",
                mcpStatus,
                "TRUST_STATUS",
                provider.trustStatus(),
                "REAL_AGENT_VALIDATION",
                "NOT_COMPLETED");
    }

    static ProviderResult status(ProviderIntegration provider, String state, Path config, boolean metadata, int count, boolean launcher, boolean profile, boolean synthetic, int exit) {
        return simple("PROVIDER_STATUS",
                state,
                exit,
                "PROVIDER",
                provider.id(),
                "SUPPORT_LEVEL",
                provider.supportLevel().name(),
                "MCP_EVIDENCE_TIER",
                provider.mcpEvidenceTier().name(),
                "METADATA_PRESENT",
                Boolean.toString(metadata),
                "CONFIG_PRESENT",
                Boolean.toString(Files.isRegularFile(config)),
                "MANAGED_HOOK_COUNT",
                Integer.toString(count),
                "LAUNCHER_PRESENT",
                Boolean.toString(launcher),
                "PROFILE_PRESENT",
                Boolean.toString(profile),
                "SYNTHETIC_BLOCK_CHECK",
                synthetic ? "PASSED" : "NOT_RUN",
                "TRUST_STATUS",
                provider.trustStatus(),
                "REAL_AGENT_VALIDATION",
                "NOT_COMPLETED");
    }

    static ProviderResult simple(String key, String state, int exit, String... fields) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, state);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            values.put(fields[i], fields[i + 1]);
        }
        return new ProviderResult(exit, values);
    }

    static ProviderResult failure(String id, String error, String key, int exit) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, error);
        values.put("ERROR", error);
        if (id != null) {
            values.put("PROVIDER", id);
        }
        return new ProviderResult(exit, values);
    }

    static ProviderResult withValue(ProviderResult result, String key, String value) {
        Map<String, String> values = new LinkedHashMap<>(result.values());
        values.put(key, value);
        return new ProviderResult(result.exitCode(), values);
    }
}
