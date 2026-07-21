package org.synesis.workspace.application;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.Ed25519Signer;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.projectrecord.ScopeMatcher;

/** Creates and persists typed project constraints without CLI concerns. */
public final class ConstraintApplicationService {
    /** Creates the service. */
    public ConstraintApplicationService() {
    }

    /**
     * Creates one signed typed constraint in the project's local record store.
     *
     * @param location initialized project location
     * @param title constraint title
     * @param rationale constraint rationale
     * @param scope repository-relative scope
     * @param effect constraint effect
     * @return structured creation result
     * @throws ApplicationException if project state or input is invalid
     */
    public ConstraintCreateResult create(ProjectApplicationService.ProjectLocation location, String title,
            String rationale, String scope, ProjectConstraint.Effect effect) throws ApplicationException {
        Objects.requireNonNull(location, "location");
        try {
            ProjectConfig config = ProjectConfig.load(location.profile().resolve("project.conf"));
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            DecisionRecord record = ProjectConstraint.createTypedRecord(config.projectId(), UUID.randomUUID(),
                    identity.nodeId(), Objects.requireNonNull(effect, "effect"),
                    Objects.requireNonNull(scope, "scope"), Objects.requireNonNull(title, "title"),
                    Objects.requireNonNull(rationale, "rationale"), Ed25519Signer.from(identity));
            DecisionStore store = new DecisionStore(location.profile().resolve("records"), config.projectId());
            DecisionStore.SaveResult saved = store.save(record, null);
            if (saved != DecisionStore.SaveResult.APPLIED && saved != DecisionStore.SaveResult.DUPLICATE) {
                throw new ApplicationException("INVALID_RECORD", "Constraint record was not accepted");
            }
            return new ConstraintCreateResult(record.projectId(), record.recordId(), record.digestHex(),
                    record.status().name(), effect.name(), ScopeMatcher.normalizePath(scope));
        } catch (ApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ApplicationException("PROJECT_NOT_CONFIGURED", "Project constraint could not be created", failure);
        }
    }

    /**
     * Structured constraint creation result.
     * @param projectId project identifier
     * @param recordId constraint record identifier
     * @param digest record digest
     * @param status record status
     * @param effect constraint effect
     * @param scope normalized scope
     */
    public record ConstraintCreateResult(UUID projectId, UUID recordId, String digest, String status,
            String effect, String scope) {
        /** Validates the result fields. */
        public ConstraintCreateResult {
            Objects.requireNonNull(projectId, "project ID");
            Objects.requireNonNull(recordId, "record ID");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Safe failure returned by the constraint service. */
    public static final class ApplicationException extends Exception {
        /** Serialized exception identifier. */
        private static final long serialVersionUID = 1L;
        /** Stable application failure code. */
        private final String code;

        /**
         * Creates a failure.
         * @param code stable code
         * @param message safe message
         */
        public ApplicationException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Creates a failure with a cause.
         * @param code stable code
         * @param message safe message
         * @param cause cause
         */
        public ApplicationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Returns the stable failure code.
         * @return code
         */
        public String code() {
            return code;
        }
    }
}
