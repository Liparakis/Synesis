package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.workspace.infrastructure.filesystem.AtomicFiles;
import org.synesis.workspace.infrastructure.json.JsonFields;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;

/**
 * Reads, validates, and writes the shareable {@code .synesis/project.json} document.
 *
 * <p>Schema 1 documents are still readable; every write emits the current schema.</p>
 */
public final class ProjectMetadataCodec {

    /**
     * Current project metadata schema.
     */
    public static final int SCHEMA_VERSION = 2;

    private static final Set<String> DOCUMENT_FIELDS = Set.of("schemaVersion", "projectId", "createdAt", "validation", "review");
    private static final Set<String> REVIEW_FIELDS = Set.of("mandatory");
    private static final Set<String> VALIDATION_FIELDS = Set.of("argv", "workingDirectory", "timeoutSeconds", "alsoInLane");

    private ProjectMetadataCodec() {
    }

    /**
     * Reads and validates the project metadata file.
     *
     * @param root     project root
     * @param synesis  private Synesis directory of the project
     * @param metadata metadata file
     * @return discovered project location
     * @throws IOException if the file cannot be read or its content is unsupported or malformed
     */
    public static ProjectLocation read(Path root, Path synesis, Path metadata) throws IOException {
        return decode(root, synesis, metadata, Files.readString(metadata, StandardCharsets.UTF_8));
    }

