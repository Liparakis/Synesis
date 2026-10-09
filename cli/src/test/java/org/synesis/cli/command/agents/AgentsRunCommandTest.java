package org.synesis.cli.command.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import org.synesis.workspace.application.agent.run.AgentRunController;

/**
 * Verifies how an {@code --agent} specification names a provider and its model.
 */
final class AgentsRunCommandTest {

    @Test
    void agentSpecificationNamesProviderThenOptionalModelAndEffort() {
        AgentRunController.AgentSpec bare = AgentsRunCommand.parseAgent("codex");
        assertEquals("codex", bare.provider());
        assertNull(bare.options().model());
        assertNull(bare.options().effort());

        AgentRunController.AgentSpec full = AgentsRunCommand.parseAgent("claude:sonnet:low");
        assertEquals("claude", full.provider());
        assertEquals("sonnet", full.options().model());
        assertEquals("low", full.options().effort());

        assertEquals("medium", AgentsRunCommand.parseAgent("codex::medium").options().effort(), "a model may be left to the provider default");
    }

    @Test
    void malformedOrUnknownSpecificationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("gemini"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent(""));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("claude:sonnet:low:extra"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent("claude:sonnet --dangerously-skip-permissions"));
        assertThrows(IllegalArgumentException.class, () -> AgentsRunCommand.parseAgent(null));
    }
}
