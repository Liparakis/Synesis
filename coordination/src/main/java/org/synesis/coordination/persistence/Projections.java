package org.synesis.coordination.persistence;

import java.io.IOException;
import java.util.List;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.AppendAdmission;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.ProjectionView;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.conversation.ConversationProjection;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.operator.OperatorRequestProjection;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Every projection of one computer's copy of a project, and the three ways an entry reaches them (ADR-0098 step 4,
 * {@link ApplyMode}): this computer's own entry is checked by every rule before it is written; an entry of another
 * computer's log is judged only by the rules that make it count for nothing, applied on copies first, and held back
 * when it names something not seen yet; an entry already taken is applied.
 */
final class Projections implements ProjectionView {

    /**
     * How one arriving entry was taken.
     *
     * @param code empty when the entry applied; otherwise the code it waits on or the rule it broke
     * @param held whether the entry waits for something it names, and is to be tried again
     */
    record Arrival(String code, boolean held) {

        private static final Arrival APPLIED = new Arrival("", false);

        boolean applied() {
            return code.isEmpty();
        }
    }

    private final ProjectId project;
    private final PredictionProjection prediction = new PredictionProjection();
    private final CoordinationProjection coordination = new CoordinationProjection();
    private final CapabilityRequestProjection capabilityRequests = new CapabilityRequestProjection();
    private final TaskCompletionProjection taskCompletion = new TaskCompletionProjection();
    private final CollaborationProjection collaboration = new CollaborationProjection();
    private final WorkGroupProjection workGroups = new WorkGroupProjection();
    private final ContractProjection contracts = new ContractProjection();
    private final OperatorRequestProjection operatorRequests = new OperatorRequestProjection();
    private final ConversationProjection conversations = new ConversationProjection();

    /**
     * Every projection above, in validation and application order; the one place a new projection is registered.
     */
    private final List<Stage> stages = List.of(
            new Stage(prediction::validate, prediction::apply),
            new Stage(coordination::validate, coordination::apply),
            new Stage(capabilityRequests::validate, capabilityRequests::apply),
            new Stage(taskCompletion::validate, taskCompletion::apply),
            new Stage(collaboration::validate, collaboration::apply),
            new Stage(workGroups::validate, workGroups::apply),
            new Stage(contracts::validate, contracts::apply),
            new Stage(operatorRequests::validate, operatorRequests::apply),
            new Stage(conversations::validate, conversations::apply));

    /**
     * Whether the entry being judged was written by the owner's computer; read by the admission rules.
     */
    private boolean leaderEntry;

    Projections(ProjectId project) {
        this.project = CoordinationNullException.requireNonNull(project, "project");
    }

    /**
     * Refuses this computer's own new entry when any rule refuses it. Nothing is changed.
     *
     * @param event  the signed entry about to be written
     * @param leader whether this computer is the owner's, which may act for any participant until ADR-0098 step 5
     * @throws IOException with the refusal code
     */
    void check(PredictionEvent event, boolean leader) throws IOException {
        for (Stage stage : stages) {
            stage.validate().accept(event, ApplyMode.CHECK);
        }
        // Last, so a rule of one projection answers first. It reads every projection and applies nothing.
        leaderEntry = leader;
        try {
            AppendAdmission.check(event, this);
        } finally {
            leaderEntry = false;
        }
    }

    /**
     * Takes an entry of another computer's log: applies it, holds it back, or counts it for nothing. It never
     * throws for what the entry says, so one entry cannot stop a copy of a log.
     *
     * @param event          a verified entry of another computer's log
     * @param signedByLeader whether the owner's computer signed it
     * @return how it was taken
     * @throws IOException when the entry passed its trial and still failed to apply, which is a defect here
     */
    Arrival arrive(PredictionEvent event, boolean signedByLeader) throws IOException {
        leaderEntry = signedByLeader;
        try {
            AppendAdmission.checkArrival(event, this);
            for (Stage stage : stages) {
                stage.validate().accept(event, ApplyMode.ARRIVAL);
            }
        } catch (MissingReferenceException waiting) {
            return new Arrival(waiting.code(), true);
        } catch (CoordinationException broken) {
            return new Arrival(broken.code(), false);
        } catch (IOException | RuntimeException unreadable) {
            return new Arrival("ENTRY_UNREADABLE", false);
        } finally {
            leaderEntry = false;
        }
        apply(event);
        return Arrival.APPLIED;
    }

    /**
     * Applies an entry already judged: this computer's own after its check, or one in its applied order.
     *
     * @param event the entry
     * @throws IOException when a projection cannot apply it
     */
    void apply(PredictionEvent event) throws IOException {
        for (Stage stage : stages) {
            stage.apply().accept(event);
        }
    }

    @Override
    public ProjectId projectIdentity() {
        return project;
    }

    @Override
    public boolean leaderEntry() {
        return leaderEntry;
    }

    PredictionProjection prediction() {
        return prediction;
    }

    CoordinationProjection coordination() {
        return coordination;
    }

    CapabilityRequestProjection capabilityRequests() {
        return capabilityRequests;
    }

    @Override
    public TaskCompletionProjection taskCompletionProjection() {
        return taskCompletion;
    }

    @Override
    public CollaborationProjection collaborationProjection() {
        return collaboration;
    }

    @Override
    public WorkGroupProjection workGroupProjection() {
        return workGroups;
    }

    ContractProjection contracts() {
        return contracts;
    }

    OperatorRequestProjection operatorRequests() {
        return operatorRequests;
    }

    ConversationProjection conversations() {
        return conversations;
    }

    @FunctionalInterface
    private interface Judge {

        void accept(PredictionEvent event, ApplyMode mode) throws IOException;
    }

    @FunctionalInterface
    private interface Transition {

        void accept(PredictionEvent event) throws IOException;
    }

    /**
     * How one projection judges an entry on a copy of itself, and applies it.
     */
    private record Stage(Judge validate, Transition apply) {

    }
}
