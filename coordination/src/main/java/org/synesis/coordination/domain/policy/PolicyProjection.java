package org.synesis.coordination.domain.policy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.policy.PolicyEntries.ProjectPolicySet;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;
import org.synesis.link.identity.NodeIdentity;

/**
 * The owner's decisions about how the project is gated, read from {@code PROJECT_POLICY_SET} entries (ADR-0104,
 * Part A). Gates ask this projection and never a file an agent could write.
 *
 * <p>Two signatures stand behind every decision. The entry is signed by the writer's node key, like every entry, and
 * {@link MembershipProjection} judges it as an owner statement: the same revision counter as the membership
 * statements, so policy and membership have one order, and in a shared project only the owner's computer counts. The
 * payload is signed again by the operator key the baseline registered, which sits behind a passphrase and is used
 * only by commands a person runs, so reading the node key forges nothing. The baseline is the first entry: it names
 * the operator key and is signed by it. A computer that has not applied a baseline has no policy.</p>
 *
 * <p>The rules are checked on this computer's own append and on arrival alike; an arriving entry that breaks one
 * counts for nothing. An entry that needs a baseline not applied yet waits for it, so every computer ends with the
 * same policy whatever the order.</p>
 */
public final class PolicyProjection {

    private final UUID projectId;
    private final MembershipProjection membership;
    private byte[] operatorKey;
    private String baselineSigner;
    private String validation = "";
    private boolean alsoInLane;
    private boolean reviewMandatory;
    private boolean unsandboxedAllowed;
    private String sandboxPaths = "";
    private long statements;

    /**
     * Creates an empty projection.
     *
     * @param projectId  the project the operator signatures name
     * @param membership the membership projection that judges the owner statement and its revision
     */
    public PolicyProjection(UUID projectId, MembershipProjection membership) {
        this.projectId = projectId;
        this.membership = membership;
    }

    /**
     * Checks an entry against the policy rules without changing anything.
     *
     * @param event the entry
     * @param mode  how it is judged; a replay is not judged again
     * @throws IOException with the rule it breaks
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        if (mode == ApplyMode.REPLAY) {
            return;
        }
        switch (event.type()) {
            case PROJECT_SHARED -> {
                // The computer that holds the baseline becomes the owner and nothing is re-keyed: sharing from another
                // node would leave decisions signed by a node key that is no longer the owner's.
                if (baselineSigner != null && !baselineSigner.equals(event.actorNodeId())) {
                    throw new CoordinationException("OWNER_NOT_THE_POLICY_BASELINE_SIGNER");
                }
            }
            case PROJECT_POLICY_SET -> validateDecision(ProjectPolicySet.decode(event.payload()), event.actorNodeId(), mode);
            default -> {
            }
        }
    }

    private void validateDecision(ProjectPolicySet decision, String signer, ApplyMode mode) throws IOException {
        if (!membership.shared() && baselineSigner != null && !baselineSigner.equals(signer)) {
            throw new CoordinationException("NOT_THE_OWNER");
        }
        byte[] statement = decision.statement(projectId);
        if (operatorKey == null) {
            if (decision.name() != PolicyName.OPERATOR_KEY) {
                // The baseline is in the owner's log before any decision, so on arrival it comes first.
                throw mode.checking() ? new CoordinationException("POLICY_BASELINE_REQUIRED") : new MissingReferenceException("POLICY_BASELINE_REQUIRED");
            }
            requireSignature(Base64.getDecoder().decode(decision.value()), statement, decision, "POLICY_SIGNATURE_INVALID");
            return;
        }
        requireSignature(operatorKey, statement, decision, decision.name() == PolicyName.OPERATOR_KEY ? "POLICY_BASELINE_EXISTS" : "POLICY_SIGNATURE_INVALID");
    }

    private static void requireSignature(byte[] key, byte[] statement, ProjectPolicySet decision, String code) throws CoordinationException {
        try {
            if (!NodeIdentity.verifySignature(key, statement, decision.operatorSignature())) {
                throw new CoordinationException(code);
            }
        } catch (GeneralSecurityException | RuntimeException malformed) {
            throw new CoordinationException(code, malformed);
        }
    }

    /**
     * Applies a judged entry.
     *
     * @param event the entry
     * @throws IOException when its payload cannot be read
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        if (event.type() != PredictionEventType.PROJECT_POLICY_SET) {
            return;
        }
        ProjectPolicySet decision = ProjectPolicySet.decode(event.payload());
        statements++;
        switch (decision.name()) {
            case OPERATOR_KEY -> {
                operatorKey = Base64.getDecoder().decode(decision.value());
                if (baselineSigner == null) {
                    baselineSigner = event.actorNodeId();
                }
            }
            case VALIDATION -> validation = decision.value();
            case VALIDATION_ALSO_IN_LANE -> alsoInLane = Boolean.parseBoolean(decision.value());
            case REVIEW -> reviewMandatory = decision.value().equals("MANDATORY");
            case SANDBOX_UNSANDBOXED -> unsandboxedAllowed = decision.value().equals("ALLOW");
            case SANDBOX_PATHS -> sandboxPaths = decision.value();
        }
    }

    /**
     * Whether the owner's baseline entry has been applied. Without it this computer has no policy.
     *
     * @return whether a baseline is applied
     */
    public synchronized boolean baselineApplied() {
        return operatorKey != null;
    }

    /**
     * Returns the registered operator key's public half, X.509 encoded.
     *
     * @return the key, or empty before the baseline
     */
    public synchronized Optional<byte[]> operatorKey() {
        return Optional.ofNullable(operatorKey).map(byte[]::clone);
    }

    /**
     * Returns the computer that wrote the baseline, which is the owner.
     *
     * @return the node, or empty before the baseline
     */
    public synchronized Optional<String> baselineSigner() {
        return Optional.ofNullable(baselineSigner);
    }

    /**
     * Returns the validation command as the JSON text it was decided in.
     *
     * @return the text, empty when the project declares none
     */
    public synchronized String validation() {
        return validation;
    }

    /**
     * Returns whether the validation command also runs in each lane alone.
     *
     * @return whether it does
     */
    public synchronized boolean validationAlsoInLane() {
        return alsoInLane;
    }

    /**
     * Returns whether every work group needs an independent review.
     *
     * @return whether review is mandatory
     */
    public synchronized boolean reviewMandatory() {
        return reviewMandatory;
    }

    /**
     * Returns whether agents' commands may run on a computer with no sandbox.
     *
     * @return whether the owner allowed it; the default is no
     */
    public synchronized boolean unsandboxedAllowed() {
        return unsandboxedAllowed;
    }

    /**
     * Returns the extra sandbox folders the owner declared, one {@code ro:} or {@code rw:} line each.
     *
     * @return the lines, empty when none
     */
    public synchronized String sandboxPaths() {
        return sandboxPaths;
    }

    /**
     * Returns how many decisions are applied, the baseline included.
     *
     * @return the number of applied decisions
     */
    public synchronized long statements() {
        return statements;
    }
}
