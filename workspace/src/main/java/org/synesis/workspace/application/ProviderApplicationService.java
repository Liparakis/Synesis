package org.synesis.workspace.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.provider.ProviderRegistry;
import org.synesis.workspace.provider.ProviderSupportLevel;
import org.synesis.workspace.provider.antigravity.AntigravityProviderIntegration;
import org.synesis.workspace.migration.CodexTomlConfiguration;

/**
 * Owns provider lifecycle, local metadata, configuration merging, and diagnostics.
 */
public final class ProviderApplicationService {

    private static final int METADATA_SCHEMA = 1;

    /**
     * Creates the default provider service.
     */
    public ProviderApplicationService() {
    }

    private static ProviderResult decorate(ProjectApplicationService.ProjectLocation location, String provider,
            ProviderResult result, ProviderSessionBindingService.BindingResult ensured) {
        Map<String, String> values = new LinkedHashMap<>(result.values());
        try {
            var bindings = new ProviderSessionBindingService().list(location, provider);
            values.put("SESSION_BINDING", bindings.isEmpty() ? "UNBOUND"
                    : bindings.stream()
                            .allMatch(binding -> binding.status()
                                    .equals("REVOKED")) ? "REVOKED" : "BOUND");
            if (!bindings.isEmpty()) {
                var binding = ensured == null ? bindings.getLast() : ensured.binding();
                values.put("SESSION_ID", binding.sessionId());
                values.put("SUPERVISOR_ID", binding.supervisorId());
                values.put("WORKER_ID", binding.workerId());
                values.put("SESSION_PROJECT_ID", binding.projectId());
                values.put("SESSION_NODE_ID", binding.nodeId());
                values.put("SESSION_TRUST",
                        binding.worktreePath() == null ? "WORKSPACE_UNVERIFIED" : binding.providerTrustState());
                values.put("WORKSPACE_TRUST",
                        binding.worktreePath() == null ? "WORKSPACE_UNVERIFIED" : binding.providerTrustState());
                values.put("SESSION_WORKSPACE", binding.worktreePath() == null ? "UNASSIGNED" : "ASSIGNED");
                boolean hasEvidence = hasProvenInterception(location, provider, binding.sessionId());
                values.put("SESSION_INTERCEPTION", hasEvidence ? "PROVEN" : "UNPROVEN");
                values.put("NATIVE_MUTATION_INTERCEPTION", "UNPROVEN");
                values.put("BROKERED_MUTATION_AVAILABLE", "true");
                values.put("BROKERED_MUTATION_VALIDATED", Boolean.toString(hasEvidence));
                values.put("ASSIGNED_WORKTREE", binding.worktreePath() == null ? "UNASSIGNED" : binding.worktreePath());
                values.put("ACTIVE_WORKSPACE",
                        hasEvidence && binding.worktreePath() != null ? binding.worktreePath() : "UNPROVEN");
                values.put("HOOK_INTERCEPTED", hasEvidence ? "true" : "false");
                values.put("DECISION", hasEvidence ? "ALLOW" : "UNKNOWN");
                values.put("MUTATION_WITHOUT_ALLOW_POSSIBLE", hasEvidence ? "false" : "true");
                if ("codex".equals(provider) && "VERIFIED".equals(binding.providerTrustState())) {
                    values.put("CODEX_PROVIDER_STATUS", "BROKERED_MUTATION_READY");
                }
                values.put("BRANCH", binding.branch() == null ? "UNASSIGNED" : binding.branch());
                values.put("BASE_COMMIT", binding.baseCommit());
                values.put("WORKTREE_BINDING_STATUS",
                        "VERIFIED".equals(binding.verificationState()) ? "BOUND" : binding.creationState());
                values.put("WORKSPACE_TRUST_STATUS", binding.verificationState());
                boolean fallback = ensured != null ? ensured.fallbackEvidence()
                        : new ProviderSessionBindingService().isFallbackEvidence(location, provider, binding);
                values.put("SESSION_EVIDENCE", fallback ? "FALLBACK" : "EXPLICIT");
            }
        } catch (Exception failure) {
            values.put("SESSION_BINDING", "BROKEN");
        }
        return new ProviderResult(result.exitCode(), values);
    }

