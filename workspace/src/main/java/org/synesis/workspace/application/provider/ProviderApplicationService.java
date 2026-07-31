package org.synesis.workspace.application.provider;

import org.synesis.workspace.application.constraint.ConstraintApplicationService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.domain.ProjectConstraint;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderRegistry;
import org.synesis.workspace.provider.ProviderSupportLevel;
import org.synesis.workspace.provider.antigravity.AntigravityProviderIntegration;
import org.synesis.workspace.provider.codex.CodexTomlConfiguration;

/**
 * Owns provider lifecycle, local metadata, configuration merging, and diagnostics.
 */
public final class ProviderApplicationService {

    private static final int METADATA_SCHEMA = 1;
    private final ProviderManualService manualService = new ProviderManualService();

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
                values.put("HOOK_INTERCEPTED", Boolean.toString(hasEvidence));
                values.put("DECISION", hasEvidence ? "ALLOW" : "UNKNOWN");
                values.put("MUTATION_WITHOUT_ALLOW_POSSIBLE", Boolean.toString(!hasEvidence));
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
        Object configuredWindows = command.containsKey("commandWindows")
                ? command.get("commandWindows")
                : command.get("command_windows");
        if (configuredWindows == null) {
            configuredWindows = command.get("commandWindows");
        }
        return windowsCommand == null || windowsCommand.equals(configuredWindows);
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

