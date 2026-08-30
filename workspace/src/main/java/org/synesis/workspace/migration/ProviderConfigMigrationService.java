package org.synesis.workspace.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderRegistry;
import org.synesis.workspace.provider.codex.CodexTomlConfiguration;

/**
 * Plans and executes compare-and-set migrations of provider MCP configuration.
 *
 * <p>The service is deliberately limited to the Synesis MCP entry. Malformed,
 * ambiguous, or changed source files are never rewritten.
 */
@SuppressWarnings("MultipleOccurrences")
public final class ProviderConfigMigrationService {

    private final Path adminRoot;
    private final Path launcher;

    /**
     * Creates a service using the global Synesis administrative root.
     */
    public ProviderConfigMigrationService() {
        this(defaultAdminRoot(), stableLauncherPath());
    }

    /**
     * Creates a service with explicit administrative and stable-launcher paths.
     *
     * @param adminRoot administrative root
     * @param launcher  stable launcher
     */
    public ProviderConfigMigrationService(Path adminRoot, Path launcher) {
        this.adminRoot = Objects.requireNonNull(adminRoot, "adminRoot")
                .toAbsolutePath()
                .normalize();
        this.launcher = Objects.requireNonNull(launcher, "launcher")
                .toAbsolutePath()
                .normalize();
    }

    private static ProviderIntegration provider(String id) {
        if (!"codex".equals(id)) {
            throw new IllegalArgumentException("unsupported provider");
        }
        return ProviderRegistry.find(id);
    }

