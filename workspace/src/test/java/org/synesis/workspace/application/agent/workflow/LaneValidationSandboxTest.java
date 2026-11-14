package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.project.execution.UnsandboxedAllowance;
import org.synesis.workspace.application.project.execution.sandbox.Sandbox;
import org.synesis.workspace.application.project.execution.sandbox.SandboxAvailability;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;

/**
 * ADR-0104 Part C: a lane whose validation command (with {@code alsoInLane}) was not run for want of a sandbox is answered
 * as {@code run_command} is, not told to retry a failure it cannot fix.
 */
final class LaneValidationSandboxTest {

    @Test
    void aLaneValidationNotRunForWantOfASandboxIsAnsweredSandboxUnavailableNotRetry(@TempDir Path root) {
        Sandbox none = new Sandbox() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public SandboxAvailability probe() {
                return SandboxAvailability.unavailable("no bwrap", "install bubblewrap");
            }

            @Override
            public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) {
                throw new AssertionError("nothing is started without a sandbox");
            }
        };
        var refused = new ProjectProcessExecutor(none, UnsandboxedAllowance.DENY).execute(new ExecutionRequest(List.of("git", "--version"), root, ".", 20, root));

        AgentResponse answer = AgentTaskCompletionService.laneValidationFailed(refused);

        assertEquals(AgentStatus.BLOCKED, answer.status(), answer.toJson());
        assertEquals(AgentReason.SANDBOX_UNAVAILABLE, answer.reason(), answer.toJson());
        assertNotEquals(AgentNextAction.RETRY, answer.nextAction(), "retried, it fails the same way for ever: " + answer.toJson());
    }
}
