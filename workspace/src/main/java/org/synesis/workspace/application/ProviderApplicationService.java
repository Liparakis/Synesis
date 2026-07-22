package org.synesis.workspace.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
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

/** Owns provider lifecycle, local metadata, configuration merging, and diagnostics. */
public final class ProviderApplicationService {
    private static final int METADATA_SCHEMA = 1;

    /** Creates the default provider service. */
    public ProviderApplicationService() {
    }

    /**
     * Lists the currently implemented providers.
     * @param location project location
     * @return provider rows
     */
    public List<ProviderRow> list(ProjectApplicationService.ProjectLocation location) {
        Objects.requireNonNull(location, "location");
        return ProviderRegistry.providers().stream().map(provider -> new ProviderRow(provider.id(), provider.supportLevel(),
                Files.exists(metadata(location, provider)) ? "INSTALLED" : "NOT_INSTALLED")).toList();
    }

    /**
     * Installs or updates one provider.
     * @param location project location
     * @param id provider ID
     * @return structured result
     */
    public ProviderResult install(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_INSTALL_RESULT", 2);
        try {
            Path launcher = launcher();
            Path profile = location.profile();
            if (!Files.isDirectory(profile)) return failure(id, "PROFILE_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            if (!Files.isRegularFile(launcher)) return failure(id, "LAUNCHER_MISSING", "PROVIDER_INSTALL_RESULT", 10);
            Path config = provider.configurationPath(location.root());
            Path metadataPath = metadata(location, provider);
            if (Files.exists(metadataPath)) {
                Map<String, Object> oldMetadata = readObject(metadataPath);
                if (!schemaVersion(oldMetadata.get("schemaVersion"))) {
                    return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_INSTALL_RESULT", 10);
                }
            }
            Map<String, Object> root = readObject(config);
            Map<String, Object> group = object(root.computeIfAbsent(provider.hookGroup(), ignored -> new LinkedHashMap<>()));
            List<Object> hooks = list(group.computeIfAbsent("PreToolUse", ignored -> new ArrayList<>()));
            Map<String, Object> expectedHook = provider.managedHook(launcher, profile);
            boolean already = hooks.stream().filter(provider::isManagedHook).count() == 1
                    && expectedHook.equals(hooks.stream().filter(provider::isManagedHook).findFirst().orElse(null))
                    && Files.exists(metadata(location, provider));
            hooks.removeIf(provider::isManagedHook);
            hooks.add(expectedHook);
            atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
            ProviderIntegration.SyntheticCheck synthetic = syntheticCheck(location, provider);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", METADATA_SCHEMA);
            metadata.put("provider", provider.id());
            metadata.put("supportLevel", provider.supportLevel().name());
            metadata.put("installedAt", Instant.now().toString());
            metadata.put("configurationPath", config.toAbsolutePath().normalize().toString());
            metadata.put("launcherPath", launcher.toAbsolutePath().normalize().toString());
            metadata.put("profilePath", profile.toAbsolutePath().normalize().toString());
            metadata.put("managedEntryId", provider.managedHookId());
            metadata.put("lastSyntheticCheck", synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED");
            atomicWrite(metadata(location, provider), ProviderJson.write(metadata) + System.lineSeparator());
            String result = synthetic.blocked() && synthetic.allowed() && synthetic.validJson()
                    && !provider.requiresRealValidation()
                    ? (already ? "ALREADY_INSTALLED" : "SUCCESS") : "DEGRADED";
            return result(provider, "PROVIDER_INSTALL_RESULT", result, synthetic, config, profile, launcher, 0);
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_INSTALL_RESULT", 10);
        } catch (Exception failure) {
            return failure(id, "INSTALL_FAILED", "PROVIDER_INSTALL_RESULT", 10);
        }
    }

    /**
     * Inspects one provider without repairing it.
     * @param location project location
     * @param id provider ID
     * @return structured result
     */
    public ProviderResult status(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_STATUS", 2);
        Path config = provider.configurationPath(location.root());
        Path metadataPath = metadata(location, provider);
        boolean metadataPresent = Files.isRegularFile(metadataPath);
        boolean configPresent = Files.isRegularFile(config);
        if (!metadataPresent && !configPresent) return status(provider, "NOT_INSTALLED", config, false, 0, false, false, false, 0);
        try {
            Map<String, Object> root = configPresent ? readObject(config) : Map.of();
            int count = configPresent ? managedEntries(root, provider).size() : 0;
            Map<String, Object> metadata = metadataPresent ? readObject(metadataPath) : Map.of();
            boolean schemaValid = !metadataPresent || schemaVersion(metadata.get("schemaVersion"));
            boolean validMetadata = metadataPresent && schemaValid && provider.id().equals(String.valueOf(metadata.get("provider")))
                    && provider.supportLevel().name().equals(String.valueOf(metadata.get("supportLevel")));
            boolean launcherPresent = validMetadata && Files.isRegularFile(Path.of(String.valueOf(metadata.get("launcherPath"))));
            boolean profilePresent = validMetadata && Files.isDirectory(Path.of(String.valueOf(metadata.get("profilePath"))));
            boolean configurationCorrect = count == 1 && launcherPresent && profilePresent
                    && managedCommandMatches(root, provider, Path.of(String.valueOf(metadata.get("launcherPath"))),
                            Path.of(String.valueOf(metadata.get("profilePath"))));
            if (!schemaValid) return failure(id, "OBSOLETE_PROVIDER_STATE", "PROVIDER_STATUS", 3);
            if (!configPresent) return status(provider, "BROKEN", config, metadataPresent, count, launcherPresent, profilePresent, false, 3);
            if (!validMetadata || !configurationCorrect) {
                return status(provider, metadataPresent ? "DEGRADED" : "DEGRADED", config, metadataPresent, count, launcherPresent, profilePresent, false, 1);
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
     * @param location project location
     * @param id provider ID
     * @return structured result
     */
    public ProviderResult uninstall(ProjectApplicationService.ProjectLocation location, String id) {
        ProviderIntegration provider = provider(id);
        if (provider == null) return failure(id, "UNKNOWN_PROVIDER", "PROVIDER_UNINSTALL_RESULT", 2);
        Path config = provider.configurationPath(location.root());
        Path metadata = metadata(location, provider);
        if (!Files.exists(config) && !Files.exists(metadata)) return simple(provider, "PROVIDER_UNINSTALL_RESULT", "NOT_INSTALLED", 0);
        try {
            boolean removed = false;
            if (Files.exists(config)) {
                Map<String, Object> root = readObject(config);
                Map<String, Object> group = object(root.get(provider.hookGroup()));
                if (group != null) {
                    List<Object> hooks = list(group.get("PreToolUse"));
                    removed = hooks.removeIf(provider::isManagedHook);
                    if (hooks.isEmpty()) group.remove("PreToolUse");
                    if (group.isEmpty()) root.remove(provider.hookGroup());
                    if (root.isEmpty()) Files.deleteIfExists(config);
                    else atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
                }
            }
            Files.deleteIfExists(metadata);
            return simple(provider, "PROVIDER_UNINSTALL_RESULT", "SUCCESS", 0,
                    "MANAGED_HOOK_REMOVED", Boolean.toString(removed), "UNRELATED_CONFIGURATION_PRESERVED", "true");
        } catch (IllegalArgumentException failure) {
            return failure(id, "INVALID_CONFIG", "PROVIDER_UNINSTALL_RESULT", 10);
        } catch (Exception failure) {
            return failure(id, "UNINSTALL_FAILED", "PROVIDER_UNINSTALL_RESULT", 10);
        }
    }

    /**
     * Runs provider diagnostics for doctor.
     * @param location project location
     * @return structured report
     */
    public DoctorResult diagnose(ProjectApplicationService.ProjectLocation location) {
        List<String> lines = new ArrayList<>();
        boolean broken = false;
        for (ProviderIntegration provider : ProviderRegistry.providers()) {
            ProviderResult result = status(location, provider.id());
            String state = result.values().getOrDefault("PROVIDER_STATUS", result.values().getOrDefault("ERROR", "BROKEN"));
            if ("BROKEN".equals(state) || "INVALID_CONFIG".equals(state)) broken = true;
            if (!"NOT_INSTALLED".equals(state)) lines.add("PROVIDER_" + provider.id().toUpperCase().replace('-', '_') + "=" + state);
        }
        lines.add("WARN=Antigravity run_command mutations are not inspected.");
        lines.add("WARN=Antigravity real-agent re-planning validation is not completed.");
        lines.add("WARN=Claude Code integration remains EXPERIMENTAL.");
        lines.add("WARN=Codex project hooks require explicit trust and real-agent validation.");
        boolean recordsHealthy = recordStoreHealthy(location);
        if (!recordsHealthy) broken = true;
        lines.add("RECORD_STORE=" + (recordsHealthy ? "PASS" : "FAIL"));
        return new DoctorResult(broken ? "BROKEN" : "HEALTHY_WITH_WARNINGS", List.copyOf(lines));
    }

    private ProviderIntegration provider(String id) { return ProviderRegistry.find(id); }

    private ProviderIntegration.SyntheticCheck syntheticCheck(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) throws Exception {
        Path root = Files.createTempDirectory("synesis-provider-check-");
        try {
            Files.createDirectories(root);
            ProjectApplicationService projectService = new ProjectApplicationService();
            var fixture = projectService.init(root).location();
            UUID projectId = fixture.projectId();
            new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64))).save(fixture.profile().resolve("project.conf"));
            new ConstraintApplicationService().create(fixture, "Synthetic protected file", "Synthetic check", "src/protected.txt", ProjectConstraint.Effect.BLOCK);
            return provider.syntheticCheck(fixture.profile(), fixture.root());
        } finally {
            try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); }
        }
    }

