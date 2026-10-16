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
}
