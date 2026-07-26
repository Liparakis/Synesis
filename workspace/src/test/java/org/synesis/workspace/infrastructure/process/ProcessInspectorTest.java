package org.synesis.workspace.infrastructure.process;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessInspectorTest {

    @Test
    void evaluatesNotObservedWhenPidNullOrZero() {
        ProcessInspector inspector = ProcessInspector.system();
        assertEquals(ProcessEvidenceState.NOT_OBSERVED, inspector.evaluateEvidence(null, "java", "SynesisMcpServer"));
        assertEquals(ProcessEvidenceState.NOT_OBSERVED, inspector.evaluateEvidence(0L, "java", "SynesisMcpServer"));
    }

    @Test
    void evaluatesNotObservedWhenProcessHandleFails() {
        ProcessInspector mockInspector = pid -> Optional.empty();
        assertEquals(ProcessEvidenceState.NOT_OBSERVED, mockInspector.evaluateEvidence(99999L, "java", "SynesisMcpServer"));
    }

    @Test
    void evaluatesLiveVerifiedWhenExecutableAndCommandMatch() {
        ProcessInspector mockInspector = pid -> Optional.of(new ProcessInspector.ProcessDetails(1234L, "C:\\jdk\\bin\\java.exe", "java -jar SynesisMcpServer.jar", true));

        ProcessEvidenceState state = mockInspector.evaluateEvidence(1234L, "java", "SynesisMcpServer");
        assertEquals(ProcessEvidenceState.LIVE_VERIFIED, state);
    }

    @Test
    void evaluatesPidReusedOrMismatchedWhenExecutableDiffers() {
        ProcessInspector mockInspector = pid -> Optional.of(new ProcessInspector.ProcessDetails(5678L, "C:\\Windows\\notepad.exe", "notepad.exe text.txt", true));

        ProcessEvidenceState state = mockInspector.evaluateEvidence(5678L, "java", "SynesisMcpServer");
        assertEquals(ProcessEvidenceState.PID_REUSED_OR_MISMATCHED, state);
    }
}
