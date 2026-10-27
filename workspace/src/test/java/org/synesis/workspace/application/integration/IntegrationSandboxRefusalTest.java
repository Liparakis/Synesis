package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Lane;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.project.execution.UnsandboxedAllowance;
import org.synesis.workspace.application.project.execution.sandbox.Sandbox;
import org.synesis.workspace.application.project.execution.sandbox.SandboxAvailability;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.application.project.execution.sandbox.SandboxSetupException;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;

/**
 * The validation gate on a computer with no sandbox (ADR-0104, Part C): the command is not run, nothing is refused or
 * integrated, one operator request names the fix and is not raised again, and a recheck that can run the command
 * integrates and closes it.
 */
class IntegrationSandboxRefusalTest {

    private static final Sandbox NONE = new Sandbox() {
        @Override
        public String name() {
            return "none-installed";
        }

        @Override
        public SandboxAvailability probe() {
            return SandboxAvailability.unavailable("no bwrap", "install bubblewrap");
        }

        @Override
        public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
            throw new SandboxSetupException("not available");
        }
    };

    private static AgentResponse pump(Project project, UnsandboxedAllowance allowance, boolean askedByOperator) throws Exception {
        IntegrationOrchestrationService service = new IntegrationOrchestrationService(() -> {
        }, new ProjectProcessExecutor(NONE, allowance));
        return service.orchestrateIntegration(project.root, project.store(), project.identity, askedByOperator);
    }

    @Test
    void aGateThatCannotRunRaisesOneRequestNamingTheFixAndALaterAllowanceIntegratesAndClosesIt(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("git", "rev-parse", "--verify", "HEAD"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        AgentResponse blocked = pump(project, UnsandboxedAllowance.DENY, false);

        assertEquals(AgentStatus.BLOCKED, blocked.status(), blocked.toString());
        assertEquals(AgentReason.OPERATOR_REQUIRED, blocked.reason(), blocked.toString());
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1"));
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_VALIDATION_REFUSED), "no verdict, so nothing is refused");
        OperatorRequest request = project.openRequest().orElseThrow();
        assertTrue(request.summary().contains("has no sandbox"), request.summary());
        assertTrue(request.summary().contains("synesis project sandbox allow-unsandboxed"), request.summary());
        assertTrue(request.evidence().contains("ERROR=SANDBOX_UNAVAILABLE"), request.evidence());
        assertTrue(request.evidence().contains("FIX=install bubblewrap"), request.evidence());

        long attempts = project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED);
        AgentResponse again = pump(project, UnsandboxedAllowance.DENY, false);

        assertEquals(AgentReason.OPERATOR_REQUIRED, again.reason(), again.toString());
        assertEquals(attempts, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED), "an agent's pass starts no attempt while the request is open");
        assertEquals(1, project.store().operatorRequestProjection().open().size(), "raised once");

        AgentResponse integrated = pump(project, controlRoot -> true, true);

        assertEquals(AgentStatus.COMPLETED, integrated.status(), integrated.toString());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        assertTrue(project.openRequest().isEmpty(), "the request closes when the command runs");
    }
}
