package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Reads, writes, and locates the JSON files a provider integration owns or shares with the user:
 * the provider configuration, the Synesis metadata file, and the generic object/array views over
 * parsed JSON. Reads reject a document that is not a JSON object, and writes replace the file
 * atomically so a concurrent reader never sees a partial provider configuration. It is separate
 * from {@link ProviderApplicationService} so the hook, instruction, and install code all parse and
 * write provider files one way instead of each carrying its own copy.
 */
final class ProviderConfigurationFiles {

    static final int METADATA_SCHEMA = 1;

    private ProviderConfigurationFiles() {
    }

    static Map<String, Object> readObject(Path path) throws IOException {
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
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("JSON array expected");
    }

    static boolean schemaVersion(Object value) {
        return value instanceof Number number && number.doubleValue() == METADATA_SCHEMA;
    }

    static Path metadata(ProjectLocation location, ProviderIntegration provider) {
        return location.synesisDirectory().resolve("local/providers/" + provider.id() + ".json");
    }

    static void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
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
