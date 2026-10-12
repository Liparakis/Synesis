package org.synesis.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.TaskCompletionProjection;

/**
 * Checks, for every event projection, that validating an event neither changes the projection nor decides
 * differently from applying the same event to an identical copy.
 */
public final class ProjectionValidation {

    private interface Step<P> {
        void run(P projection, PredictionEvent event) throws Exception;
    }

    private record Subject<P>(String name, Supplier<P> create, Step<P> apply, Step<P> validate,
                              Function<P, Object> state) {
    }

    private static final List<Subject<?>> SUBJECTS = List.of(
            new Subject<>("prediction",
                    PredictionProjection::new,
                    PredictionProjection::apply,
                    PredictionProjection::validate,
                    projection -> projection.snapshot()),
            new Subject<>("coordination",
                    CoordinationProjection::new,
                    CoordinationProjection::apply,
                    CoordinationProjection::validate,
                    projection -> Arrays.asList(projection.tasks(), projection.ownerships())),
            new Subject<>("capability",
                    CapabilityRequestProjection::new,
                    CapabilityRequestProjection::apply,
                    CapabilityRequestProjection::validate,
                    projection -> Arrays.asList(projection.records(),
                            projection.allValidationContexts(),
                            projection.recordsByHandle().keySet().stream()
                                    .map(projection::findLatestImplementation).toList())),
            new Subject<>("task completion",
                    TaskCompletionProjection::new,
                    TaskCompletionProjection::apply,
                    TaskCompletionProjection::validate,
                    projection -> Arrays.asList(projection.allSnapshots(),
                            projection.allPrepared(),
                            projection.allPrepared().stream()
                                    .map(prepared -> projection.taskState(prepared.taskIdentity())).toList(),
                            projection.readySnapshots(),
                            projection.pendingReviewSnapshots(),
                            projection.activeIntegrationAttempt(),
                            projection.lastControlHeadAdvanced())),
            new Subject<>("collaboration",
                    CollaborationProjection::new,
                    CollaborationProjection::apply,
                    CollaborationProjection::validate,
                    projection -> Arrays.asList(projection.activeIntents(),
                            projection.participants(),
                            projection.requests(),
                            projection.noChangeCompletions(),
                            projection.activated())),
            new Subject<>("work group",
                    WorkGroupProjection::new,
                    WorkGroupProjection::apply,
                    WorkGroupProjection::validate,
                    projection -> Arrays.asList(projection.groups(),
                            projection.grants(),
                            projection.reviewValidations(),
                            projection.grants().stream()
                                    .map(grant -> Arrays.asList(projection.grantAvailable(grant.identity()),
                                            projection.grantConsumed(grant.identity()),
                                            projection.grantSuperseded(grant.identity()),
                                            projection.continuationGrant(grant.identity()))).toList())),
            new Subject<>("contract",
                    ContractProjection::new,
                    ContractProjection::apply,
                    ContractProjection::validate,
                    projection -> Arrays.asList(projection.contracts(),
                            projection.contracts().stream()
                                    .map(contract -> Arrays.asList(projection.bindings(contract.contractId()),
                                            projection.openChangeRequestsFor(contract.ownerLineageId()),
                                            projection.changeRequestsFiledBy(contract.ownerLineageId()))).toList())));

    private ProjectionValidation() {
    }

    /**
     * Replays {@code log} into each projection. Before each event, and again for every event once the whole log
     * is applied, validation must leave the projection unchanged and end the way applying to a copy ends.
     *
     * @param log the signed events of one project, in order
     */
    public static void assertValidateMatchesApplyOnCopy(List<PredictionEvent> log) throws Exception {
        for (Subject<?> subject : SUBJECTS) {
            check(subject, log);
        }
    }

    private static <P> void check(Subject<P> subject, List<PredictionEvent> log) throws Exception {
        for (int prefix = 0; prefix < log.size(); prefix++) {
            compare(subject, log.subList(0, prefix), log.get(prefix));
        }
        for (PredictionEvent event : log) {
            compare(subject, log, event);
        }
    }

    private static <P> void compare(Subject<P> subject, List<PredictionEvent> prefix, PredictionEvent event)
            throws Exception {
        P live = replay(subject, prefix);
        P twin = replay(subject, prefix);
        Object before = subject.state().apply(live);
        String validated = outcome(() -> subject.validate().run(live, event));
        assertEquals(before, subject.state().apply(live), subject.name() + " changed by validate");
        String applied = outcome(() -> subject.apply().run(twin, event));
        assertEquals(applied, validated, subject.name() + " validate differs from apply on a copy");
    }

    private static <P> P replay(Subject<P> subject, List<PredictionEvent> events) throws Exception {
        P projection = subject.create().get();
        for (PredictionEvent event : events) {
            subject.apply().run(projection, event);
        }
        return projection;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static String outcome(Action action) {
        try {
            action.run();
            return "accepted";
        } catch (Exception rejection) {
            return rejection.getClass().getName() + ": " + rejection.getMessage();
        }
    }
}
