package org.synesis.workspace.lifecycle.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.application.ProjectApplicationService;

class SessionSuccessionsTest {

    @Test
    void aConnectionRemembersWhichEarlierConnectionOfTheSameAgentItContinues(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("control-repo");
        Files.createDirectories(root);
        new ProjectApplicationService().init(root);

        assertEquals(Optional.empty(), SessionSuccessions.continuedFrom(root, "conn-2"), "nothing is recorded before a controller resumes an agent");
        SessionSuccessions.continueConnection(root, "conn-2", "conn-1");
        SessionSuccessions.continueConnection(root, "conn-4", "conn-3");

        assertEquals(Optional.of("conn-1"), SessionSuccessions.continuedFrom(root, "conn-2"));
        assertEquals(Optional.of("conn-3"), SessionSuccessions.continuedFrom(root, "conn-4"));
        assertEquals(Optional.empty(), SessionSuccessions.continuedFrom(root, "conn-1"), "the first connection continues nothing");
        assertEquals(Optional.of(Map.of()), SessionSuccessions.predecessors(root), "a continuation is not a succession until the binding service has a session for it");
    }
}
