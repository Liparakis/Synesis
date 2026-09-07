package org.synesis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipStore;

/**
 * Verifies fail-closed MCP launcher argument admission.
 */
class SynesisMcpServerTest {

    @Test
    void explicitProjectWithoutValueRejectsStartup() {
        assertEquals(2, SynesisMcpServer.execute(new String[]{"--project"}));
    }

    @Test
    void explicitProjectCannotConsumeAnotherOptionAsItsValue() {
        assertEquals(2, SynesisMcpServer.execute(new String[]{"--project", "--provider", "codex"}));
    }

    @Test
    void exactManagedBindingCannotDowngradeWithoutProof() throws Exception {
        var root = Files.createTempDirectory("synesis-mcp-proof-gate-");
        var location = new ProjectApplicationService().init(root)
                .location();
        var binding = new ProviderSessionBindingService().ensure(location, "codex", "connection-a")
                .binding();
        var owner = ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId()
                                .toString(), "codex",
                        "thread-a", binding.sessionId());
        var attachment = new ManagedAttachmentService().issueFromOwnership(location,
                location.projectId()
                        .toString(), "codex", binding.sessionId(), "normal-provider-home", owner,
                ManagedAttachmentService.storeFor(location, binding.sessionId()));

        assertThrows(Exception.class, () -> SynesisMcpServer.rejectProoflessManagedAttachment(root, "codex",
                "connection-a"));
        Assertions.assertNotNull(attachment.proof());
    }
}
