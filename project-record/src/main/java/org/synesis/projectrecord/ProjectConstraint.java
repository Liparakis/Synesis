package org.synesis.projectrecord;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain representation of a project constraint extracted from a verified {@link DecisionRecord}.
 *
 * <p>Supports both typed semantics (preferred) and legacy inferred constraints for backwards compatibility.
 *
 * @since 1.0
 */
public final class ProjectConstraint {

    /** Constraint origin source. */
    public enum Source {
        /** Typed constraint record using explicit constraint evidence schema. */
        TYPED,
        /** Legacy constraint inferred from title prefix 'CONSTRAINT:'. */
        LEGACY_INFERRED
    }

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
        /** Inactive or rejected constraint. */
        INACTIVE,
        /** Superseded constraint replaced by a newer revision. */
        SUPERSEDED
    }

    private final UUID recordId;
    private final String title;
    private final String rationale;
    private final ConstraintStatus status;
    private final Effect effect;
    private final List<String> scopes;
    private final Source source;

    /**
     * Constructs a typed project constraint domain model.
     *
     * @param recordId  record ID
     * @param title     constraint title
     * @param rationale constraint rationale
     * @param status    lifecycle status
     * @param effect    enforcement effect
     * @param scopes    target scopes
     * @param source    origin source
     */
    public ProjectConstraint(UUID recordId, String title, String rationale, ConstraintStatus status,
                             Effect effect, List<String> scopes, Source source) {
        this.recordId = Objects.requireNonNull(recordId, "record ID");
        this.title = Objects.requireNonNull(title, "title");
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        this.status = Objects.requireNonNull(status, "status");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Extracts a {@link ProjectConstraint} from a verified {@link DecisionRecord}.
     *
     * @param record target decision record
     * @return extracted constraint, or null if the record is not a constraint
     */
    public static ProjectConstraint fromRecord(DecisionRecord record) {
        Objects.requireNonNull(record, "record");

        ConstraintStatus status = switch (record.status()) {
            case PROPOSED, ACCEPTED -> ConstraintStatus.ACTIVE;
            case REJECTED -> ConstraintStatus.INACTIVE;
            case SUPERSEDED -> ConstraintStatus.SUPERSEDED;
        };

        // 1. Check for typed constraint evidence (kind="constraint:v1" or kind="constraint")
        Effect typedEffect = null;
        List<String> typedScopes = new ArrayList<>();

        for (DecisionEvidence ev : record.evidence()) {
            if ("constraint:v1".equals(ev.kind()) || "constraint".equals(ev.kind())) {
                String ref = ev.reference();
                for (String part : ref.split("\\|")) {
                    if (part.startsWith("effect=")) {
                        String effStr = part.substring("effect=".length()).toUpperCase(Locale.ROOT);
                        if ("WARN".equals(effStr)) {
                            typedEffect = Effect.WARN;
                        } else {
                            typedEffect = Effect.BLOCK;
                        }
                    } else if (part.startsWith("scope=")) {
                        typedScopes.add(part.substring("scope=".length()));
                    }
                }
            } else if ("scope".equals(ev.kind()) && typedEffect != null) {
                typedScopes.add(ev.reference());
            }
        }

        if (typedEffect != null && !typedScopes.isEmpty()) {
            return new ProjectConstraint(record.recordId(), record.title(), record.rationale(),
                    status, typedEffect, typedScopes, Source.TYPED);
        }

        // 2. Legacy fallback for title starting with "CONSTRAINT:"
        if (record.title().regionMatches(true, 0, "CONSTRAINT:", 0, "CONSTRAINT:".length())) {
            List<String> legacyScopes = new ArrayList<>();
            for (DecisionEvidence ev : record.evidence()) {
                if (ev.reference() != null && !ev.reference().isEmpty()) {
                    legacyScopes.add(ev.reference());
                }
            }
            if (legacyScopes.isEmpty()) {
                legacyScopes.add(record.title());
            }
            return new ProjectConstraint(record.recordId(), record.title(), record.rationale(),
                    status, Effect.BLOCK, legacyScopes, Source.LEGACY_INFERRED);
        }

        return null; // Not a constraint
    }

    /**
     * Creates a new signed {@link DecisionRecord} representing a typed project constraint.
     *
     * @param projectId namespace project ID
     * @param recordId  record identity
     * @param ownerNode owner node identity
     * @param effect    constraint effect (BLOCK vs WARN)
     * @param scope     primary scope pattern
     * @param title     constraint title
     * @param rationale constraint rationale
     * @param signer    Ed25519 signer
     * @return signed DecisionRecord containing typed constraint evidence
     * @throws GeneralSecurityException if signing fails
     */
    public static DecisionRecord createTypedRecord(UUID projectId, UUID recordId, String ownerNode,
                                                   Effect effect, String scope, String title,
                                                   String rationale, Ed25519Signer signer) throws GeneralSecurityException {
        Objects.requireNonNull(scope, "scope");
        String normScope = ScopeMatcher.normalizePath(scope);
        String ref = "effect=" + effect.name() + "|scope=" + normScope;
        byte[] digest = sha256(ref);
        DecisionEvidence evidence = new DecisionEvidence("constraint:v1", ref, digest);

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return DecisionRecord.create(projectId, recordId, 1, null, ownerNode, ownerNode,
                DecisionStatus.ACCEPTED, now, now, title, rationale, List.of(evidence), signer);
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
    public UUID recordId() {
        return recordId;
    }

    /**
     * Returns the constraint title.
     *
     * @return title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the constraint rationale.
     *
     * @return rationale
     */
    public String rationale() {
        return rationale;
    }

    /**
     * Returns the constraint lifecycle status.
     *
     * @return status
     */
    public ConstraintStatus status() {
        return status;
    }

    /**
     * Returns the constraint enforcement effect.
     *
     * @return effect
     */
    public Effect effect() {
        return effect;
    }

    /**
     * Returns the list of target scopes.
     *
     * @return scopes
     */
    public List<String> scopes() {
        return scopes;
    }

    /**
     * Returns the origin source of this constraint.
     *
     * @return source
     */
    public Source source() {
        return source;
    }

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
