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
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.trash.TrashProjection;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Every projection of one computer's copy of a project, and the three ways an entry reaches them (ADR-0098 step 4,
 * {@link ApplyMode}): this computer's own entry is checked by every rule before it is written; an entry of another
 * computer's log is judged only by the rules that make it count for nothing, applied on copies first, and held back
 * when it names something not seen yet; an entry already taken is applied.
 */
final class Projections implements ProjectionView {

    private static final System.Logger LOGGER = System.getLogger(Projections.class.getName());

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
    private final MembershipProjection membership;
    private final TrashProjection trash = new TrashProjection();

    /**
     * Every projection above, in validation and application order; the one place a new projection is registered.
     */
    private final List<Stage> stages = List.of(
            new Stage((event, mode, revision) -> prediction.validate(event, mode),
                    (event, revision) -> prediction.apply(event)),
            new Stage((event, mode, revision) -> coordination.validate(event, mode),
                    (event, revision) -> coordination.apply(event)),
            new Stage(capabilityRequests::validate, capabilityRequests::apply),
            new Stage((event, mode, revision) -> taskCompletion.validate(event, mode),
                    (event, revision) -> taskCompletion.apply(event)),
            new Stage(collaboration::validate, collaboration::apply),
            new Stage((event, mode, revision) -> workGroups.validate(event, mode),
                    (event, revision) -> workGroups.apply(event)),
            new Stage((event, mode, revision) -> contracts.validate(event, mode),
                    (event, revision) -> contracts.apply(event)),
            new Stage((event, mode, revision) -> operatorRequests.validate(event, mode),
                    (event, revision) -> operatorRequests.apply(event)),
            new Stage(conversations::validate, conversations::apply),
            new Stage((event, mode, revision) -> membership().validate(event, mode),
                    (event, revision) -> membership().apply(event)),
            new Stage((event, mode, revision) -> trash.validate(event, mode),
                    (event, revision) -> trash.apply(event)));


    Projections(ProjectId project) {
        this.project = CoordinationNullException.requireNonNull(project, "project");
        this.membership = new MembershipProjection(project.value());
    }

    /**
     * Refuses this computer's own new entry when any rule refuses it. Nothing is changed.
     *
     * @param event    the signed entry about to be written
     * @param revision the local revision the entry would take
     * @throws IOException with the refusal code
     */
    void check(PredictionEvent event, long revision) throws IOException {
        for (Stage stage : stages) {
            stage.validate().accept(event, ApplyMode.CHECK, revision);
        }
        // Last, so a rule of one projection answers first. It reads every projection and applies nothing.
        AppendAdmission.check(event, this);
    }

    /**
     * Takes an entry of another computer's log: applies it, holds it back, or counts it for nothing. It never
     * throws for what the entry says, so one entry cannot stop a copy of a log.
     *
     * @param event    a verified entry of another computer's log
     * @param revision the local revision the entry takes if it applies
     * @return how it was taken
     * @throws IOException when the entry passed its trial and still failed to apply, which is a defect here
     */
    Arrival arrive(PredictionEvent event, long revision) throws IOException {
        try {
            AppendAdmission.checkArrival(event, this);
            for (Stage stage : stages) {
                stage.validate().accept(event, ApplyMode.ARRIVAL, revision);
            }
        } catch (MissingReferenceException waiting) {
            return new Arrival(waiting.code(), true);
        } catch (CoordinationException broken) {
            collaboration.countedForNothing(event);
            return new Arrival(broken.code(), false);
        } catch (CoordinationStateException broken) {
            collaboration.countedForNothing(event);
            return new Arrival("PREDICTION_TRANSITION_INVALID", false);
        } catch (IOException | RuntimeException unreadable) {
            collaboration.countedForNothing(event);
            LOGGER.log(System.Logger.Level.WARNING, "An arriving entry could not be applied and counts for nothing.",
                    unreadable);
            return new Arrival("ENTRY_UNREADABLE", false);
        }
        apply(event, revision);
        return Arrival.APPLIED;
    }

    /**
     * Applies an entry already judged: this computer's own after its check, or one in its applied order.
     *
     * @param event    the entry
     * @param revision its local revision
     * @throws IOException when a projection cannot apply it
     */
    void apply(PredictionEvent event, long revision) throws IOException {
        for (Stage stage : stages) {
            stage.apply().accept(event, revision);
        }
        // A removed computer's lane announced before its removal may arrive after it: it ends as its removal ended the
        // others, so every computer frees the same claims whatever the order (ADR-0098 step 6, designer item 10).
        if (event.type() == PredictionEventType.WORK_INTENT_ANNOUNCED && membership.removedHead(event.actorNodeId()).isPresent()) {
            collaboration.endLanesOf(event.actorNodeId());
        }
    }

    @Override
    public ProjectId projectIdentity() {
        return project;
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

    MembershipProjection membership() {
        return membership;
    }

    ConversationProjection conversations() {
        return conversations;
    }

    TrashProjection trash() {
        return trash;
    }

    @FunctionalInterface
    private interface Judge {

        void accept(PredictionEvent event, ApplyMode mode, long revision) throws IOException;
    }

    @FunctionalInterface
    private interface Transition {

        void accept(PredictionEvent event, long revision) throws IOException;
    }

    /**
     * How one projection judges an entry on a copy of itself, and applies it.
     */
    private record Stage(Judge validate, Transition apply) {

    }
}
