package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.codex.CodexTomlConfiguration;
import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Owns provider MCP configuration installation and removal.
 */
@SuppressWarnings("DuplicatedCode")
final class ProviderMcpConfigurationService {

    /**
     * Ensures the Synesis MCP entry exists in the provider configuration.
     */
    String ensure(ProjectLocation location, ProviderIntegration provider, Path launcher) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath == null) {
            return "UNSUPPORTED";
        }
        try {
            if (provider.usesGlobalMcpConfiguration()) {
                CodexTomlConfiguration.Inspection before = CodexTomlConfiguration.inspect(configPath, launcher);
                if (before.outcome() == CodexTomlConfiguration.Outcome.MALFORMED || before.outcome() == CodexTomlConfiguration.Outcome.DUPLICATE_SYNSESIS_ENTRY
                        || before.outcome() == CodexTomlConfiguration.Outcome.UNSUPPORTED_SCHEMA) {
                    return "MALFORMED_CONFIG";
                }
                CodexTomlConfiguration.Inspection after = CodexTomlConfiguration.upsert(configPath, launcher);
                cleanObsoleteProjectFile(location.root().resolve(".codex/mcp.json"));
                return before.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE && after.outcome() == CodexTomlConfiguration.Outcome.UP_TO_DATE ? "UNCHANGED" : "INSTALLED";
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

            return unchanged ? "UNCHANGED" : "INSTALLED";
        } catch (Exception failure) {
            return "MALFORMED_CONFIG";
        }
    }

    /**
     * Removes the Synesis MCP entry from the provider configuration.
     */
    void remove(ProjectLocation location, ProviderIntegration provider) {
        Path configPath = provider.mcpConfigurationPath(location.root());
        if (configPath != null && Files.exists(configPath)) {
            try {
                if (provider.usesGlobalMcpConfiguration()) {
                    CodexTomlConfiguration.remove(configPath);
                    cleanObsoleteProjectFile(location.root().resolve(".codex/mcp.json"));
                } else {
                    removeEntry(configPath);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Removes only Codex's user-wide Synesis MCP entry.
     */
    String removeGlobal(ProviderIntegration provider) throws IOException {
        Path configPath = provider.mcpConfigurationPath(Path.of("."));
        if (configPath == null || !Files.exists(configPath)) {
            return "NOT_PRESENT";
        }
        if (!provider.usesGlobalMcpConfiguration()) {
            throw new IllegalArgumentException("global MCP removal is only supported for Codex");
        }
        CodexTomlConfiguration.Inspection inspection = CodexTomlConfiguration.inspect(configPath, null);
        if (inspection.outcome() == CodexTomlConfiguration.Outcome.MALFORMED || inspection.outcome() == CodexTomlConfiguration.Outcome.DUPLICATE_SYNSESIS_ENTRY
                || inspection.outcome() == CodexTomlConfiguration.Outcome.UNSUPPORTED_SCHEMA) {
            throw new IOException("Codex MCP configuration is not safe to edit");
        }
        return CodexTomlConfiguration.remove(configPath) ? "REMOVED" : "NOT_PRESENT";
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
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
