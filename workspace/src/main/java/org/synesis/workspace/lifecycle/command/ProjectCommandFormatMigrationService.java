package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/** Migrates supported older command objects with exact backup and immutable evidence. */
public final class ProjectCommandFormatMigrationService {

    /** Creates a format migration service. */
    public ProjectCommandFormatMigrationService() {
    }

    /** Migrates one older object while the caller holds the required permanent locks.
     * @param target durable object to migrate
     * @param backup exact-byte backup destination, which must not already exist
     * @param journal immutable migration-evidence destination, which must not already exist
     * @param requiredLock held namespace or scoped command lock
     * @throws IOException if compatibility, backup, journal, replacement, or verification fails
     */
    public void migrate(Path target, Path backup, Path journal, CommandPermanentLock requiredLock)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(requiredLock, "requiredLock");
        if (!requiredLock.isHeld()) {
            throw new IOException("COMMAND_MIGRATION_LOCK_REQUIRED");
        }
        byte[] original = Files.readAllBytes(target);
        Map<String, Object> value = parse(original);
        CommandDurableFormat.verify(value);
        long schema = ((Number) value.get("schemaVersion")).longValue();
        if (schema >= CommandDurableFormat.SCHEMA_VERSION) {
            throw new IOException("COMMAND_FORMAT_MIGRATION_NOT_REQUIRED");
        }
        Files.createDirectories(Objects.requireNonNull(backup.toAbsolutePath().normalize().getParent(), "backup parent"));
        Files.write(backup, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("target", target.toAbsolutePath().normalize().toString());
        evidence.put("backup", backup.toAbsolutePath().normalize().toString());
        evidence.put("oldSchemaVersion", schema);
        evidence.put("newSchemaVersion", CommandDurableFormat.SCHEMA_VERSION);
        evidence.put("originalByteDigest", digest(target.toString() + "\u001f"
                + java.util.HexFormat.of().formatHex(digestBytes(original))));
        evidence.put("migrationId", UUID.randomUUID().toString());
        String journalJson = ProviderJson.write(CommandDurableFormat.withIntegrity(evidence));
        Files.createDirectories(Objects.requireNonNull(journal.toAbsolutePath().normalize().getParent(), "journal parent"));
        Files.writeString(journal, journalJson, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        value.put("schemaVersion", CommandDurableFormat.SCHEMA_VERSION);
        value.put("minimumReaderSchemaVersion", CommandDurableFormat.MINIMUM_READER_SCHEMA_VERSION);
        value.put("writerVersion", CommandDurableFormat.WRITER_VERSION);
        String replacement = ProviderJson.write(CommandDurableFormat.withIntegrity(value));
        Path temporary = target.resolveSibling(target.getFileName() + ".migration-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, replacement, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        CommandDurableFormat.verify(parse(Files.readAllBytes(target)));
    }

    private static Map<String, Object> parse(byte[] bytes) throws IOException {
        Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw)) throw new CommandFormatException("COMMAND_FORMAT_NOT_OBJECT");
        Map<String, Object> value = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new CommandFormatException("COMMAND_FORMAT_KEY_INVALID");
            value.put(key, entry.getValue());
        }
        return value;
    }

    private static byte[] digestBytes(byte[] value) throws IOException {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException("COMMAND_INTEGRITY_ALGORITHM_UNAVAILABLE", failure);
        }
    }

    private static String digest(String value) throws IOException {
        return java.util.HexFormat.of().formatHex(digestBytes(value.getBytes(StandardCharsets.UTF_8)));
    }
}