    private static Map<String, Object> readObject(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        Object parsed = ProviderJson.parse(Files.readString(path));
        return object(parsed) == null ? throwInvalid() : object(parsed);
    }

    private static Map<String, Object> throwInvalid() { throw new IllegalArgumentException("JSON object expected"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { if (value == null) return new ArrayList<>(); if (value instanceof List<?> list) return (List<Object>) list; throw new IllegalArgumentException("JSON array expected"); }

    private static List<Object> managedEntries(Map<String, Object> root, ProviderIntegration provider) {
        Map<String, Object> group = object(root.get(provider.hookGroup()));
        return group == null ? List.of() : list(group.get("PreToolUse")).stream().filter(provider::isManagedHook).toList();
    }

    private static boolean managedCommandMatches(Map<String, Object> root, ProviderIntegration provider, Path launcher, Path profile) {
        List<Object> entries = managedEntries(root, provider);
        if (entries.size() != 1) return false;
        Map<String, Object> hook = object(entries.getFirst());
        if (hook == null || !provider.matcher().equals(hook.get("matcher"))) return false;
        List<Object> commands = list(hook.get("hooks"));
        if (commands.size() != 1) return false;
        Map<String, Object> command = object(commands.getFirst());
        if (command == null || !provider.hookCommand(launcher, profile).equals(command.get("command"))) return false;
        String windowsCommand = provider.windowsHookCommand(launcher, profile);
        return windowsCommand == null || windowsCommand.equals(command.get("commandWindows"));
    }

    private static boolean schemaVersion(Object value) {
        return value instanceof Number number && number.doubleValue() == METADATA_SCHEMA;
    }

    private static boolean recordStoreHealthy(ProjectApplicationService.ProjectLocation location) {
        Path records = location.profile().resolve("records");
        if (!Files.isDirectory(records)) return false;
        try (var paths = Files.walk(records)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".sdr")).toList()) {
                DecisionRecord record = DecisionRecord.decode(Files.readAllBytes(path));
                if (!record.verify()) return false;
            }
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String quote(Path path) { return "\"" + path.toAbsolutePath().normalize().toString().replace("\"", "\\\"") + "\""; }
    private static Path metadata(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) { return location.synesisDirectory().resolve("local/providers/" + provider.id() + ".json"); }
    private static Path launcher() {
        String executable = isWindows() ? "synesis.cmd" : "synesis";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(entry).resolve(executable).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        String configured = System.getProperty("synesis.launcher", System.getenv("SYNESIS_LAUNCHER"));
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path fallback = stableLauncher(executable);
        return fallback;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static Path stableLauncher(String executable) {
        String base;
        if (isWindows()) {
            base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) base = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        } else if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support").toString();
        } else {
            base = System.getenv("XDG_DATA_HOME");
            if (base == null || base.isBlank()) base = Path.of(System.getProperty("user.home"), ".local", "share").toString();
        }
        return Path.of(base, "Synesis", "bin", executable).toAbsolutePath().normalize();
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent()); Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try { Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temporary); }
    }

    private static ProviderResult result(ProviderIntegration provider, String key, String state, ProviderIntegration.SyntheticCheck synthetic, Path config, Path profile, Path launcher, int exit) {
        return simple(provider, key, state, exit, "PROVIDER", provider.id(), "SUPPORT_LEVEL", provider.supportLevel().name(), "CONFIG_PATH", config.toString(), "PROFILE_PATH", profile.toString(), "MANAGED_HOOK_PRESENT", "true", "SYNTHETIC_CHECK", synthetic.blocked() && synthetic.allowed() && synthetic.validJson() ? "PASSED" : "FAILED", "TRUST_STATUS", provider.trustStatus(), "REAL_AGENT_VALIDATION", "NOT_COMPLETED");
    }
    private static ProviderResult status(ProviderIntegration provider, String state, Path config, boolean metadata, int count, boolean launcher, boolean profile, boolean synthetic, int exit) { return simple(provider, "PROVIDER_STATUS", state, exit, "PROVIDER", provider.id(), "SUPPORT_LEVEL", provider.supportLevel().name(), "METADATA_PRESENT", Boolean.toString(metadata), "CONFIG_PRESENT", Boolean.toString(Files.isRegularFile(config)), "MANAGED_HOOK_COUNT", Integer.toString(count), "LAUNCHER_PRESENT", Boolean.toString(launcher), "PROFILE_PRESENT", Boolean.toString(profile), "SYNTHETIC_BLOCK_CHECK", synthetic ? "PASSED" : "NOT_RUN", "TRUST_STATUS", provider.trustStatus(), "REAL_AGENT_VALIDATION", "NOT_COMPLETED"); }
    private static ProviderResult simple(ProviderIntegration provider, String key, String state, int exit, String... fields) { Map<String, String> values = new LinkedHashMap<>(); values.put(key, state); for (int i = 0; i + 1 < fields.length; i += 2) values.put(fields[i], fields[i + 1]); return new ProviderResult(exit, values); }
    private static ProviderResult failure(String id, String error, String key, int exit) { Map<String, String> values = new LinkedHashMap<>(); values.put(key, error); values.put("ERROR", error); if (id != null) values.put("PROVIDER", id); return new ProviderResult(exit, values); }

    /** Provider list row.
     * @param id provider ID
     * @param supportLevel maturity
     * @param status local state
     */
    public record ProviderRow(String id, ProviderSupportLevel supportLevel, String status) { }
    /** Structured provider operation result.
     * @param exitCode process code
     * @param values machine-readable fields
     */
    public record ProviderResult(int exitCode, Map<String, String> values) {
        /** Copies the result fields. */
        public ProviderResult { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    /** Structured doctor provider result.
     * @param result overall state
     * @param lines diagnostic lines
     */
    public record DoctorResult(String result, List<String> lines) {
        /** Copies diagnostic lines. */
        public DoctorResult { lines = List.copyOf(lines); }
    }
}
