package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.util.Objects;
import org.synesis.coordination.application.WorkIntentMutationPrecondition;
import org.synesis.coordination.domain.collaboration.WorkIntent;

/** Workspace boundary wrapper for an optimistic WorkIntent mutation precondition.
 * @param coordinationPrecondition coordination-layer immutable precondition
 */
public record WorkspaceWorkIntentMutationPrecondition(
        WorkIntentMutationPrecondition coordinationPrecondition
) {

    /** Validates the wrapped precondition.
     * @param coordinationPrecondition coordination-layer precondition
     */
    public WorkspaceWorkIntentMutationPrecondition {
        Objects.requireNonNull(coordinationPrecondition, "coordinationPrecondition");
    }

    /** Captures the exact workspace mutation precondition from an intent.
     * @param intent current work intent
     * @return workspace mutation precondition
     */
    public static WorkspaceWorkIntentMutationPrecondition capture(WorkIntent intent) {
        return new WorkspaceWorkIntentMutationPrecondition(WorkIntentMutationPrecondition.capture(intent));
    }

    /** Requires the current intent to retain the captured authority.
     * @param intent current intent
     * @throws IOException when authority changed
     */
    public void requireMatches(WorkIntent intent) throws IOException {
        coordinationPrecondition.requireMatches(intent);
    }
}
