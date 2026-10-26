package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.synesis.workspace.application.provider.ProviderApplicationService;

/**
 * What the runner refuses before it starts anything: it is the one place both {@code agents run} and the page start agents.
 */
final class AgentRunnerTest {

    private final AgentRunner runner = new AgentRunner(new ProviderApplicationService(), java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "synesis-agent-runner-test"));

    @Test
    void agentSpecificationNamesProviderThenOptionalModelAndEffort() {
        assertEquals("codex", AgentRunner.parse("codex").provider());
        assertEquals("sonnet", AgentRunner.parse("claude:sonnet:low").options().model());
        assertEquals("low", AgentRunner.parse("claude:sonnet:low").options().effort());
        assertEquals("medium", AgentRunner.parse("codex::medium").options().effort());
    }

    @Test
    void malformedOrUnknownSpecificationsAreRefusedWithTheirCodes() {
        assertEquals("UNKNOWN_PROVIDER", assertThrows(AgentRunner.Refusal.class, () -> AgentRunner.parse("gemini")).code());
        assertEquals("AGENT_INVALID", assertThrows(AgentRunner.Refusal.class, () -> AgentRunner.parse("")).code());
        assertEquals("AGENT_INVALID", assertThrows(AgentRunner.Refusal.class, () -> AgentRunner.parse("claude:sonnet:low:extra")).code());
        assertEquals("AGENT_INVALID", assertThrows(AgentRunner.Refusal.class, () -> AgentRunner.parse(null)).code());
    }

    @Test
    void aStartNeedsBetweenOneAndTheMostAgentsAndANonBlankTaskOrNone() {
        assertEquals("AGENT_INVALID", assertThrows(AgentRunner.Refusal.class, () -> runner.start(location(), List.of(), "task")).code());
        assertEquals("AGENT_INVALID", assertThrows(AgentRunner.Refusal.class, () -> runner.start(location(), java.util.Collections.nCopies(AgentRunner.MAX_AGENTS + 1, "codex"), "task")).code());
        assertEquals("TASK_REQUIRED", assertThrows(AgentRunner.Refusal.class, () -> runner.start(location(), List.of("codex"), "  ")).code());
        assertEquals(List.of(), runner.runs(), "nothing was started");
    }

    @Test
    void aRunThisRunnerDidNotStartCannotBeStopped() {
        assertFalse(runner.stop("run-" + java.util.UUID.randomUUID()));
    }

    private static org.synesis.workspace.application.project.ProjectLocation location() {
        java.nio.file.Path root = java.nio.file.Path.of("project").toAbsolutePath();
        return new org.synesis.workspace.application.project.ProjectLocation(root, root.resolve(".synesis"), root.resolve("project.json"), root.resolve(".synesis/local/profile"), java.util.UUID.randomUUID(),
                java.time.Instant.now());
    }
}
