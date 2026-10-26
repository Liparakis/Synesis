package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.PolicyService;
import org.synesis.coordination.application.PolicyService.Decision;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.policy.PolicyProjection;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.identity.OperatorKeyException;
import org.synesis.link.identity.OperatorKeyStore;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The project's gating policy as the owner's signed decisions give it (ADR-0104, Part A): the door
 * {@code ProjectApplicationService.locate} reads validation, review and the lane gate through, the commands that
 * write a decision, and the migration of a project whose policy is still in {@code project.json}.
 *
 * <p>Once the baseline entry is applied, {@code project.json} is ignored for policy; before it, the file is the
 * policy, as it always was, because nothing else exists to read. If the log cannot be read the policy is unavailable
 * and nothing gates or ungates: reading never falls back to the file. A member computer that has not applied the
 * owner's baseline has no policy: its location refuses every policy accessor ({@link PolicyNotReceivedException})
 * instead of integrating with the gate off, while everything else on it works so the baseline can arrive.</p>
 *
 * <p>Writing needs the operator key, which a person unlocks with a passphrase at a terminal. This class is used by
 * commands a person runs; the MCP server and the daemon read policy and never write it.</p>
 */
public final class ProjectPolicyService {

    /** The operator request raised when the file and the committed file disagree on a policy nobody has signed yet. */
    public static final String ADOPTION_CONDITION = "policy_adoption_needs_confirmation";

    /**
     * The policy in force, as the signed log gives it.
     *
     * @param validation         the validation command, or {@code null} for none
     * @param validationAlsoInLane whether it also runs in each lane alone
     * @param review             the review policy of every work group announced
     * @param unsandboxedAllowed whether agents' commands may run on a computer with no sandbox
     * @param sandboxPaths       the extra sandbox folders, one {@code ro:} or {@code rw:} line each
     * @param revision           the owner-statement revision, the one the next decision is written at
     * @param statements         how many decisions are applied, the baseline included
     * @param operatorKeyId      the registered operator key as a node ID derived from its public half, to compare by eye
     */
    public record Snapshot(ProjectCommandSpec validation, boolean validationAlsoInLane, ReviewPolicy review, boolean unsandboxedAllowed,
                           String sandboxPaths, long revision, long statements, String operatorKeyId) {
    }

    /**
     * What this computer's log says about policy.
     *
     * @param snapshot       the policy in force, or empty before a baseline is applied
     * @param shared         whether the project is shared
     * @param ownsTheProject whether this computer may write decisions: it is the owner, or the project is not shared
     */
    public record Reading(Optional<Snapshot> snapshot, boolean shared, boolean ownsTheProject) {
    }

    /**
     * The policy a file holds, which is what a baseline adopts.
     *
     * @param validation   the validation command, or {@code null}
     * @param alsoInLane   whether it also runs in each lane alone
     * @param reviewMandatory whether review is mandatory
     */
    public record FilePolicy(ProjectCommandSpec validation, boolean alsoInLane, boolean reviewMandatory) {

        static FilePolicy of(ProjectLocation location) {
            return new FilePolicy(location.validation(), location.validationAlsoInLane(), location.review().mandatory());
        }

        /**
         * Whether this is what a project with no policy has.
         *
         * @return whether nothing is declared
         */
        public boolean isDefault() {
            return validation == null && !alsoInLane && !reviewMandatory;
        }

        private Map<PolicyName, String> decisions() {
            Map<PolicyName, String> decisions = new LinkedHashMap<>();
            decisions.put(PolicyName.VALIDATION, ProjectMetadataCodec.validationValue(validation));
            decisions.put(PolicyName.VALIDATION_ALSO_IN_LANE, Boolean.toString(alsoInLane));
            decisions.put(PolicyName.REVIEW, reviewMandatory ? "MANDATORY" : "NONE");
            return decisions;
        }
    }

