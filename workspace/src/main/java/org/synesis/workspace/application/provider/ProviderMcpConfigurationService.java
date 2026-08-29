package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.codex.CodexTomlConfiguration;

/**
 * Owns provider MCP configuration installation and removal.
 */
@SuppressWarnings("DuplicatedCode")
final class ProviderMcpConfigurationService {

    /**
     * Ensures the Synesis MCP entry exists in the provider configuration.
     */
    String ensure(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider, Path launcher) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath == null) {
            return "UNSUPPORTED";
        }
        try {
            if ("codex".equals(provider.id())) {
                CodexTomlConfiguration.Inspection before = CodexTomlConfiguration.inspect(configPath, launcher,
                        location.root());
                if (before.outcome() == CodexTomlConfiguration.Outcome.MALFORMED
                        || before.outcome() == CodexTomlConfiguration.Outcome.DUPLICATE_SYNSESIS_ENTRY) {
                    return "MALFORMED_CONFIG";
                }
                CodexTomlConfiguration.Inspection after = CodexTomlConfiguration.upsert(configPath, launcher,
                        location.root());
                cleanObsoleteProjectFile(location.root()
                        .resolve(".codex/mcp.json"));
                return before.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE
                        && after.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE ? "UNCHANGED" : "INSTALLED";
            }

            Map<String, Object> managedEntry = provider.managedMcpServer(launcher, location.root());
            Map<String, Object> root = Files.exists(configPath) ? readObject(configPath) : new LinkedHashMap<>();
            Map<String, Object> servers = mapValue(root.get("mcpServers"));
            Object existing = servers.get("synesis");
            boolean unchanged = managedEntry.equals(existing);
            if (!unchanged) {
                servers.put("synesis", managedEntry);
                root.put("mcpServers", servers);
                atomicWrite(configPath, ProviderJson.write(root) + System.lineSeparator());
            }

            if ("antigravity".equals(provider.id())) {
                cleanObsoleteAntigravityProviderConfig();
            }
            cleanObsoleteProjectFile(location.root()
                    .resolve(".agents/mcp.json"));
            cleanObsoleteProjectFile(location.root()
                    .resolve(".gemini/mcp.json"));
            return unchanged ? "UNCHANGED" : "INSTALLED";
        } catch (Exception failure) {
            return "MALFORMED_CONFIG";
        }
    }

    /**
     * Removes the Synesis MCP entry from the provider configuration.
     */
    void remove(ProjectApplicationService.ProjectLocation location, ProviderIntegration provider) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath != null && Files.exists(configPath)) {
            try {
                if ("codex".equals(provider.id())) {
                    CodexTomlConfiguration.remove(configPath);
                    cleanObsoleteProjectFile(location.root()
                            .resolve(".codex/mcp.json"));
                } else {
                    removeEntry(configPath);
                }
            } catch (Exception ignored) {
            }
        }
        if ("antigravity".equals(provider.id())) {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank()) {
                try {
                    cleanObsoleteAntigravityProviderConfig();
                } catch (IOException ignored) {
                }
            }
            cleanObsoleteProjectFile(location.root()
                    .resolve(".agents/mcp.json"));
            cleanObsoleteProjectFile(location.root()
                    .resolve(".gemini/mcp.json"));
        }
    }

    private void cleanObsoleteAntigravityProviderConfig() throws IOException {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return;
        }
        Path mirror = Path.of(userHome, ".gemini", "antigravity", "mcp_config.json");
        if (Files.exists(mirror)) {
            removeEntry(mirror);
        }
    }

    private void removeEntry(Path path) throws IOException {
        Map<String, Object> root = readObject(path);
        Object value = root.get("mcpServers");
        if (value instanceof Map<?, ?>) {
            Map<String, Object> servers = mapValue(value);
            servers.remove("synesis");
            if (servers.isEmpty()) {
                root.remove("mcpServers");
            } else {
                root.put("mcpServers", servers);
            }
            atomicWrite(path, ProviderJson.write(root) + System.lineSeparator());
        }
    }

    private void cleanObsoleteProjectFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Map<String, Object> root = readObject(path);
            Object value = root.get("mcpServers");
            if (!(value instanceof Map<?, ?>)) {
                return;
            }
            Map<String, Object> servers = mapValue(value);
            servers.remove("synesis");
            if (servers.isEmpty()) {
                root.remove("mcpServers");
            } else {
                root.put("mcpServers", servers);
            }
            if (root.isEmpty()) {
                Files.deleteIfExists(path);
            } else {
                atomicWrite(path, ProviderJson.write(root) + System.lineSeparator());
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> readObject(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        Object value = ProviderJson.parse(Files.readString(path));
        if (value instanceof Map<?, ?>) {
            return mapValue(value);
        }
        throw new IllegalArgumentException("JSON object expected");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
