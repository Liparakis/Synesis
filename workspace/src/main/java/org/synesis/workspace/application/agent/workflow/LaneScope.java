package org.synesis.workspace.application.agent.workflow;

import java.nio.file.Path;

/**
 * Identifies the caller's lane inside the project. Shared by the review, completion and
 * orchestration stages, which all act on the same lane.
 *
 * @param participantId exact participant handle of the calling session
 * @param worktree      verified assigned worktree, or {@code null} when unknown
 * @param baseCommit    base commit recorded in the provider binding
 */
record LaneScope(String participantId, Path worktree, String baseCommit) {

}