    /**
     * The two places an unsigned policy can be read from.
     *
     * @param head              the file as committed on the control branch's head, or empty when it is not committed
     * @param working           the file in the working tree
     * @param needsConfirmation whether adopting either one silently could adopt an edit nobody confirmed
     */
    public record Adoption(Optional<FilePolicy> head, FilePolicy working, boolean needsConfirmation) {
    }

    /** Which copy of the file a person adopts as the baseline. */
    public enum AdoptionChoice {
        /** The file as committed on the control branch's head: only integration moves that branch. */
        HEAD,
        /** The file in the working tree. */
        FILE
    }

    /**
     * Asks a person for the operator key's passphrase.
     */
    @FunctionalInterface
    public interface OperatorPrompt {

        /**
         * Asks for the passphrase.
         *
         * @param newKey whether a key is being created, so the person should type it twice
         * @return the passphrase, or {@code null} when no person can be asked; the caller clears it
         * @throws IOException when the terminal cannot be read
         */
        char[] passphrase(boolean newKey) throws IOException;
    }

    /**
     * What a decision command did.
     *
     * @param written  how many entries were appended, the baseline included
     * @param revision the owner-statement revision after the write
     * @param entry    the sequence of the last entry written in this computer's log, or zero when none
     * @param adopted  what the baseline adopted from the file, or empty when this command wrote no baseline
     * @param operatorKeyId the operator key that signed, as a node ID derived from its public half
     */
    public record Decided(int written, long revision, long entry, Optional<FilePolicy> adopted, String operatorKeyId) {
    }

    private ProjectPolicyService() {
    }

    // ---- reading ----

    /**
     * Gives a location read from the file the policy of the signed log, once the log has a baseline. The log is read
     * when a gate first asks for policy, not when the location is made, so a command that never asks does not need a
     * readable log, and a location kept for a long time follows the log: it is read again whenever an entry has been
     * recorded since. A log that cannot be read refuses the gate ({@link PolicyUnavailableException}); a member that
     * has not applied the owner's baseline refuses every policy accessor ({@link PolicyNotReceivedException}) while
     * everything else on it works, so the baseline can arrive.
     *
     * @param file the location as the file reads
     * @return the same location with the policy in force
     */
    public static ProjectLocation overlay(ProjectLocation file) {
        return file.withPolicySource(new LogPolicy(file));
    }

    /** The policy of one location, read from the log when asked and again when an entry has been recorded since. */
    private static final class LogPolicy implements ProjectLocation.PolicySource {

        private final ProjectLocation file;
        private long recorded = -1;
        private ProjectLocation.Policy cached;

        LogPolicy(ProjectLocation file) {
            this.file = file;
        }

        @Override
        public synchronized ProjectLocation.Policy current() {
            // Every change of the policy begins with an entry recorded in this computer's log or a copy of another's.
            long now = PredictionEventStore.recordedEvents(file.coordinationDirectory());
            if (cached != null && now == recorded) {
                return cached;
            }
            Reading reading;
            try {
                reading = read(file);
            } catch (ProjectApplicationException unreadable) {
                throw new PolicyUnavailableException(unreadable);
            }
            ProjectLocation.Policy policy;
            if (reading.snapshot().isPresent()) {
                Snapshot signed = reading.snapshot().get();
                policy = new ProjectLocation.Policy(signed.validation(), signed.review(), signed.validationAlsoInLane(), true);
            } else if (!reading.ownsTheProject()) {
                policy = new ProjectLocation.Policy(null, ReviewPolicy.none(), false, false);
            } else if (Files.exists(file.operatorKeyFile())) {
                // This computer made the project's operator key, so a baseline was meant to exist. A log without it has
                // lost it, and the file an agent could have written is not the policy.
                throw new PolicyUnavailableException(new IOException("an operator key exists on this computer but the log holds no baseline"));
            } else {
                policy = new ProjectLocation.Policy(file.validation(), file.review(), file.validationAlsoInLane(), true);
            }
            recorded = now;
            cached = policy;
            return policy;
        }
    }

