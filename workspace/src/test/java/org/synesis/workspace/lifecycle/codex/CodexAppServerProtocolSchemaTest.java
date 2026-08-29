package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated-schema request-ID union used by Codex 0.145.0.
 */
class CodexAppServerProtocolSchemaTest {

    @Test
    void acceptsNumericServerRequestId() {
        assertDoesNotThrow(() -> CodexAppServerProtocolSchema.validateFrame(Map.of(
                "id", 0L,
                "method", "mcpServer/elicitation/request",
                "params", Map.of())));
    }

    @Test
    void acceptsNumericResponseId() {
        assertDoesNotThrow(() -> CodexAppServerProtocolSchema.validateFrame(Map.of(
                "id", 0L,
                "result", Map.of("ok", true))));
    }

    @Test
    void rejectsFractionalRequestId() {
        assertThrows(IOException.class, () -> CodexAppServerProtocolSchema.validateFrame(Map.of(
                "id", 1.5,
                "result", Map.of())));
    }
}