    /**
     * Resolves the native stdio MCP launcher when the local distribution
     * provides it, falling back to the CLI launcher for development installs.
     *
     * @return native MCP launcher or development fallback
     */
    public Path mcpLauncherPath() {
        String executable = isWindows() ? "synesis-mcp.exe" : "synesis-mcp";
        String configured = System.getProperty("synesis.mcp.launcher", System.getenv("SYNESIS_MCP_LAUNCHER"));
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (isWindows() && localAppData != null && !localAppData.isBlank()) {
            Path installed = Path.of(localAppData, "Synesis", "bin", executable);
            if (Files.isRegularFile(installed)) {
                return installed.toAbsolutePath().normalize();
            }
        }
        Path cli = launcher();
        Path candidate = cli.getParent() == null ? Path.of(executable) : cli.getParent().resolve(executable);
        return Files.isRegularFile(candidate) ? candidate.toAbsolutePath().normalize() : cli;
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

    static void materializeHook(Path worktree, ProviderIntegration provider, Path launcher, Path profile)
            throws IOException {
        Path config = provider.configurationPath(worktree);
        Map<String, Object> root = Files.exists(config) ? readObject(config) : new LinkedHashMap<>();
        Map<String, Object> group = object(root.computeIfAbsent(provider.hookGroup(),
                ignored -> new LinkedHashMap<>()));
        List<Object> hooks = list(group.computeIfAbsent("PreToolUse", ignored -> new ArrayList<>()));
        hooks.removeIf(provider::isManagedHook);
        hooks.add(provider.managedHook(launcher, profile));
        List<Object> sessionHooks = list(group.get("SessionStart"));
        sessionHooks.removeIf(provider::isManagedSessionHook);
        Map<String, Object> sessionHook = provider.managedSessionHook(launcher, profile);
        if (sessionHook != null) {
            sessionHooks.add(sessionHook);
        }
        group.put("SessionStart", sessionHooks);
        atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
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
                "MCP_EVIDENCE_TIER",
                provider.mcpEvidenceTier()
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
                "MCP_EVIDENCE_TIER",
                provider.mcpEvidenceTier()
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
            ProviderManualService.Attestation manual = manualService.install(provider.id());
            if (!manual.valid()) {
                return withValue(failure(id, "MANUAL_INSTALL_FAILED", "PROVIDER_INSTALL_RESULT", 10),
                        "MANUAL_REASON", manual.reason());
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
            List<Object> sessionHooks = list(group.get("SessionStart"));
            sessionHooks.removeIf(provider::isManagedSessionHook);
            Map<String, Object> expectedSessionHook = provider.managedSessionHook(launcher, profile);
            if (expectedSessionHook != null) {
                sessionHooks.add(expectedSessionHook);
            }
            if (sessionHooks.isEmpty()) {
                group.remove("SessionStart");
            } else {
                group.put("SessionStart", sessionHooks);
            }
            atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
            Path mcpLauncher = mcpLauncherPath();
            String mcpStatus = ensureMcpConfig(location, provider, mcpLauncher);
            McpHealth health = probeMcp(mcpLauncher, provider.id(), location.root());
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
            metadata.put("mcpLauncherPath",
                    mcpLauncher.toAbsolutePath()
                            .normalize()
                            .toString());
            metadata.put("profilePath",
                    profile.toAbsolutePath()
                            .normalize()
                            .toString());
            metadata.put("managedEntryId", provider.managedHookId());
            metadata.put("mcpConfigStatus", mcpStatus);
            metadata.put("mcpHealth", health.status());
            metadata.put("manualVersion", manual.version());
            metadata.put("manualContentHash", manual.contentHash());
            metadata.put("manualPath", manualService.skillDirectory(provider.id()).resolve("SKILL.md").toString());
            metadata.put("lastSyntheticCheck",
                    synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED");
            atomicWrite(metadata(location, provider), ProviderJson.write(metadata) + System.lineSeparator());
            String result = synthetic.blocked() && synthetic.allowed() && synthetic.validJson()
                    && health.passed()
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
            installedResult = withValue(installedResult, "SYNESIS_MANUAL", "ATTESTED");
            installedResult = withValue(installedResult, "MCP_HEALTH", health.status());
            try {
                ProviderSessionBindingService.BindingResult ensured = new ProviderSessionBindingService().ensure(
                        location, provider.id(), null);
                if (ensured.binding()
                        .worktreePath() != null) {
                    materializeHook(Path.of(ensured.binding()
                            .worktreePath()), provider, launcher, profile);
                }
                return decorate(location, provider.id(), installedResult, ensured);
            } catch (ProviderSessionBindingService.BindingException bindingFailure) {
                return decorate(location, provider.id(), installedResult, null);
            }
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_INSTALL_RESULT", 10);
        } catch (Exception failure) {
            return failure(id, "INSTALL_FAILED", "PROVIDER_INSTALL_RESULT", 10);
        }
    }

    private static ProviderResult withValue(ProviderResult result, String key, String value) {
        Map<String, String> values = new LinkedHashMap<>(result.values());
        values.put(key, value);
        return new ProviderResult(result.exitCode(), values);
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
                values.put("MCP_CONFIG_STATUS",
                        inspection.outcome()
                                .name());
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
        ProviderManualService.Attestation manual = manualService.attest(provider.id());
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
            ProviderResult result = status(provider, state, config, true, count, launcherPresent, profilePresent,
                    synthetic.blocked() && synthetic.allowed(), state.equals("HEALTHY") ? 0 : 1);
            return withValue(result, "SYNESIS_MANUAL", manual.valid() ? "ATTESTED" : manual.reason());
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
                    List<Object> sessionHooks = list(group.get("SessionStart"));
                    boolean removedSession = sessionHooks.removeIf(provider::isManagedSessionHook);
                    removed = removed || removedSession;
                    if (sessionHooks.isEmpty()) {
                        group.remove("SessionStart");
                    } else {
                        group.put("SessionStart", sessionHooks);
                    }
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
    private final ProviderMcpConfigurationService mcpConfiguration = new ProviderMcpConfigurationService();

    /**
     * Ensures provider-neutral Model Context Protocol (MCP) server configuration is installed.
     *
     * @param location project location
     * @param provider provider integration
     * @param launcher stable launcher path
     * @return installation status identifier
     */
    public String ensureMcpConfig(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider,
            Path launcher) {
        return mcpConfiguration.ensure(location, provider, launcher);
    }

    /**
     * Performs a bounded read-only MCP transport probe against the installed launcher.
     *
     * @param launcher native MCP launcher
     * @param provider provider identifier
     * @param project project root supplied to the server
     * @return probe outcome
     */
    private McpHealth probeMcp(Path launcher, String provider, Path project) {
        Process process = null;
        try {
            List<String> command = List.of(launcher.toAbsolutePath().normalize().toString(), "mcp",
                    "--provider", provider, "--project", project.toAbsolutePath().normalize().toString());
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8)); BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"synesis-installer\",\"version\":\"1\"}}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
                writer.flush();
                String initialize = readWithTimeout(reader);
                String tools = readWithTimeout(reader);
                Map<?, ?> initializeMap = object(ProviderJson.parse(initialize));
                Map<?, ?> toolsMap = object(ProviderJson.parse(tools));
                Map<?, ?> initializeResult = object(initializeMap.get("result"));
                Map<?, ?> toolsResult = object(toolsMap.get("result"));
                Object advertised = toolsResult.get("tools");
                if (initializeResult.isEmpty() || !(advertised instanceof List<?> list) || list.size() != 10) {
                    return new McpHealth(false, "FAILED:unexpected_tools_or_initialize");
                }
                return new McpHealth(true, "PASSED");
            }
        } catch (TimeoutException failure) {
            return new McpHealth(false, "FAILED:timeout");
        } catch (Exception failure) {
            return new McpHealth(false, "FAILED:" + failure.getClass().getSimpleName());
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    private static String readWithTimeout(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private record McpHealth(boolean passed, String status) {
    }

    private void removeMcpConfig(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) {
        mcpConfiguration.remove(location, provider);
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
            var fixture = projectService.init(root, false)
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
