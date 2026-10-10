package org.synesis.workspace.application.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the answers each caller of the workspace path rules has always given, including where the
 * read side and the write side disagree.
 */
class WorkspacePathPolicyTest {

    @Test
    void protectedNamespaceIsCaseInsensitiveAndSeparatorInsensitive() {
        for (String path : new String[] {".synesis", ".codex", ".git", ".synesis/local/x",
            ".CODEX/hooks.json", ".Git/config", ".git\\config", ".synesis\\local"}) {
            assertTrue(WorkspacePathPolicy.isProtectedNamespace(path), path);
            assertTrue(WorkspacePathPolicy.isProtectedTarget(path), path);
            assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath(path), path);
        }
    }

    @Test
    void unlistedNamespacesAndLookalikesAreNotProtected() {
        for (String path : new String[] {".claude/settings.json", "AGENTS.md", ".github/x",
            ".gitignore", ".gitmodules", ".synesis-old/x", "src/.git/config", " .git/config",
            "./.git/config"}) {
            assertFalse(WorkspacePathPolicy.isProtectedNamespace(path), path);
            assertFalse(WorkspacePathPolicy.isProtectedTarget(path), path);
        }
    }

    @Test
    void blankInputIsProtectedForWritesInvalidForReadsAndNotAProtectedNamespace() {
        for (String blank : new String[] {null, "", "  "}) {
            assertFalse(WorkspacePathPolicy.isProtectedNamespace(blank));
            assertTrue(WorkspacePathPolicy.isProtectedTarget(blank));
            assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath(blank));
        }
    }

    @Test
    void readSideRefusesAbsoluteTraversingAndMalformedPaths() {
        String absolute = Path.of("").toAbsolutePath().toString();
        assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath(absolute));
        assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath("../x"));
        assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath("a..b"));
        assertTrue(WorkspacePathPolicy.isInvalidOrProtectedReadPath("a\0b"));
        assertFalse(WorkspacePathPolicy.isInvalidOrProtectedReadPath("src/a.txt"));
    }

    @Test
    void writeSideRawChecksLetMalformedSyntaxPropagate() {
        assertThrows(InvalidPathException.class, () -> WorkspacePathPolicy.isAbsolute("a\0b"));
        assertTrue(WorkspacePathPolicy.containsTraversal("a\0b..c"));
        assertTrue(WorkspacePathPolicy.containsTraversal("a..b"));
        assertFalse(WorkspacePathPolicy.containsTraversal("a/b.txt"));
        assertTrue(WorkspacePathPolicy.isAbsolute(Path.of("").toAbsolutePath().toString()));
    }
}
