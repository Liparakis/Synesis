package org.synesis.coordination.domain.collaboration;

import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.task.TaskCompletionProjection;

/**
 * The projections an admission rule reads. A rule that guards authority needs more than one of them (a reviewer
 * is judged against lanes, grants and snapshots), so it takes this view rather than a single projection.
 */
public interface ProjectionView {

    /**
     * Returns the project the projections belong to.
     *
     * @return project identity
     */
    ProjectId projectIdentity();

    /**
     * Returns the lane, request and participant projection.
     *
     * @return collaboration projection
     */
    CollaborationProjection collaborationProjection();

    /**
     * Returns the work-group and lane-grant projection.
     *
     * @return work-group projection
     */
    WorkGroupProjection workGroupProjection();

    /**
     * Returns the snapshot and completion projection.
     *
     * @return task completion projection
     */
    TaskCompletionProjection taskCompletionProjection();

    /**
     * Reports whether the entry being judged is the leader's own: written locally on a computer that is not a
     * member. The leader decides for the project, so it may act for any participant, for example releasing a
     * member's lane after merging it; a member's entry, proposed or judged on the member, may act only for its own.
     *
     * @return {@code true} for a local entry on the leader or a single computer
     */
    default boolean leaderEntry() {
        return false;
    }
}
