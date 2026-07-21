package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ScopeMatcherTest {

    @Test
    void normalizesPathSeparatorsAndDots() {
        assertEquals("src/protocol/RecordMessage.java", ScopeMatcher.normalizePath("src\\protocol\\RecordMessage.java"));
        assertEquals("src/protocol/RecordMessage.java", ScopeMatcher.normalizePath("./src//protocol/./RecordMessage.java"));
        assertEquals("file.java", ScopeMatcher.normalizePath("file.java"));
    }

    @Test
    void rejectsAbsolutePathsAndDirectoryTraversal() {
        assertThrows(IllegalArgumentException.class, () -> ScopeMatcher.normalizePath("/src/protocol/File.java"));
        assertThrows(IllegalArgumentException.class, () -> ScopeMatcher.normalizePath("../src/protocol/File.java"));
        assertThrows(IllegalArgumentException.class, () -> ScopeMatcher.normalizePath("src/../protocol/File.java"));
        assertThrows(IllegalArgumentException.class, () -> ScopeMatcher.normalizePath("C:\\project\\src\\protocol\\File.java"));
        assertThrows(IllegalArgumentException.class, () -> ScopeMatcher.normalizePath("D:/project/src/protocol/File.java"));
    }

    @Test
    void matchesWildcardDoubleAsterisk() {
        assertTrue(ScopeMatcher.matches("src/protocol/**", "src/protocol/RecordMessage.java"));
        assertTrue(ScopeMatcher.matches("src/protocol/**", "src/protocol/internal/Codec.java"));
        assertFalse(ScopeMatcher.matches("src/protocol/**", "src/protocol-old/Codec.java"));
    }

    @Test
    void matchesSingleAsteriskWithinSegment() {
        assertTrue(ScopeMatcher.matches("src/*/Codec.java", "src/protocol/Codec.java"));
        assertFalse(ScopeMatcher.matches("src/*/Codec.java", "src/protocol/internal/Codec.java"));
    }

    @Test
    void matchingIsCaseSensitiveAndExact() {
        assertTrue(ScopeMatcher.matches("src/protocol/RecordMessage.java", "src/protocol/RecordMessage.java"));
        assertFalse(ScopeMatcher.matches("src/protocol/RecordMessage.java", "src/Protocol/RecordMessage.java"));
        assertFalse(ScopeMatcher.matches("src/protocol/RecordMessage.java", "src/protocol/OtherMessage.java"));
    }
}