    /**
     * Reads the policy from this computer's log without changing anything.
     *
     * @param location the project
     * @return what the log says
     * @throws ProjectApplicationException {@code POLICY_UNAVAILABLE} when the log cannot be read, or holds a decision
     *                                     this build cannot read
     */
    public static Reading read(ProjectLocation location) throws ProjectApplicationException {
        Path coordination = location.coordinationDirectory();
        if (!Files.isDirectory(coordination) || PredictionEventStore.recordedEvents(coordination) == 0) {
            return new Reading(Optional.empty(), false, !joining(coordination));
        }
        try {
            return read(location, PredictionEventStore.openReadOnly(coordination, location.projectId()));
        } catch (IOException | GeneralSecurityException | RuntimeException unreadable) {
            throw new ProjectApplicationException("POLICY_UNAVAILABLE", "The project's signed policy could not be read from its log, so no gate runs and none is skipped", unreadable);
        }
    }

    private static Reading read(ProjectLocation location, PredictionEventStore store) throws IOException {
        PolicyProjection policy = store.policyProjection();
        MembershipProjection membership = store.membershipProjection();
        boolean owns = !membership.shared() || membership.owner().map(owner -> owner.equals(localNode(location))).orElse(false);
        if (!policy.baselineApplied()) {
            return new Reading(Optional.empty(), membership.shared(), owns);
        }
        Snapshot snapshot = new Snapshot(ProjectMetadataCodec.parseValidationValue(policy.validation()), policy.validationAlsoInLane(),
                policy.reviewMandatory() ? ReviewPolicy.mandatoryIndependent() : ReviewPolicy.none(), policy.unsandboxedAllowed(),
                policy.sandboxPaths(), membership.revision(), policy.statements(), NodeIdentity.deriveNodeId(policy.operatorKey().orElseThrow()));
        return new Reading(Optional.of(snapshot), membership.shared(), owns);
    }

