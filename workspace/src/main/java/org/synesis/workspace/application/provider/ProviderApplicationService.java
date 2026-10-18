package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderHookConfiguration.EmptySessionStart;
import org.synesis.workspace.provider.codex.CodexTomlConfiguration;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderSupportLevel;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.atomicWrite;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.metadata;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.readObject;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.schemaVersion;
import static org.synesis.workspace.application.provider.ProviderHealthProbes.probeMcp;
import static org.synesis.workspace.application.provider.ProviderHealthProbes.syntheticCheck;
import static org.synesis.workspace.application.provider.ProviderHookConfiguration.managedCommandMatches;
import static org.synesis.workspace.application.provider.ProviderHookConfiguration.managedEntries;
import static org.synesis.workspace.application.provider.ProviderHookConfiguration.materializeHook;
import static org.synesis.workspace.application.provider.ProviderHookConfiguration.mergeManagedHooks;
import static org.synesis.workspace.application.provider.ProviderHookConfiguration.removeManagedHooks;
import static org.synesis.workspace.application.provider.ProviderProjectInstructions.ensureProjectInstructions;
import static org.synesis.workspace.application.provider.ProviderProjectInstructions.removeProjectInstructions;
import static org.synesis.workspace.application.provider.ProviderResults.decorate;
import static org.synesis.workspace.application.provider.ProviderResults.failure;
import static org.synesis.workspace.application.provider.ProviderResults.result;
import static org.synesis.workspace.application.provider.ProviderResults.simple;
import static org.synesis.workspace.application.provider.ProviderResults.withValue;

/**
 * Owns provider lifecycle, local metadata, and configuration merging.
 */
@SuppressWarnings("DuplicatedCode")
public final class ProviderApplicationService {

    private final ProviderManualService manualService = new ProviderManualService();
    private final ProviderMcpConfigurationService mcpConfiguration = new ProviderMcpConfigurationService();

    /**
     * Creates the default provider service.
     */
    public ProviderApplicationService() {
    }

    /**
     * Resolves the stable or configured launcher path, the one provider hooks run.
     *
     * @return resolved launcher path
     */
    public Path launcherPath() {
        return ProviderLauncherResolution.launcher();
    }

    /**
     * Resolves the native stdio MCP launcher when the local distribution provides it, falling back to
     * the CLI launcher for development installs.
     *
     * @return native MCP launcher or development fallback
     */
    public Path mcpLauncherPath() {
        return ProviderLauncherResolution.mcpLauncherPath();
    }

    /**
     * Lists the currently implemented providers.
     *
     * @param location project location
     * @return provider rows
     */
    public List<ProviderRow> list(ProjectLocation location) {
        Objects.requireNonNull(location, "location");
        return ProviderRegistry.providers().stream().map(provider -> new ProviderRow(provider.id(), provider.supportLevel(), Files.exists(metadata(location, provider)) ? "INSTALLED" : "NOT_INSTALLED")).toList();
    }