    private static boolean hasProvenInterception(ProjectApplicationService.ProjectLocation location,
            String provider,
            String sessionId) {
        try {
            Path dir = location.synesisDirectory()
                    .resolve("local")
                    .resolve("evidence")
                    .resolve(provider);
            if (!Files.isDirectory(dir)) {
                return false;
            }
            try (var paths = Files.list(dir)) {
                for (Path path : paths.filter(p -> p.getFileName()
                                .toString()
                                .endsWith(".json"))
                        .toList()) {
                    Object parsed = ProviderJson.parse(Files.readString(path));
                    if (parsed instanceof Map<?, ?> map) {
                        if (sessionId.equals(map.get("sessionId")) && Boolean.TRUE.equals(map.get("hookIntercepted"))
                                && "ALLOW".equals(map.get("decision"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Map<String, Object> readObject(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        Object parsed = ProviderJson.parse(Files.readString(path));
        return object(parsed) == null ? throwInvalid() : object(parsed);
    }

    private static Map<String, Object> throwInvalid() {
        throw new IllegalArgumentException("JSON object expected");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("JSON array expected");
    }

    private static List<Object> managedEntries(Map<String, Object> root, ProviderIntegration provider) {
        Map<String, Object> group = object(root.get(provider.hookGroup()));
        return group == null ? List.of() : list(group.get("PreToolUse")).stream()
                                           .filter(provider::isManagedHook)
                                           .toList();
    }

    private static boolean managedCommandMatches(Map<String, Object> root,
            ProviderIntegration provider,
            Path launcher,
            Path profile) {
        List<Object> entries = managedEntries(root, provider);
        if (entries.size() != 1) {
            return false;
        }
        Map<String, Object> hook = object(entries.getFirst());
        if (hook == null || !provider.matcher()
                .equals(hook.get("matcher"))) {
            return false;
        }
        List<Object> commands = list(hook.get("hooks"));
        if (commands.size() != 1) {
            return false;
        }
        Map<String, Object> command = object(commands.getFirst());
        if (command == null || !provider.hookCommand(launcher, profile)
                .equals(command.get("command"))) {
            return false;
        }
        String windowsCommand = provider.windowsHookCommand(launcher, profile);
        return windowsCommand == null || windowsCommand.equals(command.get("commandWindows"));
    }

    private static boolean schemaVersion(Object value) {
        return value instanceof Number number && number.doubleValue() == METADATA_SCHEMA;
    }

    private static boolean recordStoreHealthy(ProjectApplicationService.ProjectLocation location) {
        Path records = location.profile()
                .resolve("records");
        if (!Files.isDirectory(records)) {
            return false;
        }
        try (var paths = Files.walk(records)) {
            for (Path path : paths.filter(file -> file.toString()
                            .endsWith(".sdr"))
                    .toList()) {
                DecisionRecord record = DecisionRecord.decode(Files.readAllBytes(path));
                if (!record.verify()) {
                    return false;
                }
            }
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String quote(Path path) {
        return "\"" + path.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"") + "\"";
    }

    private static Path metadata(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) {
        return location.synesisDirectory()
                .resolve("local/providers/" + provider.id() + ".json");
    }

    /**
     * Resolves the stable or configured launcher path.
     *
     * @return resolved launcher path
     */
    public Path launcherPath() {
        return launcher();
    }

    private static Path launcher() {
        String executable = isWindows() ? "synesis.cmd" : "synesis";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(entry)
                        .resolve(executable)
                        .toAbsolutePath()
                        .normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        String configured = System.getProperty("synesis.launcher", System.getenv("SYNESIS_LAUNCHER"));
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured)
                    .toAbsolutePath()
                    .normalize();
        }
        Path fallback = stableLauncher(executable);
        return fallback;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static Path stableLauncher(String executable) {
        String base;
        if (isWindows()) {
            base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), "AppData", "Local")
                        .toString();
            }
        } else if (System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support")
                    .toString();
        } else {
            base = System.getenv("XDG_DATA_HOME");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), ".local", "share")
                        .toString();
            }
        }
        return Path.of(base, "Synesis", "bin", executable)
                .toAbsolutePath()
                .normalize();
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ProviderResult result(ProviderIntegration provider,
            String key,
            String state,
            ProviderIntegration.SyntheticCheck synthetic,
            Path config,
            Path profile,
            Path launcher,
            String mcpStatus,
            int exit) {
        return simple(provider,
                key,
                state,
                exit,
                "PROVIDER",
                provider.id(),
                "SUPPORT_LEVEL",
                provider.supportLevel()
                        .name(),
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

    private static ProviderResult status(ProviderIntegration provider,
            String state,
            Path config,
            boolean metadata,
            int count,
            boolean launcher,
            boolean profile,
            boolean synthetic,
            int exit) {
        return simple(provider,
                "PROVIDER_STATUS",
                state,
                exit,
                "PROVIDER",
                provider.id(),
                "SUPPORT_LEVEL",
                provider.supportLevel()
                        .name(),
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

    private static ProviderResult simple(ProviderIntegration provider,
            String key,
            String state,
            int exit,
            String... fields) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, state);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            values.put(fields[i], fields[i + 1]);
        }
        return new ProviderResult(exit, values);
    }

    private static ProviderResult failure(String id, String error, String key, int exit) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, error);
        values.put("ERROR", error);
        if (id != null) {
            values.put("PROVIDER", id);
        }
        return new ProviderResult(exit, values);
    }

    /**
     * Lists the currently implemented providers.
     *
     * @param location project location
     * @return provider rows
     */
    public List<ProviderRow> list(ProjectApplicationService.ProjectLocation location) {
        Objects.requireNonNull(location, "location");
        return ProviderRegistry.providers()
                .stream()
                .map(provider -> new ProviderRow(provider.id(), provider.supportLevel(),
                        Files.exists(metadata(location, provider)) ? "INSTALLED" : "NOT_INSTALLED"))
                .toList();
    }

    /**
     * Installs or updates one provider.
     *
     * @param location project location
     * @param id       provider ID
     * @return structured result
     */
    public ProviderResult install(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_INSTALL_RESULT", 2);
        }
        try {
            Path launcher = launcher();
            Path profile = location.profile();
            if (!Files.isDirectory(profile)) {
                return failure(id, "PROFILE_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            }
            if (!Files.isRegularFile(launcher)) {
                return failure(id, "LAUNCHER_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            }
            Path config = provider.configurationPath(location.root());
            Path metadataPath = metadata(location, provider);
            if (Files.exists(metadataPath)) {
                Map<String, Object> oldMetadata = readObject(metadataPath);
                if (!schemaVersion(oldMetadata.get("schemaVersion"))) {
                    return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_INSTALL_RESULT", 10);
                }
            }
            Map<String, Object> root = readObject(config);
            Map<String, Object> group = object(root.computeIfAbsent(provider.hookGroup(),
                    ignored -> new LinkedHashMap<>()));
            List<Object> hooks = list(group.computeIfAbsent("PreToolUse", ignored -> new ArrayList<>()));
            Map<String, Object> expectedHook = provider.managedHook(launcher, profile);
            if (provider instanceof AntigravityProviderIntegration antigravity && isWindows()) {
                antigravity.writeWrapper(profile);
            }
            boolean already = hooks.stream()
                    .filter(provider::isManagedHook)
                    .count() == 1
                    && expectedHook.equals(hooks.stream()
                    .filter(provider::isManagedHook)
                    .findFirst()
                    .orElse(null))
                    && Files.exists(metadata(location, provider));
            hooks.removeIf(provider::isManagedHook);
            hooks.add(expectedHook);
            atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
            String mcpStatus = ensureMcpConfig(location, provider, launcher);
            ProviderIntegration.SyntheticCheck synthetic = syntheticCheck(location, provider);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", METADATA_SCHEMA);
            metadata.put("provider", provider.id());
            metadata.put("supportLevel",
                    provider.supportLevel()
                            .name());
            metadata.put("installedAt",
                    Instant.now()
                            .toString());
            metadata.put("configurationPath",
                    config.toAbsolutePath()
                            .normalize()
                            .toString());
            metadata.put("launcherPath",
                    launcher.toAbsolutePath()
                            .normalize()
                            .toString());
            metadata.put("profilePath",
                    profile.toAbsolutePath()
                            .normalize()
                            .toString());
            metadata.put("managedEntryId", provider.managedHookId());
            metadata.put("mcpConfigStatus", mcpStatus);
            metadata.put("lastSyntheticCheck",
                    synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED");
            atomicWrite(metadata(location, provider), ProviderJson.write(metadata) + System.lineSeparator());
            String result = synthetic.blocked() && synthetic.allowed() && synthetic.validJson()
                    && !provider.requiresRealValidation()
                    ? (already ? "ALREADY_INSTALLED" : "SUCCESS") : "DEGRADED";
            ProviderResult installedResult = result(provider,
                    "PROVIDER_INSTALL_RESULT",
                    result,
                    synthetic,
                    config,
                    profile,
                    launcher,
                    mcpStatus,
                    0);
            try {
                return decorate(location, provider.id(), installedResult,
                        new ProviderSessionBindingService().ensure(location, provider.id(), null));
            } catch (ProviderSessionBindingService.BindingException bindingFailure) {
                return decorate(location, provider.id(), installedResult, null);
            }
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_INSTALL_RESULT", 10);
        } catch (Exception failure) {
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
    public ProviderResult status(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration resolvedProvider = provider(id);
        String resolvedId = resolvedProvider == null ? id : resolvedProvider.id();
        ProviderResult result = decorate(location, resolvedId, statusInternal(location, resolvedId), null);
        if ("codex".equals(resolvedId)) {
            try {
                Path path = resolvedProvider.mcpConfigurationPath(location.root());
                CodexTomlConfiguration.Inspection inspection = CodexTomlConfiguration.inspect(path,
                        stableLauncher(isWindows() ? "synesis.cmd" : "synesis"));
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

    private ProviderResult statusInternal(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_STATUS", 2);
        }
        Path config = provider.configurationPath(location.root());
        Path metadataPath = metadata(location, provider);
        boolean metadataPresent = Files.isRegularFile(metadataPath);
        boolean configPresent = Files.isRegularFile(config);
        if (!metadataPresent && !configPresent) {
            return status(provider, "NOT_INSTALLED", config, false, 0, false, false, false, 0);
        }
        try {
            Map<String, Object> root = configPresent ? readObject(config) : Map.of();
            int count = configPresent ? managedEntries(root, provider).size() : 0;
            Map<String, Object> metadata = metadataPresent ? readObject(metadataPath) : Map.of();
            boolean schemaValid = !metadataPresent || schemaVersion(metadata.get("schemaVersion"));
            boolean validMetadata = metadataPresent && schemaValid && provider.id()
                    .equals(String.valueOf(metadata.get("provider")))
                    && provider.supportLevel()
                    .name()
                    .equals(String.valueOf(metadata.get("supportLevel")));
            boolean launcherPresent =
                    validMetadata && Files.isRegularFile(Path.of(String.valueOf(metadata.get("launcherPath"))));
            boolean profilePresent =
                    validMetadata && Files.isDirectory(Path.of(String.valueOf(metadata.get("profilePath"))));
            boolean configurationCorrect = count == 1 && launcherPresent && profilePresent
                    && managedCommandMatches(root, provider, Path.of(String.valueOf(metadata.get("launcherPath"))),
                    Path.of(String.valueOf(metadata.get("profilePath"))));
            if (!schemaValid) {
                return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_STATUS", 3);
            }
            if (!configPresent) {
                return status(provider,
                        "BROKEN",
                        config,
                        metadataPresent,
                        count,
                        launcherPresent,
                        profilePresent,
                        false,
                        3);
            }
            if (!validMetadata || !configurationCorrect) {
                return status(provider,
                        metadataPresent ? "DEGRADED" : "DEGRADED",
                        config,
                        metadataPresent,
                        count,
                        launcherPresent,
                        profilePresent,
                        false,
                        1);
            }
            var synthetic = syntheticCheck(location, provider);
            String state = synthetic.blocked() && synthetic.allowed() && synthetic.validJson()
                    ? (provider.requiresRealValidation() ? "DEGRADED" : "HEALTHY") : "BROKEN";
            return status(provider, state, config, true, count, launcherPresent, profilePresent,
                    synthetic.blocked() && synthetic.allowed(), state.equals("HEALTHY") ? 0 : 1);
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
    public ProviderResult uninstall(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) {
            return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_UNINSTALL_RESULT", 2);
        }
        Path config = provider.configurationPath(location.root());
        Path metadata = metadata(location, provider);
        if (!Files.exists(config) && !Files.exists(metadata)) {
            return simple(provider, "PROVIDER_UNINSTALL_RESULT", "NOT_INSTALLED", 0);
        }
        try {
            boolean removed = false;
            if (Files.exists(config)) {
                Map<String, Object> root = readObject(config);
                Map<String, Object> group = object(root.get(provider.hookGroup()));
                if (group != null) {
                    List<Object> hooks = list(group.get("PreToolUse"));
                    removed = hooks.removeIf(provider::isManagedHook);
                    if (hooks.isEmpty()) {
                        group.remove("PreToolUse");
                    }
                    if (group.isEmpty()) {
                        root.remove(provider.hookGroup());
                    }
                    if (root.isEmpty()) {
                        Files.deleteIfExists(config);
                    } else {
                        atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
                    }
                }
            }
            removeMcpConfig(location, provider);
            Files.deleteIfExists(metadata);
            try {
                new ProviderSessionBindingService().revoke(location, provider.id());
            } catch (ProviderSessionBindingService.BindingException ignored) {
                // Status exposes a broken binding; managed hook removal remains complete.
            }
            return simple(provider, "PROVIDER_UNINSTALL_RESULT", "SUCCESS", 0,
                    "MANAGED_HOOK_REMOVED", Boolean.toString(removed), "UNRELATED_CONFIGURATION_PRESERVED", "true");
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_UNINSTALL_RESULT", 10);
        } catch (Exception failure) {
            return failure(id, "UNINSTALL_FAILED", "PROVIDER_UNINSTALL_RESULT", 10);
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
    @SuppressWarnings("unchecked")
    public String ensureMcpConfig(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider, Path launcher) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath == null) {
            return "UNSUPPORTED";
        }
        try {
            if ("codex".equals(provider.id())) {
                CodexTomlConfiguration.Inspection before = CodexTomlConfiguration.inspect(configPath, launcher);
                if (before.outcome() == CodexTomlConfiguration.Outcome.MALFORMED
                        || before.outcome() == CodexTomlConfiguration.Outcome.DUPLICATE_SYNSESIS_ENTRY) return "MALFORMED_CONFIG";
                CodexTomlConfiguration.Inspection after = CodexTomlConfiguration.upsert(configPath, launcher);
                return before.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE
                        && after.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE ? "UNCHANGED" : "INSTALLED";
            }
            Map<String, Object> managedEntry = provider.managedMcpServer(launcher, location.root());
            Map<String, Object> root = Files.exists(configPath) ? readObject(configPath) : new LinkedHashMap<>();

            Map<String, Object> mcpServers = root.containsKey("mcpServers") && root.get("mcpServers") instanceof Map<?, ?>
                    ? new LinkedHashMap<>((Map<String, Object>) root.get("mcpServers"))
                    : new LinkedHashMap<>();

            Object existing = mcpServers.get("synesis");
            boolean unchanged = managedEntry.equals(existing);
            if (!unchanged) {
                mcpServers.put("synesis", managedEntry);
                root.put("mcpServers", mcpServers);
                atomicWrite(configPath, ProviderJson.write(root) + System.lineSeparator());
            }

            if ("antigravity".equals(provider.id())) {
                String userHome = System.getProperty("user.home");
                if (userHome != null && !userHome.isBlank()) {
                    Path secondaryConfig = Path.of(userHome, ".gemini", "antigravity", "mcp_config.json");
                    if (Files.exists(secondaryConfig) || Files.isDirectory(secondaryConfig.getParent())) {
                        Map<String, Object> secRoot = Files.exists(secondaryConfig) ? readObject(secondaryConfig) : new LinkedHashMap<>();
                        Map<String, Object> secServers = secRoot.containsKey("mcpServers") && secRoot.get("mcpServers") instanceof Map<?, ?>
                                ? new LinkedHashMap<>((Map<String, Object>) secRoot.get("mcpServers"))
                                : new LinkedHashMap<>();
                        if (!managedEntry.equals(secServers.get("synesis"))) {
                            secServers.put("synesis", managedEntry);
                            secRoot.put("mcpServers", secServers);
                            atomicWrite(secondaryConfig, ProviderJson.write(secRoot) + System.lineSeparator());
                        }
                    }
                }

            }

            // Clean up obsolete project-local MCP replication files so project repositories stay clean
            cleanObsoleteProjectMcpFile(location.root().resolve(".agents/mcp.json"));
            cleanObsoleteProjectMcpFile(location.root().resolve(".gemini/mcp.json"));

            return unchanged ? "UNCHANGED" : "INSTALLED";
        } catch (Exception failure) {
            return "MALFORMED_CONFIG";
        }
    }

    private void cleanObsoleteProjectMcpFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root = readObject(path);
            if (root.get("mcpServers") instanceof Map<?, ?> mcpMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mcpServers = new LinkedHashMap<>((Map<String, Object>) mcpMap);
                mcpServers.remove("synesis");
                if (mcpServers.isEmpty()) {
                    root.remove("mcpServers");
                } else {
                    root.put("mcpServers", mcpServers);
                }
                if (root.isEmpty()) {
                    Files.deleteIfExists(path);
                } else {
                    atomicWrite(path, ProviderJson.write(root) + System.lineSeparator());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void removeMcpConfig(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath != null && Files.exists(configPath)) {
            try {
                if ("codex".equals(provider.id())) {
                    CodexTomlConfiguration.remove(configPath);
                    return;
                }
                Map<String, Object> root = readObject(configPath);
                if (root.get("mcpServers") instanceof Map<?, ?> mcpMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mcpServers = new LinkedHashMap<>((Map<String, Object>) mcpMap);
                    mcpServers.remove("synesis");
                    if (mcpServers.isEmpty()) {
                        root.remove("mcpServers");
                    } else {
                        root.put("mcpServers", mcpServers);
                    }
                    atomicWrite(configPath, ProviderJson.write(root) + System.lineSeparator());
                }
            } catch (Exception ignored) {
            }
        }
        if ("antigravity".equals(provider.id())) {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank()) {
                Path secondaryConfig = Path.of(userHome, ".gemini", "antigravity", "mcp_config.json");
                if (Files.exists(secondaryConfig)) {
                    try {
                        Map<String, Object> root = readObject(secondaryConfig);
                        if (root.get("mcpServers") instanceof Map<?, ?> mcpMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mcpServers = new LinkedHashMap<>((Map<String, Object>) mcpMap);
                            mcpServers.remove("synesis");
                            if (mcpServers.isEmpty()) {
                                root.remove("mcpServers");
                            } else {
                                root.put("mcpServers", mcpServers);
                            }
                            atomicWrite(secondaryConfig, ProviderJson.write(root) + System.lineSeparator());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            cleanObsoleteProjectMcpFile(location.root().resolve(".agents/mcp.json"));
            cleanObsoleteProjectMcpFile(location.root().resolve(".gemini/mcp.json"));
        }
    }

    /**
     * Runs provider diagnostics for doctor.
     *
     * @param location project location
     * @return structured report
     */
    public DoctorResult diagnose(ProjectApplicationService.ProjectLocation location) {
        List<String> lines = new ArrayList<>();
        boolean broken = false;
        for (ProviderIntegration provider : ProviderRegistry.providers()) {
            ProviderResult result = status(location, provider.id());
            String state = result.values()
                    .getOrDefault("PROVIDER_STATUS",
                            result.values()
                                    .getOrDefault("ERROR", "BROKEN"));
            if ("BROKEN".equals(state) || "INVALID_CONFIG".equals(state)) {
                broken = true;
            }
            if (!"NOT_INSTALLED".equals(state)) {
                lines.add("PROVIDER_" + provider.id()
                        .toUpperCase()
                        .replace('-', '_') + "=" + state);
            }
        }
        lines.add("WARN=Antigravity run_command mutations are not inspected.");
        lines.add("WARN=Antigravity real-agent re-planning validation is not completed.");
        lines.add("WARN=Claude Code integration remains EXPERIMENTAL.");
        lines.add("WARN=Codex project hooks require explicit trust and real-agent validation.");
        boolean recordsHealthy = recordStoreHealthy(location);
        if (!recordsHealthy) {
            broken = true;
        }
        lines.add("RECORD_STORE=" + (recordsHealthy ? "PASS" : "FAIL"));
        return new DoctorResult(broken ? "BROKEN" : "HEALTHY_WITH_WARNINGS", List.copyOf(lines));
    }

    private ProviderIntegration provider(String id) {
        return ProviderRegistry.find(id);
    }

    private ProviderIntegration.SyntheticCheck syntheticCheck(ProjectApplicationService.ProjectLocation location,
            ProviderIntegration provider) throws Exception {
        Path root = Files.createTempDirectory("synesis-provider-check-");
        try {
            Files.createDirectories(root);
            ProjectApplicationService projectService = new ProjectApplicationService();
            var fixture = projectService.init(root)
                    .location();
            UUID projectId = fixture.projectId();
            new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64))).save(fixture.profile()
                    .resolve("project.conf"));
            new ConstraintApplicationService().create(fixture,
                    "Synthetic protected file",
                    "Synthetic check",
                    "src/protected.txt",
                    ProjectConstraint.Effect.BLOCK);
            return provider.syntheticCheck(fixture.profile(), fixture.root());
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
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

    /**
     * Structured doctor provider result.
     *
     * @param result overall state
     * @param lines  diagnostic lines
     */
    public record DoctorResult(String result, List<String> lines) {

        /**
         * Copies diagnostic lines.
         */
        public DoctorResult {
            lines = List.copyOf(lines);
        }
    }
}