    private static ProjectLocation decode(Path root, Path synesis, Path metadata, String document) throws IOException {
        Object parsed = ProviderJson.parse(document);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IOException("unsupported or unsafe project metadata");
        }
        Map<String, Object> value = ProviderJson.stringKeyedObject(raw);
        int schema = integerNumber(value.get("schemaVersion"), "schemaVersion");
        if (schema != 1 && schema != SCHEMA_VERSION) {
            throw new IOException("unsupported project metadata schema");
        }
        if (!DOCUMENT_FIELDS.containsAll(value.keySet())) {
            throw new IOException("unsupported project metadata field");
        }
        UUID projectId = UUID.fromString(JsonFields.requiredText(value, "projectId", IOException::new));
        Instant createdAt = Instant.parse(JsonFields.requiredText(value, "createdAt", IOException::new));
        if (value.containsKey("validation") && value.get("validation") == null) {
            throw new IOException("validation must be an object when present");
        }
        ProjectCommandSpec validation = parseValidation(value.get("validation"));
        return new ProjectLocation(root, synesis, metadata, synesis.resolve("local/profile"), projectId, createdAt, validation, parseReview(value), alsoInLane(value.get("validation")));
    }

    /**
     * Returns the document text written for a project identity.
     *
     * @param identity portable project identity
     * @return metadata document text
     */
    public static String json(ProjectIdentity identity) {
        return json(identity, null, false, ReviewPolicy.none());
    }

    /**
     * Returns the document text for a project identity and the policy the project declares. Every
     * write of the document goes through here, so no writer can leave out what another one put in.
     *
     * @param identity             portable project identity
     * @param validation           project-owned validation command, or {@code null} when the project declares none
     * @param validationAlsoInLane whether the command also runs in a lane alone; written only when true
     * @param review               review policy; written only when mandatory
     * @return metadata document text
     */
    static String json(ProjectIdentity identity, ProjectCommandSpec validation, boolean validationAlsoInLane, ReviewPolicy review) {
        StringBuilder document = new StringBuilder("{\n" + "  \"schemaVersion\": " + SCHEMA_VERSION + ",\n" + "  \"projectId\": \"" + identity.projectId() + "\",\n" + "  \"createdAt\": \"" + identity.createdAt() + "\"");
        if (validation != null) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("argv", validation.argv());
            command.put("workingDirectory", validation.workingDirectory());
            command.put("timeoutSeconds", validation.timeoutSeconds());
            if (validationAlsoInLane) {
                command.put("alsoInLane", true);
            }
            document.append(",\n  \"validation\": ").append(ProviderJson.write(command));
        }
        if (review.mandatory()) {
            document.append(",\n  \"review\": {\"mandatory\": true}");
        }
        return document.append("\n}\n").toString();
    }

    /**
     * Replaces the metadata file of an initialized project, keeping its review policy.
     *
     * @param current              the project as it reads now
     * @param identity             identity to write
     * @param validation           validation command to write, or {@code null} to declare none
     * @param validationAlsoInLane whether the command also runs in a lane alone
     * @return the project as the replaced file reads
     * @throws IOException if the document would not read back as given, or the file cannot be replaced
     */
    public static ProjectLocation rewrite(ProjectLocation current, ProjectIdentity identity, ProjectCommandSpec validation, boolean validationAlsoInLane) throws IOException {
        write(current.metadataFile(), document(current, identity, validation, validationAlsoInLane), true);
        return read(current.root(), current.synesisDirectory(), current.metadataFile());
    }

    /**
     * Returns the document of an initialized project with another validation command, keeping its
     * review policy, after reading it back: a document this codec would refuse must never reach
     * the file, because the project could not be opened again to correct it.
     *
     * @param current              the project as it reads now
     * @param identity             identity to write
     * @param validation           validation command to write, or {@code null} to declare none
     * @param validationAlsoInLane whether the command also runs in a lane alone
     * @return the document text
     * @throws IOException if the document would not read back as given
     */
    private static String document(ProjectLocation current, ProjectIdentity identity, ProjectCommandSpec validation, boolean validationAlsoInLane) throws IOException {
        String document = json(identity, validation, validationAlsoInLane, current.review());
        ProjectLocation written = decode(current.root(), current.synesisDirectory(), current.metadataFile(), document);
        if (!written.identity().matches(identity) || !Objects.equals(written.validation(), validation) || written.validationAlsoInLane() != validationAlsoInLane || !written.review().equals(current.review())) {
            throw new IOException("project metadata would not read back as written");
        }
        return document;
    }

    /**
     * Writes the metadata file through a sibling temporary file.
     *
     * @param metadata        metadata file
     * @param identity        portable project identity
     * @param replaceExisting whether an existing file may be replaced; when {@code false}, an existing
     *                        file makes the move fail
     * @throws IOException if the file cannot be written
     */
    public static void write(Path metadata, ProjectIdentity identity, boolean replaceExisting) throws IOException {
        write(metadata, json(identity), replaceExisting);
    }

    private static void write(Path metadata, String document, boolean replaceExisting) throws IOException {
        Path temporary = metadata.resolveSibling("project.json.tmp-" + UUID.randomUUID());
        Files.writeString(temporary, document, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (replaceExisting) {
            AtomicFiles.replaceFromTemporary(temporary, metadata);
            return;
        }
        try {
            try {
                Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, metadata);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads the project's review policy: {@code "review": {"mandatory": true}} makes every work
     * group announced in the project subject to mandatory independent review. Absent, or with
     * {@code mandatory} false, work groups are created without a review policy, as before.
     */
    private static ReviewPolicy parseReview(Map<String, Object> document) throws IOException {
        if (!document.containsKey("review")) {
            return ReviewPolicy.none();
        }
        if (!(document.get("review") instanceof Map<?, ?> review) || !REVIEW_FIELDS.containsAll(review.keySet()) || !(review.get("mandatory") instanceof Boolean mandatory)) {
            throw new IOException("review must be an object with a boolean mandatory");
        }
        return mandatory ? ReviewPolicy.mandatoryIndependent() : ReviewPolicy.none();
    }

    /**
     * Reads {@code validation.alsoInLane}: whether the command also gates each lane alone. Absent
     * means it gates only the combined tree at integration.
     */
    private static boolean alsoInLane(Object validation) throws IOException {
        if (!(validation instanceof Map<?, ?> map) || !map.containsKey("alsoInLane")) {
            return false;
        }
        if (!(map.get("alsoInLane") instanceof Boolean also)) {
            throw new IOException("validation.alsoInLane must be a boolean");
        }
        return also;
    }

    private static ProjectCommandSpec parseValidation(Object raw) throws IOException {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("validation must be an object");
        }
        if (map.keySet().stream().anyMatch(key -> !(key instanceof String text) || !VALIDATION_FIELDS.contains(text))) {
            throw new IOException("validation contains unsupported fields");
        }
        Object argvRaw = map.get("argv");
        if (!(argvRaw instanceof List<?> list)) {
            throw new IOException("validation.argv must be an array");
        }
        List<String> argv = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String argument)) {
                throw new IOException("validation.argv entries must be strings");
            }
            argv.add(argument);
        }
        Object workingDirectoryRaw = map.get("workingDirectory");
        if (map.containsKey("workingDirectory") && !(workingDirectoryRaw instanceof String)) {
            throw new IOException("validation.workingDirectory must be a string");
        }
        String workingDirectory = workingDirectoryRaw instanceof String value ? value : ".";
        Object timeoutRaw = map.get("timeoutSeconds");
        if (map.containsKey("timeoutSeconds") && timeoutRaw == null) {
            throw new IOException("validation.timeoutSeconds must be an integer");
        }
        int timeoutSeconds = timeoutRaw == null ? ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS : integerNumber(timeoutRaw, "validation.timeoutSeconds");
        try {
            if (ProjectCommandSpec.escapesRoot(workingDirectory)) {
                throw new IOException("validation workingDirectory must remain relative");
            }
            return new ProjectCommandSpec(argv, workingDirectory, timeoutSeconds);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid project validation command", failure);
        }
    }

    private static int integerNumber(Object value, String key) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("missing or non-integer " + key);
        }
        if (number instanceof Double || number instanceof Float) {
            throw new IOException("non-integer " + key);
        }
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble) || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw new IOException("non-integer " + key);
        }
        int result = number.intValue();
        if (result != asDouble) {
            throw new IOException("non-integer " + key);
        }
        return result;
    }
}
