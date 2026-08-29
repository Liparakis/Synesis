package org.synesis.workspace.lifecycle.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Defines and verifies compatibility metadata for durable command objects.
 */
public final class CommandDurableFormat {

    /**
     * Current durable object schema.
     */
    public static final long SCHEMA_VERSION = 2L;
    /**
     * Lowest schema this implementation can read.
     */
    public static final long MINIMUM_READER_SCHEMA_VERSION = 1L;
    /**
     * Version of the canonical durable-object encoding.
     */
    public static final long CANONICALIZATION_VERSION = 1L;
    /**
     * Integrity algorithm used for durable objects.
     */
    public static final String INTEGRITY_ALGORITHM = "SHA-256";
    /**
     * Writer version recorded in durable objects.
     */
    public static final String WRITER_VERSION = "0.1.0-SNAPSHOT";

    private CommandDurableFormat() {
    }

    /**
     * Adds current compatibility metadata and an integrity digest to a map.
     *
     * @param source durable object fields
     * @return new map with compatibility and integrity metadata
     */
    public static Map<String, Object> withIntegrity(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        Map<String, Object> value = new TreeMap<>(source);
        value.putIfAbsent("schemaVersion", SCHEMA_VERSION);
        value.putIfAbsent("minimumReaderSchemaVersion", MINIMUM_READER_SCHEMA_VERSION);
        value.putIfAbsent("writerVersion", WRITER_VERSION);
        value.putIfAbsent("canonicalizationVersion", CANONICALIZATION_VERSION);
        value.putIfAbsent("integrityAlgorithm", INTEGRITY_ALGORITHM);
        value.putIfAbsent("objectRevision", 1L);
        value.remove("integrityDigest");
        value.put("integrityDigest", digest(value));
        return value;
    }

    /**
     * Verifies compatibility metadata and the exact stored integrity digest.
     *
     * @param value parsed durable object
     * @throws CommandFormatException if compatibility or integrity fails
     */
    public static void verify(Map<String, Object> value) throws CommandFormatException {
        Objects.requireNonNull(value, "value");
        long schema = number(value, "schemaVersion");
        long minimumReader = number(value, "minimumReaderSchemaVersion");
        if (schema > SCHEMA_VERSION || minimumReader > SCHEMA_VERSION) {
            throw new CommandFormatException("COMMAND_FORMAT_NEWER_THAN_READER");
        }
        if (schema < MINIMUM_READER_SCHEMA_VERSION || minimumReader < MINIMUM_READER_SCHEMA_VERSION
                || string(value, "writerVersion").isBlank()) {
            throw new CommandFormatException("COMMAND_FORMAT_METADATA_INVALID");
        }
        if (number(value, "canonicalizationVersion") != CANONICALIZATION_VERSION) {
            throw new CommandFormatException("COMMAND_CANONICALIZATION_UNSUPPORTED");
        }
        if (!INTEGRITY_ALGORITHM.equals(string(value, "integrityAlgorithm"))) {
            throw new CommandFormatException("COMMAND_INTEGRITY_ALGORITHM_UNSUPPORTED");
        }
        String expected = string(value, "integrityDigest");
        Map<String, Object> withoutDigest = new TreeMap<>(value);
        withoutDigest.remove("integrityDigest");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                digest(withoutDigest).getBytes(StandardCharsets.UTF_8))) {
            throw new CommandFormatException("COMMAND_INTEGRITY_MISMATCH");
        }
    }

    private static String digest(Map<String, Object> value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance(INTEGRITY_ALGORITHM)
                            .digest(ProviderJson.write(new TreeMap<>(value))
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("command integrity unavailable", failure);
        }
    }

    private static long number(Map<String, Object> value, String key) throws CommandFormatException {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw new CommandFormatException("COMMAND_FORMAT_FIELD_MISSING:" + key);
        }
        return number.longValue();
    }

    private static String string(Map<String, Object> value, String key) throws CommandFormatException {
        Object raw = value.get(key);
        if (!(raw instanceof String text)) {
            throw new CommandFormatException("COMMAND_FORMAT_FIELD_MISSING:" + key);
        }
        return text;
    }
}
