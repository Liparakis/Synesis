package org.synesis.coordination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRequestHandleTest {

    @Test
    void validatesHandleFormatAndPrefix() {
        assertTrue(CapabilityRequestHandle.isValid("req_K7F3M2X9Q4V8N2"));
        assertTrue(CapabilityRequestHandle.isValid("req_1234567890ABCDEF"));
        assertFalse(CapabilityRequestHandle.isValid("invalid_handle"));
        assertFalse(CapabilityRequestHandle.isValid("req_short"));
        assertFalse(CapabilityRequestHandle.isValid(null));
        assertFalse(CapabilityRequestHandle.isValid(""));
    }

    @Test
    void secureRandomGeneratorProducesValidHandlesWithEntropy() {
        SecureRandomCapabilityRequestHandleGenerator generator = new SecureRandomCapabilityRequestHandleGenerator();
        CapabilityRequestHandle handle1 = generator.generate();
        CapabilityRequestHandle handle2 = generator.generate();

        assertNotNull(handle1);
        assertNotNull(handle2);
        assertTrue(handle1.value().startsWith("req_"));
        assertTrue(CapabilityRequestHandle.isValid(handle1.value()));
        assertFalse(handle1.value().equals(handle2.value()));
    }

    @Test
    void handleDoesNotEncodeMetadata() {
        SecureRandomCapabilityRequestHandleGenerator generator = new SecureRandomCapabilityRequestHandleGenerator();
        CapabilityRequestHandle handle = generator.generate();

        String val = handle.value();
        assertFalse(val.contains("project"));
        assertFalse(val.contains("worker"));
        assertFalse(val.contains("session"));
        assertFalse(val.contains("worktree"));
    }

    @Test
    void rejectsInvalidHandleParsing() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityRequestHandle.parse("bad_handle"));
    }

    @Test
    void parsesAndNormalizesValidHandle() {
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_k7f3m2x9q4v8n2");
        assertEquals("req_K7F3M2X9Q4V8N2", handle.value());
        assertEquals("req_K7F3M2X9Q4V8N2", handle.handle());
    }
}