    /**
     * Installs or updates one provider.
     *
     * @param location project location
     * @param id       provider ID
     * @return structured result
     */
    public ProviderResult install(ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_INSTALL_RESULT", 2);
        }
        try {
            Path launcher = ProviderLauncherResolution.launcher();
            Path profile = location.profile();
            if (!Files.isDirectory(profile)) {
                return failure(id, "PROFILE_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            }
            if (!Files.isRegularFile(launcher)) {
                return failure(id, "LAUNCHER_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            }
            ProviderManualService.Attestation manual = manualService.install(provider.id());
            if (!manual.valid()) {
                return withValue(failure(id, "MANUAL_INSTALL_FAILED", "PROVIDER_INSTALL_RESULT", 10), "MANUAL_REASON", manual.reason());
            }
            Path config = provider.configurationPath(location.root());
            Path metadataPath = metadata(location, provider);
            if (Files.exists(metadataPath)) {
                Map<String, Object> oldMetadata = readObject(metadataPath);
                if (!schemaVersion(oldMetadata.get("schemaVersion"))) {
                    return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_INSTALL_RESULT", 10);
                }
            }
            boolean already;
            if (provider.sharesHookFileWithUser()) {
                // Codex hook ownership is classified and materialized by the
                // fail-closed path before any provider session authority exists.
                materializeHook(location.root(), provider, launcher, profile);
                already = Files.exists(metadata(location, provider));
            } else {
                Map<String, Object> root = readObject(config);
                already = mergeManagedHooks(root, provider, launcher, profile, EmptySessionStart.DROP) && Files.exists(metadata(location, provider));
                atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
            }
            Path mcpLauncher = mcpLauncherPath();
            String mcpStatus = ensureMcpConfig(location, provider, mcpLauncher);
            String instructionsStatus = ensureProjectInstructions(location, provider);
            McpHealth health = probeMcp(mcpLauncher, provider.id(), location.root());
            ProviderIntegration.SyntheticCheck synthetic = syntheticCheck(provider);
            ProviderMetadata installed = new ProviderMetadata(provider.id(), provider.supportLevel().name(), Instant.now().toString(), config.toAbsolutePath().normalize().toString(),
                    launcher.toAbsolutePath().normalize().toString(), mcpLauncher.toAbsolutePath().normalize().toString(), profile.toAbsolutePath().normalize().toString(), provider.managedHookId(),
                    mcpStatus, health.status(), manual.version(), manual.contentHash(), manual.wireCompatibilityDigest(), manual.catalogContentDigest(), manual.guidanceArtifactDigest(),
                    manualService.skillDirectory(provider.id()).resolve("SKILL.md").toString(), synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED");
            atomicWrite(metadata(location, provider), ProviderJson.write(installed.toMap()) + System.lineSeparator());
            String result = synthetic.blocked() && synthetic.allowed() && synthetic.validJson() && health.passed() && !provider.requiresRealValidation() ? (already ? "ALREADY_INSTALLED" : "SUCCESS") : "DEGRADED";
            ProviderResult installedResult = result(provider, result, synthetic, config, profile, mcpStatus);
            installedResult = withValue(installedResult, "SYNESIS_MANUAL", "ATTESTED");
            installedResult = withValue(installedResult, "MCP_HEALTH", health.status());
            installedResult = withValue(installedResult, "PROJECT_INSTRUCTIONS", instructionsStatus);
            try {
                ProviderSessionBindingService.BindingResult ensured = new ProviderSessionBindingService().ensure(location, provider.id(), null);
                if (ensured.binding().worktreePath() != null) {
                    materializeHook(Path.of(ensured.binding().worktreePath()), provider, launcher, profile);
                }
                return decorate(location, provider.id(), installedResult, ensured);
            } catch (ProviderSessionBindingService.BindingException bindingFailure) {
                return decorate(location, provider.id(), installedResult, null);
            }
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_INSTALL_RESULT", 10);
        } catch (Exception failure) {
            if (String.valueOf(failure.getMessage()).contains("PROVIDER_CONFIGURATION_CONFLICT")) {
                return failure(id, "PROVIDER_CONFIGURATION_CONFLICT", "PROVIDER_INSTALL_RESULT", 10);
            }
            return failure(id, "INSTALL_FAILED", "PROVIDER_INSTALL_RESULT", 10);
        }
    }

    /**
     * Inspects one provider without repairing it.
     *
     * @param location project location
     * @param id       provider ID
     * @return structured result
     */
    public ProviderResult status(ProjectLocation location, String id) {
        ProviderIntegration resolvedProvider = provider(id);
        String resolvedId = resolvedProvider == null ? id : resolvedProvider.id();
        ProviderResult result = decorate(location, resolvedId, statusInternal(location, resolvedId), null);
        if (resolvedProvider != null && resolvedProvider.usesGlobalMcpConfiguration()) {
            try {
                Path path = resolvedProvider.mcpConfigurationPath(location.root());
                CodexTomlConfiguration.Inspection inspection = CodexTomlConfiguration.inspect(path, mcpLauncherPath());
                Map<String, String> values = new LinkedHashMap<>(result.values());
                values.put("MCP_CONFIG_PATH", path.toString());
                values.put("MCP_CONFIG_STATUS", inspection.outcome().name());
                values.put("MCP_CONFIG_READ_ONLY", "true");
                return new ProviderResult(result.exitCode(), values);
            } catch (Exception ignored) {
                // The hook status remains authoritative when MCP inspection cannot read the file.
            }
        }
        return result;
    }

    /**
     * Evaluates whether a provider integration may admit agent work.
     *
     * <p>A project-local MCP entry alone is not an installation: initialization
     * may create that entry before the provider hook and metadata exist. A {@code DEGRADED} state
     * remains admissible, while missing metadata or any broken/unknown status fails closed.</p>
     *
     * @param location initialized project location
     * @param id       canonical provider identifier
     * @return bounded provider work-admission result
     */
    public ProviderWorkAdmission assessWorkAdmission(ProjectLocation location, String id) {
        Objects.requireNonNull(location, "location");
        ProviderResult result = status(location, id);
        String state = result.values().get("PROVIDER_STATUS");
        boolean metadataPresent = "true".equalsIgnoreCase(result.values().get("METADATA_PRESENT"));
        boolean admissibleState = "HEALTHY".equals(state) || "DEGRADED".equals(state);
        return new ProviderWorkAdmission(metadataPresent && admissibleState, state == null ? "UNKNOWN" : state);
    }

    private ProviderResult statusInternal(ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_STATUS", 2);
        }
        Path config = provider.configurationPath(location.root());
        Path metadataPath = metadata(location, provider);
        ProviderManualService.Attestation manual = manualService.attest(provider.id());
        boolean metadataPresent = Files.isRegularFile(metadataPath);
        boolean configPresent = Files.isRegularFile(config);
        if (!metadataPresent && !configPresent) {
            return ProviderResults.status(provider, "NOT_INSTALLED", config, false, 0, false, false, false, 0);
        }
        try {
            Map<String, Object> root = configPresent ? readObject(config) : Map.of();
            int count = configPresent ? managedEntries(root, provider).size() : 0;
            Map<String, Object> stored = metadataPresent ? readObject(metadataPath) : Map.of();
            ProviderMetadata metadata = ProviderMetadata.fromMap(stored);
            boolean schemaValid = !metadataPresent || schemaVersion(stored.get("schemaVersion"));
            boolean validMetadata = metadataPresent && schemaValid && provider.id().equals(metadata.provider()) && provider.supportLevel().name().equals(metadata.supportLevel());
            boolean launcherPresent = validMetadata && Files.isRegularFile(Path.of(metadata.launcherPath()));
            boolean profilePresent = validMetadata && Files.isDirectory(Path.of(metadata.profilePath()));
            boolean configurationCorrect = count == 1 && launcherPresent && profilePresent && managedCommandMatches(root, provider, Path.of(metadata.launcherPath()), Path.of(metadata.profilePath()));
            if (!schemaValid) {
                return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_STATUS", 3);
            }
            if (!configPresent) {
                return ProviderResults.status(provider, "BROKEN", config, true, count, launcherPresent, profilePresent, false, 3);
            }
            if (!validMetadata || !configurationCorrect) {
                return ProviderResults.status(provider, "DEGRADED", config, metadataPresent, count, launcherPresent, profilePresent, false, 1);
            }
            var synthetic = syntheticCheck(provider);
            String state = synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? (provider.requiresRealValidation() ? "DEGRADED" : "HEALTHY") : "BROKEN";
            boolean wireCompatible = McpToolCatalog.wireCompatibilityDigest().equals(metadata.wireCompatibilityDigest());
            boolean catalogFresh = McpToolCatalog.catalogContentDigest().equals(metadata.catalogContentDigest());
            Path managedManual = manualService.skillDirectory(provider.id()).resolve("SKILL.md");
            boolean guidanceValid = manual.valid() && Files.isRegularFile(managedManual) && McpToolCatalog.guidanceArtifactDigest(ProviderManualService.MANUAL_NAME, provider.id(), Files.readAllBytes(managedManual))
                    .equals(metadata.guidanceArtifactDigest());
            if (!wireCompatible) {
                state = "DEGRADED";
            }
            ProviderResult result = ProviderResults.status(provider, state, config, true, count, true, true, synthetic.blocked() && synthetic.allowed(), state.equals("HEALTHY") ? 0 : 1);
            result = withValue(result, "SYNESIS_MANUAL", manual.valid() ? "ATTESTED" : manual.reason());
            result = withValue(result, "MCP_WIRE_COMPATIBILITY", wireCompatible ? "CURRENT" : "MCP_CATALOG_MISMATCH");
            result = withValue(result, "MCP_CATALOG_CONTENT", catalogFresh ? "CURRENT" : "CATALOG_CONTENT_STALE");
            result = withValue(result, "GUIDANCE_ARTIFACT", guidanceValid ? "CURRENT" : "GUIDANCE_ARTIFACT_MISMATCH");
            if (!wireCompatible) {
                result = withValue(result, "ERROR", "MCP_CATALOG_MISMATCH");
            } else if (!catalogFresh || !guidanceValid) {
                result = withValue(result, "ERROR", "MANAGED_GUIDANCE_STALE");
            }
            return result;
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_STATUS", 3);
        } catch (Exception failure) {
            return failure(id, "BROKEN", "PROVIDER_STATUS", 3);
        }
    }

    /**
     * Uninstalls only the managed hook and local provider metadata.
     *
     * @param location project location
     * @param id       provider ID
     * @return structured result
     */
    public ProviderResult uninstall(ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_UNINSTALL_RESULT", 2);
        }
        Path config = provider.configurationPath(location.root());
        Path metadata = metadata(location, provider);
        if (!Files.exists(config) && !Files.exists(metadata)) {
            return simple("PROVIDER_UNINSTALL_RESULT", "NOT_INSTALLED", 0);
        }
        try {
            boolean removed = removeManagedHooks(location.root(), provider);
            if (!provider.usesGlobalMcpConfiguration()) {
                removeMcpConfig(location, provider);
            }
            removeProjectInstructions(location, provider);
            Files.deleteIfExists(metadata);
            try {
                new ProviderSessionBindingService().revoke(location, provider.id());
            } catch (ProviderSessionBindingService.BindingException ignored) {
                // Status exposes a broken binding; managed hook removal remains complete.
            }
            return simple("PROVIDER_UNINSTALL_RESULT", "SUCCESS", 0, "MANAGED_HOOK_REMOVED", Boolean.toString(removed), "GLOBAL_MCP_CONFIG_PRESERVED", Boolean.toString(provider.usesGlobalMcpConfiguration()), "UNRELATED_CONFIGURATION_PRESERVED", "true");
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_UNINSTALL_RESULT", 10);
        } catch (Exception failure) {
            if (String.valueOf(failure.getMessage()).contains("PROVIDER_CONFIGURATION_CONFLICT")) {
                return failure(id, "PROVIDER_CONFIGURATION_CONFLICT", "PROVIDER_UNINSTALL_RESULT", 10);
            }
            return failure(id, "UNINSTALL_FAILED", "PROVIDER_UNINSTALL_RESULT", 10);
        }
    }

    /**
     * Removes the shared user-level Codex MCP entry without changing any project hook.
     *
     * @param id provider identifier
     * @return structured global MCP removal result
     */
    public ProviderResult uninstallGlobalMcpConfiguration(String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_UNINSTALL_RESULT", 2);
        }
        if (!provider.usesGlobalMcpConfiguration()) {
            return failure(id, "GLOBAL_MCP_UNSUPPORTED", "PROVIDER_UNINSTALL_RESULT", 2);
        }
        try {
            String outcome = mcpConfiguration.removeGlobal(provider);
            return simple("PROVIDER_UNINSTALL_RESULT", "SUCCESS", 0, "GLOBAL_MCP_CONFIG_STATUS", outcome, "PROJECT_HOOKS_CHANGED", "false");
        } catch (Exception failure) {
            return failure(id, "GLOBAL_MCP_UNINSTALL_FAILED", "PROVIDER_UNINSTALL_RESULT", 10);
        }
    }

    /**
     * Ensures provider-neutral Model Context Protocol (MCP) server configuration is installed.
     *
     * @param location project location
     * @param provider provider integration
     * @param launcher stable launcher path
     * @return installation status identifier
     */
    public String ensureMcpConfig(ProjectLocation location, ProviderIntegration provider, Path launcher) {
        return mcpConfiguration.ensure(location, provider, launcher);
    }

    private void removeMcpConfig(ProjectLocation location, ProviderIntegration provider) {
        mcpConfiguration.remove(location, provider);
    }

    private ProviderIntegration provider(String id) {
        return ProviderRegistry.find(id);
    }


    /**
     * Holds provider MCP health status for readiness reporting.
     */
    record McpHealth(boolean passed, String status) {

    }

    /**
     * Provider list row.
     *
     * @param id           provider ID
     * @param supportLevel maturity
     * @param status       local state
     */
    public record ProviderRow(String id, ProviderSupportLevel supportLevel, String status) {

    }

    /**
     * Result of the provider integration gate used before session admission.
     *
     * @param admitted whether agent work may be admitted
     * @param status   current provider status classification
     */
    public record ProviderWorkAdmission(boolean admitted, String status) {

        /**
         * Validates the bounded status value.
         */
        public ProviderWorkAdmission {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Structured provider operation result.
     *
     * @param exitCode process code
     * @param values   machine-readable fields
     */
    public record ProviderResult(int exitCode, Map<String, String> values) {

        /**
         * Copies the result fields.
         */
        public ProviderResult {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
