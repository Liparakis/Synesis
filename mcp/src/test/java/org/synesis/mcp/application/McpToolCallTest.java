package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies validation at the untrusted tools/call JSON boundary. */
class McpToolCallTest {

    /** Missing arguments stay distinct from a present, empty JSON object. */
    @Test
    void preservesAbsentArgumentsAndTypesPresentArguments() {
        McpToolCall absent = McpToolCall.decode(Map.of("name", "read_file"));
        McpToolCall present = McpToolCall.decode(Map.of("name", "read_file", "arguments", Map.of("path", "README.md")));

        assertEquals(Optional.empty(), absent.arguments());
        assertEquals("read_file", absent.name());
        assertEquals("README.md", present.arguments().orElseThrow().get("path"));
    }

    /** Invalid envelope shapes must become invalid-params errors, not unchecked casts. */
    @Test
    void rejectsInvalidToolCallEnvelopeValues() {
        assertThrows(IllegalArgumentException.class, () -> McpToolCall.decode(null));
        assertThrows(IllegalArgumentException.class, () -> McpToolCall.decode(Map.of("name", "synesis.read_file")));
        assertThrows(IllegalArgumentException.class, () -> McpToolCall.decode(Map.of("name", "read_file", "arguments", "path")));

        assertFalse(McpToolCall.decode(Map.of("name", "read_file", "arguments", Map.of())).arguments().isEmpty());
    }

    /** Top-level tool argument names must be JSON object strings. */
    @Test
    void rejectsNonStringArgumentNames() {
        Map<Object, Object> arguments = Map.of(1, "invalid");
        assertThrows(IllegalArgumentException.class, () -> McpToolCall.decode(Map.of("name", "read_file", "arguments", arguments)));
    }
}
