package org.synesis.projectrecord;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain representation of an explicit typed project constraint.
 *
 * @since 1.0
 */
public final class ProjectConstraint {

    /** Constraint enforcement effect. */
    public enum Effect {
        /** Hard block: prevents protected operation and forces re-planning. */
        BLOCK,
        /** Soft warning: allows operation but emits a diagnostic warning. */
        WARN
    }

    /** Constraint lifecycle status. */
    public enum ConstraintStatus {
        /** Active constraint enforced against actions. */
        ACTIVE,
        /** Inactive constraint ignored during action checks. */
        INACTIVE,
        /** Superseded constraint replaced by a newer record. */
        SUPERSEDED
    }

    private final UUID recordId;
    private final String title;
    private final String rationale;
    private final ConstraintStatus status;
    private final Effect effect;
    private final List<String> scopes;
    private final List<UUID> supersedes;

    /**
     * Constructs a typed project constraint domain model.
     *
     * @param recordId   record ID
     * @param title      constraint title
     * @param rationale  constraint rationale
     * @param status     lifecycle status
     * @param effect     enforcement effect
     * @param scopes     target scopes
     * @param supersedes superseded constraint IDs
     */
    public ProjectConstraint(UUID recordId, String title, String rationale, ConstraintStatus status,
                             Effect effect, List<String> scopes, List<UUID> supersedes) {
        this.recordId = Objects.requireNonNull(recordId, "record ID");
        this.title = Objects.requireNonNull(title, "title");
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        this.status = Objects.requireNonNull(status, "status");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        this.supersedes = List.copyOf(Objects.requireNonNull(supersedes, "supersedes"));
        if (supersedes.contains(recordId)) {
            throw new IllegalArgumentException("self-supersession is forbidden: " + recordId);
        }
    }

    /**
     * Extracts a {@link ProjectConstraint} from a verified {@link DecisionRecord}.
     *
     * @param record target decision record
     * @return extracted constraint, or null if the record is not a project constraint
     */
    public static ProjectConstraint fromRecord(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.recordType() != DecisionRecord.RecordType.PROJECT_CONSTRAINT) {
            return null; // Ordinary decision records are ignored
        }
        DecisionRecord.ConstraintPayload payload = record.constraintPayload();
        if (payload == null) {
            return null;
        }
        return new ProjectConstraint(record.recordId(), record.title(), record.rationale(),
                payload.status(), payload.effect(), payload.scopes(), payload.supersedes());
    }

    /**
     * Creates a new signed {@link DecisionRecord} representing a typed project constraint.
     *
     * @param projectId  namespace project ID
     * @param recordId   record identity
     * @param ownerNode  owner node identity
     * @param effect     constraint effect (BLOCK vs WARN)
     * @param scope      primary scope pattern
     * @param title      constraint title
     * @param rationale  constraint rationale
     * @param signer     Ed25519 signer
     * @return signed DecisionRecord containing typed constraint payload
     * @throws GeneralSecurityException if signing fails
     */
    public static DecisionRecord createTypedRecord(UUID projectId, UUID recordId, String ownerNode,
                                                   Effect effect, String scope, String title,
                                                   String rationale, Ed25519Signer signer) throws GeneralSecurityException {
        return createTypedRecord(projectId, recordId, ownerNode, effect, scope, title, rationale, List.of(), signer);
    }

    /**
     * Creates a new signed {@link DecisionRecord} representing a typed project constraint with supersedes.
     *
     * @param projectId  namespace project ID
     * @param recordId   record identity
     * @param ownerNode  owner node identity
     * @param effect     constraint effect (BLOCK vs WARN)
     * @param scope      primary scope pattern
     * @param title      constraint title
     * @param rationale  constraint rationale
     * @param supersedes list of superseded constraint UUIDs
     * @param signer     Ed25519 signer
     * @return signed DecisionRecord containing typed constraint payload
     * @throws GeneralSecurityException if signing fails
     */
    public static DecisionRecord createTypedRecord(UUID projectId, UUID recordId, String ownerNode,
                                                   Effect effect, String scope, String title,
                                                   String rationale, List<UUID> supersedes,
                                                   Ed25519Signer signer) throws GeneralSecurityException {
        Objects.requireNonNull(scope, "scope");
        String normScope = ScopeMatcher.normalizePath(scope);
        DecisionRecord.ConstraintPayload payload = new DecisionRecord.ConstraintPayload(
                effect, ConstraintStatus.ACTIVE, List.of(normScope), supersedes);

        byte[] digest = sha256("scope=" + normScope);
        DecisionEvidence evidence = new DecisionEvidence("constraint", "scope=" + normScope, digest);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return DecisionRecord.createConstraint(projectId, recordId, 1, null, ownerNode, ownerNode,
                DecisionStatus.ACCEPTED, now, now, title, rationale, List.of(evidence), payload, signer);
    }

    private static byte[] sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    /**
     * Returns the constraint record ID.
     *
     * @return record ID
     */
    public UUID recordId() { return recordId; }

    /**
     * Returns the constraint title.
     *
     * @return title
     */
    public String title() { return title; }

    /**
     * Returns the constraint rationale.
     *
     * @return rationale
     */
    public String rationale() { return rationale; }

    /**
     * Returns the constraint lifecycle status.
     *
     * @return status
     */
    public ConstraintStatus status() { return status; }

    /**
     * Returns the constraint enforcement effect.
     *
     * @return effect
     */
    public Effect effect() { return effect; }

    /**
     * Returns the list of target scopes.
     *
     * @return scopes
     */
    public List<String> scopes() { return scopes; }

    /**
     * Returns the list of superseded constraint record IDs.
     *
     * @return supersedes
     */
    public List<UUID> supersedes() { return supersedes; }

    /**
     * Evaluates whether this active constraint applies to a normalized target path using {@link ScopeMatcher}.
     *
     * @param targetPath repository-relative file path
     * @return true if the path matches any configured scope in this constraint
     */
    public boolean appliesTo(String targetPath) {
        if (status != ConstraintStatus.ACTIVE) {
            return false;
        }
        for (String scopePattern : scopes) {
            if (ScopeMatcher.matches(scopePattern, targetPath)) {
                return true;
            }
        }
        return false;
    }
}