    /**
     * Whether this computer has joined another's project and has not received a single entry yet, so that an empty log
     * means "not synced" and not "a project of my own". Ambiguous settings count as joining: a computer that cannot say
     * it owns the project does not decide for it.
     */
    private static boolean joining(Path coordination) {
        if (!Files.isDirectory(coordination)) {
            return false;
        }
        try {
            return !ReplicationPeers.read(coordination).dialed().isEmpty()
                    || ReplicationSettings.read(coordination).map(ReplicationSettings::isMember).orElse(false);
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    /** This computer's node ID without creating anything; empty when the identity cannot be read, which owns nothing. */
    private static String localNode(ProjectLocation location) {
        return new IdentityBootstrap(location.linkDirectory()).inspect().nodeId();
    }

    /**
     * Compares the file with the policy in force, for doctor.
     *
     * @param location the project
     * @return a description of how the file disagrees, or empty when it agrees or no baseline exists
     * @throws ProjectApplicationException when the log cannot be read
     */
    public static Optional<String> fileDisagreement(ProjectLocation location) throws ProjectApplicationException {
        Reading reading = read(location);
        if (reading.snapshot().isEmpty()) {
            return Optional.empty();
        }
        FilePolicy file;
        try {
            file = FilePolicy.of(ProjectMetadataCodec.readFile(location));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        Snapshot policy = reading.snapshot().get();
        FilePolicy signed = new FilePolicy(policy.validation(), policy.validationAlsoInLane(), policy.review().mandatory());
        if (file.equals(signed)) {
            return Optional.empty();
        }
        return Optional.of("this file says " + describe(file) + ", the signed policy says " + describe(signed) + "; the signed policy is used");
    }

    private static String describe(FilePolicy policy) {
        return "validation=" + (policy.validation() == null ? "none" : String.join(" ", policy.validation().argv()))
                + " alsoInLane=" + policy.alsoInLane() + " review=" + (policy.reviewMandatory() ? "mandatory" : "none");
    }

    // ---- migration ----

    /**
     * Finds what a baseline would adopt: the file as committed on the control branch's head, because only integration
     * moves that branch and agents cannot reach it, and the file in the working tree, which any process can write.
     *
     * @param location the project
     * @return the two copies and whether they disagree enough to need a person's pick
     * @throws ProjectApplicationException when the file cannot be read
     */
    public static Adoption adoption(ProjectLocation location) throws ProjectApplicationException {
        try {
            FilePolicy working = FilePolicy.of(ProjectMetadataCodec.readFile(location));
            Optional<FilePolicy> head = committedPolicy(location);
            // Untracked, uncommitted or outside Git, the file has nothing to vouch for it: it is adopted without a
            // question only when it declares nothing.
            boolean needs = head.map(committed -> !committed.equals(working)).orElse(!working.isDefault());
            return new Adoption(head, working, needs);
        } catch (IOException unreadable) {
            throw new ProjectApplicationException("MALFORMED", "Project metadata is malformed", unreadable);
        }
    }

    private static Optional<FilePolicy> committedPolicy(ProjectLocation location) {
        try {
            GitProcessRunner.Result shown = GitProcessRunner.runResult(location.root(), "show", "HEAD:.synesis/project.json");
            if (shown.exitCode() != 0) {
                return Optional.empty();
            }
            return Optional.of(FilePolicy.of(ProjectMetadataCodec.readDocument(location, shown.output())));
        } catch (IOException | RuntimeException notCommitted) {
            return Optional.empty();
        }
    }

    /**
     * Raises or closes the operator request that asks a person to confirm the policy to adopt (ADR-0104, Migration),
     * on the computer that owns the project. Raised once while the file and the committed file disagree and no
     * baseline exists; closed when a baseline is applied or the disagreement is gone.
     *
     * @param location the project
     * @param store    this computer's log, open for appends
     * @param node     this computer's node key
     * @return whether a request is open after this call
     * @throws IOException              when an entry is refused
     * @throws GeneralSecurityException when signing fails
     */
    public static boolean reconcileAdoptionRequest(ProjectLocation location, PredictionEventStore store, NodeIdentity node) throws IOException, GeneralSecurityException {
        MembershipProjection membership = store.membershipProjection();
        if (membership.shared() && !membership.owner().map(owner -> owner.equals(node.nodeId())).orElse(false)) {
            return false;
        }
        Optional<OperatorRequest> open = store.operatorRequestProjection().open(ADOPTION_CONDITION);
        Adoption found;
        try {
            found = adoption(location);
        } catch (ProjectApplicationException unreadable) {
            // Nothing new is known about the file: a request that stands stands.
            return open.isPresent();
        }
        boolean needed = !store.policyProjection().baselineApplied() && found.needsConfirmation();
        if (needed && open.isEmpty()) {
            String evidence = "committed: " + found.head().map(ProjectPolicyService::describe).orElse("not committed") + "\nworking file: " + describe(found.working());
            OperatorRequest request = OperatorRequest.raised("opr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), ADOPTION_CONDITION,
                    "The policy in .synesis/project.json differs from the committed one, or is uncommitted, so no policy is signed. Pick one with 'synesis project policy adopt --from head' or '--from file'.", evidence);
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, node.nodeId(), request.encodeRaised(), node);
            return true;
        }
        if (!needed && open.isPresent()) {
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, node.nodeId(),
                    OperatorRequest.encodeClosed(open.get().id(), "The policy was signed or the files agree."), node);
            return false;
        }
        return open.isPresent();
    }

    // ---- writing ----

    /**
     * Signs decisions with the operator key, writing the baseline first on a project that has none: the policy of the
     * file the person picked (or the only one, when no pick is needed), then the decisions asked for.
     *
     * @param location  the project
     * @param prompt    how to ask a person for the passphrase
     * @param decisions what to decide, which wins over what the baseline adopts
     * @param choice    which copy of the file to adopt when a baseline is written and the copies disagree; may be empty
     * @return what was written
     * @throws ProjectApplicationException {@code NOT_THE_OWNER}, {@code POLICY_UNAVAILABLE}, {@code OPERATOR_KEY_UNAVAILABLE}
     *                                     (no person to ask), {@code OPERATOR_PASSPHRASE_WRONG}, {@code OPERATOR_KEY_MISSING}
     *                                     (a baseline exists but its key is not on this computer), {@code OPERATOR_KEY_MISMATCH},
     *                                     {@code POLICY_ADOPTION_NEEDS_CONFIRMATION}, or the entry's refusal
     */
    public static Decided decide(ProjectLocation location, OperatorPrompt prompt, Map<PolicyName, String> decisions, Optional<AdoptionChoice> choice)
            throws ProjectApplicationException {
        Objects.requireNonNull(prompt, "prompt");
        if (decisions.containsKey(PolicyName.VALIDATION)) {
            // What is signed must read back: a command the reader refuses would leave the project's policy unreadable.
            try {
                ProjectMetadataCodec.parseValidationValue(decisions.get(PolicyName.VALIDATION));
            } catch (IOException | RuntimeException invalid) {
                throw new ProjectApplicationException("PROJECT_INVALID", "The validation command is not valid: " + invalid.getMessage(), invalid);
            }
        }
        Reading reading = read(location);
        if (!reading.ownsTheProject()) {
            throw new ProjectApplicationException("NOT_THE_OWNER", "Only the owner's computer decides the project's policy");
        }
        Map<PolicyName, String> wanted = new LinkedHashMap<>();
        Optional<FilePolicy> adopted = Optional.empty();
        boolean baseline = reading.snapshot().isPresent();
        if (!baseline) {
            Adoption found = adoption(location);
            FilePolicy picked = pick(found, choice);
            adopted = Optional.of(picked);
            wanted.putAll(picked.decisions());
        }
        wanted.putAll(decisions);
        OperatorKeyStore keys = new OperatorKeyStore(location.operatorKeyFile());
        char[] passphrase = null;
        try {
            boolean creating = !keys.exists();
            if (creating && baseline) {
                throw new ProjectApplicationException("OPERATOR_KEY_MISSING", "This project has a signed policy, but its operator key is not on this computer");
            }
            passphrase = prompt.passphrase(creating);
            if (passphrase == null || passphrase.length == 0) {
                throw new ProjectApplicationException("OPERATOR_KEY_UNAVAILABLE", "The operator key needs a passphrase typed at a terminal, and none could be asked for");
            }
            NodeIdentity operator = creating ? keys.create(passphrase) : keys.unlock(passphrase);
            List<Decision> pending = wanted.entrySet().stream().map(entry -> new Decision(entry.getKey(), entry.getValue())).toList();
            PolicyService.Written written = new PolicyService(location.openCoordinationStore(), location.loadOrCreateIdentity()).decide(operator, pending);
            return new Decided(written.written(), written.revision(), written.entry(), adopted, NodeIdentity.deriveNodeId(operator.publicKeyEncoded()));
        } catch (OperatorKeyException wrong) {
            throw new ProjectApplicationException(wrong.code(), "The operator key could not be used");
        } catch (CoordinationException refused) {
            throw new ProjectApplicationException(refused.code(), "The decision was refused: " + refused.code());
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new ProjectApplicationException("POLICY_WRITE_FAILED", "The decision could not be written", failure);
        } finally {
            if (passphrase != null) {
                java.util.Arrays.fill(passphrase, '\0');
            }
        }
    }

    private static FilePolicy pick(Adoption found, Optional<AdoptionChoice> choice) throws ProjectApplicationException {
        if (choice.isPresent()) {
            return switch (choice.get()) {
                case FILE -> found.working();
                case HEAD -> found.head().orElseThrow(() -> new ProjectApplicationException("POLICY_ADOPTION_NEEDS_CONFIRMATION",
                        "project.json is not committed on the control branch, so there is no committed policy to adopt; use --from file"));
            };
        }
        if (found.needsConfirmation()) {
            throw new ProjectApplicationException("POLICY_ADOPTION_NEEDS_CONFIRMATION",
                    "The policy in project.json differs from the committed one, or is not committed; choose with 'synesis project policy adopt --from head' or '--from file'");
        }
        return found.head().orElse(found.working());
    }
}
