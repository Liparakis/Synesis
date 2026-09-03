package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns one isolated stock-Codex runtime home and its non-secret MCP
 * configuration.
 *
 * <p>The home is outside the customer repository. The attachment proof is
 * supplied only to the returned process environment; it is never written to
 * the Codex configuration.</p>
 */
public final class CodexManagedRuntimeHome {

    /** Production attachment proof environment variable. */
    public static final String ATTACHMENT_PROOF_ENV = "SYNESIS_ATTACH_PROOF";
    private final String homeId;
    private final Path home;

    private CodexManagedRuntimeHome(String homeId, Path home) {
        this.homeId = homeId;
        this.home = home;
    }

    /**
     * Creates a fresh opaque home for one logical project worker.
     *
     * @param projectId durable project identity used as a non-secret namespace
     * @return newly created managed home
     * @throws IOException when the home cannot be created
     */
    public static CodexManagedRuntimeHome create(String projectId) throws IOException {
        requireText(projectId, "projectId");
        String base = System.getenv("LOCALAPPDATA");
        Path root = base == null || base.isBlank()
                ? Path.of(System.getProperty("user.home"), ".synesis", "managed-runtime")
                : Path.of(base, "Synesis", "managed-runtime");
        String homeId = UUID.randomUUID().toString();
        Path home = root.resolve(projectId).resolve(homeId).toAbsolutePath().normalize();
        Files.createDirectories(home);
        return new CodexManagedRuntimeHome(homeId, home);
    }

    /**
     * Selects the provider-owned normal Codex home without creating or copying
     * any credential state.
     *
     * @return normal provider home handle
     */
    public static CodexManagedRuntimeHome normalProviderHome() {
        return new CodexManagedRuntimeHome("normal-provider-home", CodexManagedAuthentication.userHome());
    }

    /**
     * Returns the opaque managed home identity.
     *
     * @return opaque managed home identity
     */
    public String homeId() {
        return homeId;
    }

    /**
     * Returns the isolated CODEX_HOME directory.
     *
     * @return isolated CODEX_HOME directory
     */
    public Path path() {
        return home;
    }

    /**
     * Returns the managed Codex configuration path.
     *
     * @return managed Codex configuration path
     */
    public Path configPath() {
        return home.resolve("config.toml");
    }

    /**
     * Writes the managed Codex configuration with an allow-listed proof name.
     *
     * @param launcher Synesis MCP launcher
     * @param projectRoot control project root
     * @param authentication safe authentication strategy
     * @throws IOException when configuration cannot be written
     */
    public void writeConfiguration(Path launcher, Path projectRoot,
            CodexManagedAuthentication.Strategy authentication) throws IOException {
        Objects.requireNonNull(launcher, "launcher");
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (authentication != CodexManagedAuthentication.Strategy.SHARED_KEYRING) {
            throw new IOException("MANAGED_CODEX_AUTH_UNAVAILABLE");
        }
        String command = quote(launcher);
        String project = quote(projectRoot.toAbsolutePath().normalize());
        String text = "cli_auth_credentials_store = \"keyring\"\n\n"
                + "[mcp_servers.synesis]\n"
                + "command = '" + launcher.toAbsolutePath().normalize().toString().replace("'", "''") + "'\n"
                + "args = [\"mcp\", \"--provider\", \"codex\", \"--project\", " + project + "]\n"
                + "env_vars = [\"" + ATTACHMENT_PROOF_ENV + "\"]\n";
        atomicWrite(configPath(), text);
        if (Files.readString(configPath()).contains(ATTACHMENT_PROOF_ENV + "=\"")) {
            throw new IOException("managed configuration contains raw attachment assignment");
        }
        // Keep the quote helper exercised at the boundary where command values
        // are assembled, without placing a secret in the configuration.
        if (command.isBlank()) {
            throw new IOException("managed launcher is invalid");
        }
    }

    /**
     * Creates a process environment for the managed App Server.
     *
     * @param proof raw proof kept in the trusted launch handoff
     * @return launch environment; its string representation is deliberately redacted
     */
    public LaunchEnvironment environment(String proof) {
        requireText(proof, "proof");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CODEX_HOME", home.toString());
        values.put(ATTACHMENT_PROOF_ENV, proof);
        return new LaunchEnvironment(values);
    }

    /**
     * Removes this home only after the caller has independently established
     * terminal retention eligibility.
     *
     * @throws IOException when cleanup fails
     */
    public void deleteAfterTerminal() throws IOException {
        if ("normal-provider-home".equals(homeId)) {
            return;
        }
        if (!Files.exists(home)) {
            return;
        }
        try (var paths = Files.walk(home)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 8_192 || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void atomicWrite(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Trusted managed-launch environment that redacts its secret on logging.
     */
    public static final class LaunchEnvironment {
        private final Map<String, String> values;

        private LaunchEnvironment(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        /**
         * Returns a copy suitable for a trusted ProcessBuilder.
         *
         * @return copied launch environment values
         */
        public Map<String, String> values() {
            return new LinkedHashMap<>(values);
        }

        /** @return redacted diagnostic text */
        @Override
        public String toString() {
            return "LaunchEnvironment[CODEX_HOME=<managed>, SYNESIS_ATTACH_PROOF=<redacted>]";
        }
    }
}
