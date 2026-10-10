package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Defines, reads, writes, and verifies compatibility metadata for durable command objects.
 */
final class CommandDurableFormat {

    /**
     * Current durable object schema.
     */
    static final long SCHEMA_VERSION = 2L;
    /**
     * Lowest schema this implementation can read.
     */
    static final long MINIMUM_READER_SCHEMA_VERSION = 1L;
    /**
     * Version of the canonical durable-object encoding.
     */
    static final long CANONICALIZATION_VERSION = 1L;
    /**
     * Integrity algorithm used for durable objects.
     */
    static final String INTEGRITY_ALGORITHM = "SHA-256";
    /**
     * Writer version recorded in durable objects.
     */
    static final String WRITER_VERSION = "0.1.0-SNAPSHOT";
    /**
     * Size bound for a reader that accepts any size.
     */
    static final int UNBOUNDED = Integer.MAX_VALUE;

    private CommandDurableFormat() {
    }

    /**
     * Adds current compatibility metadata and an integrity digest to a map.
     *
     * @param source durable object fields
     * @return new map with compatibility and integrity metadata
     */
    static Map<String, Object> withIntegrity(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        Map<String, Object> value = new TreeMap<>(source);
        value.putIfAbsent("schemaVersion", SCHEMA_VERSION);
        value.putIfAbsent("minimumReaderSchemaVersion", MINIMUM_READER_SCHEMA_VERSION);
        value.putIfAbsent("writerVersion", WRITER_VERSION);
        value.putIfAbsent("canonicalizationVersion", CANONICALIZATION_VERSION);
        value.putIfAbsent("integrityAlgorithm", INTEGRITY_ALGORITHM);
        value.putIfAbsent("objectRevision", 1L);
        value.remove("integrityDigest");
        value.put("integrityDigest", canonicalDigest(value));
        return value;
    }

    /**
     * Verifies compatibility metadata and the exact stored integrity digest.
     *
     * @param value parsed durable object
     * @throws CommandFormatException if compatibility or integrity fails
     */
    static void verify(Map<String, Object> value) throws CommandFormatException {
        Objects.requireNonNull(value, "value");
        long schema = number(value, "schemaVersion");
        long minimumReader = number(value, "minimumReaderSchemaVersion");
        if (schema > SCHEMA_VERSION || minimumReader > SCHEMA_VERSION) {
            throw new CommandFormatException("COMMAND_FORMAT_NEWER_THAN_READER");
        }
        if (schema < MINIMUM_READER_SCHEMA_VERSION || minimumReader < MINIMUM_READER_SCHEMA_VERSION || string(value, "writerVersion").isBlank()) {
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
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), canonicalDigest(withoutDigest).getBytes(StandardCharsets.UTF_8))) {
            throw new CommandFormatException("COMMAND_INTEGRITY_MISMATCH");
        }
    }

    /**
     * Hashes the canonical encoding of a JSON value, in which every object's keys are sorted.
     *
     * <p>{@link ProviderJson#write} follows map iteration order, which {@code Map.of} randomizes
     * per JVM run. Every digest in this package goes through here so the same content hashes to
     * the same value in every process.</p>
     *
     * @param value JSON value
     * @return SHA-256 hex digest
     */
    static String canonicalDigest(Object value) {
        try {
            return Sha256.hex(ProviderJson.write(sortKeys(value)));
        } catch (Exception failure) {
            throw new IllegalStateException("command integrity unavailable", failure);
        }
    }

    /**
     * Reads one durable object and verifies its compatibility metadata and integrity digest.
     *
     * @param path     durable object file
     * @param maxBytes largest accepted file size, or {@link #UNBOUNDED}
     * @param codes    failure codes of the calling site
     * @return verified object fields
     * @throws IOException if the file is unreadable, malformed, or fails verification
     */
    static Map<String, Object> read(Path path, int maxBytes, ReadCodes codes) throws IOException {
        return load(path, maxBytes, codes, true);
    }

    /**
     * Reads and parses one durable object without verifying its metadata or digest.
     *
     * @param path     durable object file
     * @param maxBytes largest accepted file size, or {@link #UNBOUNDED}
     * @param codes    failure codes of the calling site
     * @return parsed object fields
     * @throws IOException if the file is unreadable or is not a JSON object
     */
    static Map<String, Object> readUnverified(Path path, int maxBytes, ReadCodes codes) throws IOException {
        return load(path, maxBytes, codes, false);
    }

    /**
     * Copies a parsed JSON object into its string-keyed representation.
     *
     * @param source         parsed JSON object
     * @param keyInvalidCode failure code for a key that is not text
     * @return insertion-ordered object fields
     * @throws CommandFormatException if a key is not text
     */
    static Map<String, Object> stringKeyed(Map<?, ?> source, String keyInvalidCode) throws CommandFormatException {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CommandFormatException(keyInvalidCode);
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    /**
     * Serializes an object that already carries integrity metadata and replaces the target file with it.
     *
     * @param target        destination file; its directory is created if missing
     * @param durable       object from {@link #withIntegrity(Map)}
     * @param maxBytes      largest accepted serialized size
     * @param sizeLimitCode failure code when the object exceeds {@code maxBytes}
     * @throws IOException if the object is too large or cannot be persisted
     */
    static void write(Path target, Map<String, Object> durable, int maxBytes, String sizeLimitCode) throws IOException {
        String json = ProviderJson.write(durable);
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IOException(sizeLimitCode);
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> load(Path path, int maxBytes, ReadCodes codes, boolean verify) throws IOException {
        if (codes.invalid() != null && (!Files.isRegularFile(path) || Files.isSymbolicLink(path))) {
            throw new CommandFormatException(codes.invalid());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) {
            throw new CommandFormatException(codes.sizeLimit());
        }
        try {
            Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> parsedMap)) {
                throw new CommandFormatException(codes.notObject());
            }
            Map<String, Object> value = stringKeyed(parsedMap, codes.keyInvalid());
            if (verify) {
                verify(value);
            }
            return value;
        } catch (RuntimeException failure) {
            if (codes.corrupt() == null) {
                throw failure;
            }
            throw new CommandFormatException(codes.corrupt(), failure);
        }
    }

    private static Object sortKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), sortKeys(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(child -> copy.add(sortKeys(child)));
            return copy;
        }
        return value;
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

    /**
     * Failure codes one reading site raises. A site that never checked a condition passes
     * {@code null} for its code and keeps accepting that input.
     *
     * @param invalid    not a regular file or a symbolic link; {@code null} skips that check
     * @param sizeLimit  larger than the size bound; unused when the bound is {@link #UNBOUNDED}
     * @param notObject  JSON value is not an object
     * @param keyInvalid object key is not text
     * @param corrupt    wraps unchecked parse and verification failures; {@code null} lets them propagate
     */
    record ReadCodes(String invalid, String sizeLimit, String notObject, String keyInvalid, String corrupt) {

    }
}
