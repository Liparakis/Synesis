package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.integration.codex.CodexApplyPatchParser;

/** Verifies the bounded Codex patch directive parser. */
final class CodexApplyPatchParserTest {
    @Test
    void parsesAllFileOperationsAndDeduplicatesExactChanges() {
        String patch = """
                *** Begin Patch
                *** Add File: src/new.txt
                +new
                *** Update File: src/old.txt
                @@
                -old
                +new
                *** Move to: src/moved.txt
                *** Delete File: src/delete.txt
                *** Update File: src/duplicate.txt
                *** Update File: src/duplicate.txt
                *** End Patch
                """;

        CodexApplyPatchParser.ParseResult result = new CodexApplyPatchParser().parse(patch);

        assertTrue(result.valid());
        assertEquals(List.of(
                new CodexApplyPatchParser.FileChange(CodexApplyPatchParser.Operation.ADD, "src/new.txt", null),
                new CodexApplyPatchParser.FileChange(CodexApplyPatchParser.Operation.MOVE, "src/old.txt", "src/moved.txt"),
                new CodexApplyPatchParser.FileChange(CodexApplyPatchParser.Operation.DELETE, "src/delete.txt", null),
                new CodexApplyPatchParser.FileChange(CodexApplyPatchParser.Operation.UPDATE, "src/duplicate.txt", null)),
                result.changes());
    }

    @Test
    void rejectsMalformedMarkersUnsupportedDirectivesAndTraversal() {
        CodexApplyPatchParser parser = new CodexApplyPatchParser();

        assertFalse(parser.parse("*** Begin Patch\n*** Add File: src/a\n").valid());
        assertFalse(parser.parse("*** Begin Patch\n*** Rename File: src/a\n*** End Patch").valid());
        assertFalse(parser.parse("*** Begin Patch\n*** Add File: ../outside\n*** End Patch").valid());
        assertFalse(parser.parse("*** Begin Patch\n*** Move to: src/new\n*** End Patch").valid());
    }
}
