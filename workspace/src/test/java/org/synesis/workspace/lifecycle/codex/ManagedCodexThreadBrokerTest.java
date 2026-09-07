package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipRecord;

/**
 * Verifies the broker's immutable exact-thread pin.
 */
final class ManagedCodexThreadBrokerTest {

    @Test
    void acceptsOnlyActiveCodexOwnershipAndRejectsThreadSwitch() {
        var owner = new ProviderThreadOwnershipRecord(1, "project", "codex", "thread-a", "binding-a",
                ProviderThreadOwnershipRecord.Status.ACTIVE, 1, 1, 1);
        var broker = new ManagedCodexThreadBroker(owner);

        assertDoesNotThrow(() -> broker.requirePinnedThread("thread-a"));
        assertThrows(IllegalStateException.class, () -> broker.requirePinnedThread("thread-b"));
        assertThrows(IllegalStateException.class, () -> broker.verifyReturnedThread("thread-b"));
    }

    @Test
    void unavailableProcessSupervisorFailsClosedBeforeLaunch() {
        var supervisor = ManagedProcessTreeSupervisor.unavailable();

        assertThrows(java.io.IOException.class,
                () -> supervisor.launch(List.of("codex"), Path.of("."), Map.of("CODEX_HOME", "normal")));
    }
}