    private static Map<String, Object> read(Path path) throws IOException {
        return read(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String raw) {
        Object value = ProviderJson.parse(raw);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("provider root must be object");
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private static String hash(Path path) throws IOException {
        return hash(Files.readString(path));
    }

    private static String safeHash(Path path) {
        try {
            return hash(path);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean sourceMismatches(Entry current, Entry planned) {
        return !current.sourceHash()
                .equals(planned.sourceHash());
    }

    private static Path configPath(Entry planned) {
        return Path.of(planned.configPath());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean equivalent(Object expected, Object actual) {
        if (expected instanceof Number a && actual instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        if (expected instanceof Map<?, ?> a && actual instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!b.containsKey(entry.getKey()) || !equivalent(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> a && actual instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equivalent(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void append(Path path, String text) throws IOException {
        Files.writeString(path,
                text + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void atomicReplace(Path path, String text) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            write(tmp, text);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String planJson(Plan plan) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("planId", plan.planId());
        root.put("createdAt",
                plan.createdAt()
                        .toString());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Entry e : plan.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", e.provider());
            m.put("configPath", e.configPath());
            m.put("sourceHash", e.sourceHash());
            m.put("outcome",
                    e.outcome()
                            .name());
            m.put("launcher", e.launcher());
            entries.add(m);
        }
        root.put("entries", entries);
        return ProviderJson.write(root);
    }

    private static Path defaultAdminRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), "AppData", "Local")
                    .toString();
        }
        return Path.of(base, "Synesis", "admin");
    }

    private static Path stableLauncherPath() {
        String configured = System.getProperty("synesis.launcher", System.getenv("SYNESIS_LAUNCHER"));
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured)
                    .toAbsolutePath()
                    .normalize();
            String text = candidate.toString()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!text.contains("\\versions\\") && !text.contains("/versions/") && !text.contains("\\payloads\\")
                    && !text.contains("/payloads/")) {
                return candidate;
            }
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        String base = windows ? System.getenv("LOCALAPPDATA") : System.getenv("XDG_DATA_HOME");
        if (base == null || base.isBlank()) {
            base = windows ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                             .toString() : Path.of(System.getProperty("user.home"), ".local", "share")
                                           .toString();
        }
        Path bin = Path.of(base, "Synesis", "bin");
        Path nativeMcp = bin.resolve(windows ? "synesis-mcp.exe" : "synesis-mcp");
        return (Files.isRegularFile(nativeMcp) ? nativeMcp : bin.resolve(windows ? "synesis.cmd" : "synesis"))
                .toAbsolutePath()
                .normalize();
    }

    private static boolean isReparsePoint(Path path) {
        try {
            return Boolean.TRUE.equals(Files.getAttribute(path, "dos:reparsePoint"));
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Inspects the supported providers without changing files.
     *
     * @return provider entries
     */
    public List<Entry> inspect() {
        return List.of(inspect(ProviderRegistry.find("codex")));
    }

    /**
     * Inspects one supported provider without changing files.
     *
     * @param providerId provider identifier
     * @return provider entry
     */
    public Entry inspect(String providerId) {
        return inspect(provider(providerId));
    }

    /**
     * Creates and persists an immutable plan for the supported providers.
     *
     * @return prepared plan
     * @throws IOException if the plan cannot be persisted
     */
    public Plan prepare() throws IOException {
        Plan plan = new Plan("pmig-" + UUID.randomUUID()
                .toString()
                .replace("-", ""), Instant.now(), inspect());
        Path plans = adminRoot.resolve("migration-plans");
        Files.createDirectories(plans);
        write(planPath(plans, plan.planId()), planJson(plan));
        return plan;
    }

    /**
     * Loads a previously prepared plan.
     *
     * @param planId plan identifier
     * @return persisted plan
     * @throws IOException if the plan is missing or invalid
     */
    public Plan load(String planId) throws IOException {
        if (planId == null || !planId.matches("pmig-[a-zA-Z0-9]+")) {
            throw new IOException("invalid migration plan");
        }
        Object value = ProviderJson.parse(Files.readString(planPath(adminRoot.resolve("migration-plans"), planId)));
        if (!(value instanceof Map<?, ?> map) || !(map.get("entries") instanceof List<?> values)) {
            throw new IOException("invalid migration plan");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> e)) {
                throw new IOException("invalid migration entry");
            }
            entries.add(new Entry(String.valueOf(e.get("provider")), String.valueOf(e.get("configPath")),
                    String.valueOf(e.get("sourceHash")), Outcome.valueOf(String.valueOf(e.get("outcome"))),
                    e.get("launcher") == null ? null : String.valueOf(e.get("launcher"))));
        }
        return new Plan(planId, Instant.parse(String.valueOf(map.get("createdAt"))), entries);
    }

    /**
     * Executes a prepared plan with compare-and-set, backup, and journal evidence.
     *
     * @param plan prepared plan
     * @return execution result
     * @throws IOException if administrative evidence cannot be written
     */
    public synchronized Result execute(Plan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Path backupRoot = adminRoot.resolve("migration-backups")
                .resolve(plan.planId());
        Path journal = adminRoot.resolve("migration-executions")
                .resolve(plan.planId() + ".jsonl");
        Files.createDirectories(backupRoot);
        Files.createDirectories(journal.getParent());
        List<Entry> results = new ArrayList<>();
        int backups = 0;
        for (Entry planned : plan.entries()) {
            ProviderIntegration provider = provider(planned.provider());
            Entry current = inspect(provider);
            if ("codex".equals(provider.id())) {
                if (current.outcome() == Outcome.MALFORMED || current.outcome() == Outcome.DUPLICATE_SYNSESIS_ENTRY
                        || current.outcome() == Outcome.UNSUPPORTED_SCHEMA
                        || current.outcome() == Outcome.REQUIRES_HUMAN_REVIEW) {
                    results.add(current);
                    append(journal, "provider=codex outcome=" + current.outcome());
                    continue;
                }
                if (current.outcome() == Outcome.UP_TO_DATE) {
                    results.add(current);
                    append(journal, "provider=codex outcome=UP_TO_DATE");
                    continue;
                }
                if (sourceMismatches(current, planned)) {
                    Entry stale = new Entry("codex",
                            planned.configPath(),
                            current.sourceHash(),
                            Outcome.STALE,
                            launcher.toString());
                    results.add(stale);
                    append(journal, "provider=codex outcome=STALE reason=provider_config_stale");
                    continue;
                }
                Path config = configPath(planned);
                Path backup = backupRoot.resolve("codex.toml");
                boolean existed = Files.exists(config);
                try {
                    if (existed) {
                        Files.copy(config,
                                backup,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        if (!hash(config).equals(hash(backup))) {
                            throw new IOException("backup verification failed");
                        }
                        write(backupRoot.resolve("codex.manifest"), "provider=codex\nsourceHash=" + planned.sourceHash()
                                + "\nbackupHash=" + hash(backup) + "\nplan=" + plan.planId() + "\nformat=TOML\n");
                        backups++;
                    }
                    CodexTomlConfiguration.Inspection after = CodexTomlConfiguration.upsert(config, launcher);
                    if (after.outcome() != CodexTomlConfiguration.Outcome.UP_TO_DATE) {
                        throw new IOException("provider output invalid: " + after.outcome());
                    }
                    Entry migrated = new Entry("codex",
                            config.toString(),
                            after.sourceHash(),
                            Outcome.MIGRATED,
                            launcher.toString());
                    results.add(migrated);
                    append(journal, "provider=codex outcome=MIGRATED");
                } catch (Exception failure) {
                    if (existed && Files.exists(backup)) {
                        Files.copy(backup,
                                config,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    Entry restored = new Entry("codex",
                            config.toString(),
                            safeHash(config),
                            Outcome.FAILED_RESTORED,
                            launcher.toString());
                    results.add(restored);
                    append(journal, "provider=codex outcome=FAILED_RESTORED");
                }
                continue;
            }
            if (current.outcome() == Outcome.MISSING || current.outcome() == Outcome.UP_TO_DATE) {
                results.add(current);
                append(journal, "provider=" + planned.provider() + " outcome=" + current.outcome());
                continue;
            }
            if (current.outcome() != Outcome.MIGRATION_REQUIRED || sourceMismatches(current, planned)) {
                Entry stale = new Entry(planned.provider(),
                        planned.configPath(),
                        current.sourceHash(),
                        Outcome.STALE,
                        launcher.toString());
                results.add(stale);
                append(journal, "provider=" + planned.provider() + " outcome=STALE reason=provider_config_stale");
                continue;
            }
            Path config = configPath(planned);
            Path backup = backupRoot.resolve(planned.provider() + ".json");
            Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            write(backupRoot.resolve(planned.provider() + ".manifest"),
                    "provider=" + planned.provider() + "\nsourceHash=" + planned.sourceHash() + "\nbackupHash=" + hash(
                            backup) + "\nplan=" + plan.planId() + "\n");
            backups++;
            try {
                Map<String, Object> root = read(config);
                @SuppressWarnings("unchecked") Map<String, Object> servers = (Map<String, Object>) root.get("mcpServers");
                servers.put("synesis", provider.managedMcpServer(launcher, config.getParent()));
                root.put("mcpServers", servers);
                atomicReplace(config, ProviderJson.write(root) + System.lineSeparator());
                Entry after = inspect(provider);
                if (after.outcome() != Outcome.UP_TO_DATE) {
                    throw new IOException("provider output invalid: " + after.outcome() + " " + after.sourceHash());
                }
                results.add(new Entry(planned.provider(),
                        planned.configPath(),
                        after.sourceHash(),
                        Outcome.MIGRATED,
                        launcher.toString()));
                append(journal, "provider=" + planned.provider() + " outcome=MIGRATED backupHash=" + hash(backup));
            } catch (Exception failure) {
                Files.copy(backup, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                Entry restored = new Entry(planned.provider(),
                        planned.configPath(),
                        hash(config),
                        Outcome.FAILED_RESTORED,
                        launcher.toString());
                results.add(restored);
                append(journal, "provider=" + planned.provider() + " outcome=FAILED_RESTORED");
            }
        }
        Outcome aggregate = results.stream()
                .anyMatch(e -> e.outcome() == Outcome.STALE) ? Outcome.STALE
                : results.stream()
                        .anyMatch(e -> e.outcome() == Outcome.FAILED_RESTORED) ? Outcome.FAILED_RESTORED
                        : results.stream()
                                .anyMatch(e -> e.outcome() == Outcome.MIGRATED) ? Outcome.MIGRATED : Outcome.UP_TO_DATE;
        return new Result(aggregate, results, backups);
    }

    private Entry inspect(ProviderIntegration provider) {
        Path config = provider.mcpConfigurationPath(Path.of("."));
        if (config == null) {
            return new Entry(provider.id(), "", "", Outcome.UNSUPPORTED_SCHEMA, launcher.toString());
        }
        config = config.toAbsolutePath()
                .normalize();
        if ("codex".equals(provider.id())) {
            try {
                CodexTomlConfiguration.Inspection inspected = CodexTomlConfiguration.inspect(config, launcher);
                Outcome outcome = switch (inspected.outcome()) {
                    case MISSING -> Outcome.MISSING;
                    case UP_TO_DATE -> Outcome.UP_TO_DATE;
                    case MIGRATION_REQUIRED -> Outcome.MIGRATION_REQUIRED;
                    case MALFORMED -> Outcome.MALFORMED;
                    case DUPLICATE_SYNSESIS_ENTRY -> Outcome.DUPLICATE_SYNSESIS_ENTRY;
                    case UNSUPPORTED_SCHEMA -> Outcome.UNSUPPORTED_SCHEMA;
                };
                return new Entry("codex", config.toString(), inspected.sourceHash(), outcome, launcher.toString());
            } catch (Exception failure) {
                return new Entry("codex", config.toString(), safeHash(config), Outcome.MALFORMED, launcher.toString());
            }
        }
        throw new IllegalStateException("unsupported provider configuration: " + provider.id());
    }

    private Path planPath(Path dir, String id) {
        return dir.resolve(id + ".json");
    }

    /**
     * Stable migration outcomes.
     */
    public enum Outcome {
        /**
         * Configuration already uses the stable entry.
         */
        UP_TO_DATE,
        /**
         * Configuration differs from the stable entry.
         */
        MIGRATION_REQUIRED,
        /**
         * Configuration was migrated.
         */
        MIGRATED,
        /**
         * Configuration is absent.
         */
        MISSING,
        /**
         * Configuration is malformed.
         */
        MALFORMED,
        /**
         * Ambiguous duplicate entries exist.
         */
        DUPLICATE_SYNSESIS_ENTRY,
        /**
         * Source changed after planning.
         */
        STALE,
        /**
         * Configuration schema is unsupported.
         */
        UNSUPPORTED_SCHEMA,
        /**
         * Replacement failed and backup was restored.
         */
        FAILED_RESTORED,
        /**
         * Human review is required.
         */
        REQUIRES_HUMAN_REVIEW
    }

    /**
     * Immutable provider migration entry.
     *
     * @param provider   provider identifier
     * @param configPath configuration path
     * @param sourceHash source fingerprint
     * @param outcome    observed outcome
     * @param launcher   stable launcher
     */
    public record Entry(String provider, String configPath, String sourceHash, Outcome outcome, String launcher) {

        /**
         * Validates and freezes an entry.
         */
        public Entry {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(configPath, "configPath");
            Objects.requireNonNull(sourceHash, "sourceHash");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * Immutable prepared migration plan.
     *
     * @param planId    plan identifier
     * @param createdAt creation time
     * @param entries   planned entries
     */
    public record Plan(String planId, Instant createdAt, List<Entry> entries) {

        /**
         * Validates and freezes a plan.
         */
        public Plan {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(createdAt, "createdAt");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * Aggregate execution result.
     *
     * @param outcome        aggregate outcome
     * @param entries        result entries
     * @param backupsCreated number of verified backups
     */
    public record Result(Outcome outcome, List<Entry> entries, int backupsCreated) {

        /**
         * Validates and freezes a result.
         */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }
}
