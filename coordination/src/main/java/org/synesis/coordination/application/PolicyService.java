package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.policy.PolicyEntries.ProjectPolicySet;
import org.synesis.coordination.domain.policy.PolicyProjection;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Writes this computer's policy decisions (ADR-0104, Part A). Each decision is signed twice: by the node key, as every
 * entry is, and by the operator key a person has just unlocked. Only commands a person runs build this service with
 * an operator key; the MCP server and the daemon never hold one. Every sequence runs under the project append lock
 * on a log read under it, so the revision a decision carries is the one it is judged at.
 */
public final class PolicyService {

    /**
     * One thing to decide.
     *
     * @param name  what is decided
     * @param value the decision
     */
    public record Decision(PolicyName name, String value) {

        /** Validates the decision as the entry would. */
        public Decision {
            CoordinationNullException.requireNonNull(name, "name");
            CoordinationNullException.requireNonNull(value, "value");
        }
    }

    /**
     * What a write did.
     *
     * @param written  how many entries were appended, the baseline included; zero when everything was decided already
     * @param revision the owner-statement revision after the write, the one the next decision is written at
     * @param entry    the sequence in this computer's log of the last entry written, or zero when none was
     */
    public record Written(int written, long revision, long entry) {
    }

    private final NodeIdentity signer;
    private final ProjectAppends appends;

    /**
     * Writes this computer's policy decisions to its log.
     *
     * @param store  this computer's log
     * @param signer this computer's node key
     */
    public PolicyService(PredictionEventStore store, NodeIdentity signer) {
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
        this.appends = new ProjectAppends(CoordinationNullException.requireNonNull(store, "store"));
    }

    /**
     * Decides, writing the baseline first when the project has none. A decision equal to the one in force writes
     * nothing, so repeating a command after a lost response changes nothing.
     *
     * @param operator  the unlocked operator key; the one the baseline registered, or the one to register
     * @param decisions what to decide, in order
     * @return what was written
     * @throws IOException              {@code OPERATOR_KEY_MISMATCH} when the key is not the registered one,
     *                                  {@code NOT_THE_OWNER} on a computer that is not the owner, or the entry's refusal
     * @throws GeneralSecurityException when signing fails
     */
    public Written decide(NodeIdentity operator, List<Decision> decisions) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(operator, "operator");
        return appends.call(current -> {
            PolicyProjection policy = current.policyProjection();
            List<Decision> pending = new ArrayList<>();
            String registered = Base64.getEncoder().encodeToString(operator.publicKeyEncoded());
            if (!policy.baselineApplied()) {
                pending.add(new Decision(PolicyName.OPERATOR_KEY, registered));
            } else if (!Arrays.equals(policy.operatorKey().orElseThrow(), operator.publicKeyEncoded())) {
                throw new CoordinationException("OPERATOR_KEY_MISMATCH");
            }
            for (Decision decision : decisions) {
                if (!decision.value().equals(valueInForce(policy, decision.name()))) {
                    pending.add(decision);
                }
            }
            int written = 0;
            long entry = 0;
            for (Decision decision : pending) {
                long revision = current.membershipProjection().revision();
                byte[] statement = ProjectPolicySet.statement(current.projectId(), revision, decision.name(), decision.value());
                ProjectPolicySet payload = new ProjectPolicySet(revision, decision.name(), decision.value(), operator.sign(statement));
                PredictionEvent event = current.append(UUID.randomUUID(), PredictionEventType.PROJECT_POLICY_SET, signer.nodeId(), payload.encode(), signer);
                written++;
                entry = event.sequence();
            }
            return new Written(written, current.membershipProjection().revision(), entry);
        });
    }

    /**
     * Returns the decision in force in text form, which is what an entry carries; the default before the baseline.
     *
     * @param policy the projection
     * @param name   what is decided
     * @return the value in force
     */
    public static String valueInForce(PolicyProjection policy, PolicyName name) {
        return switch (name) {
            case OPERATOR_KEY -> policy.operatorKey().map(key -> Base64.getEncoder().encodeToString(key)).orElse("");
            case VALIDATION -> policy.validation();
            case VALIDATION_ALSO_IN_LANE -> Boolean.toString(policy.validationAlsoInLane());
            case REVIEW -> policy.reviewMandatory() ? "MANDATORY" : "NONE";
            case SANDBOX_UNSANDBOXED -> policy.unsandboxedAllowed() ? "ALLOW" : "DENY";
            case SANDBOX_PATHS -> policy.sandboxPaths();
        };
    }
}
