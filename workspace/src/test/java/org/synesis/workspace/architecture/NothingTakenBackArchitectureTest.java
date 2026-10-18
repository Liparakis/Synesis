package org.synesis.workspace.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The "nothing taken back" fitness function of ADR-0098 step 7: work that leaves an agent's hands is kept in the
 * trash, and the one deletion of kept work is a person emptying it. Only {@code WorkTrash} deletes a trash ref, and
 * only it names the trash refs besides the class that spells them and the server that serves them.
 */
class NothingTakenBackArchitectureTest {

    private static final Pattern REF_DELETION = Pattern.compile("\"update-ref\",\\s*\"-d\"");

    @Test
    void onlyWorkTrashDeletesATrashRefAndOnlyWhatMayNameThemDoes() throws Exception {
        Path sources = source();
        List<Path> files;
        try (var walk = Files.walk(sources)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty());
        List<String> naming = List.of("WorkTrash.java", "SnapshotRefs.java", "CodeTransferServer.java", "LogNamedCommits.java");
        for (Path file : files) {
            String code = withoutComments(Files.readString(file));
            String name = file.getFileName().toString();
            if (code.contains("trashRef(") || code.contains("refs/synesis/trash")) {
                assertTrue(naming.contains(name), "only the trash's own classes may name a trash ref: " + file);
            }
            if (REF_DELETION.matcher(code).find() && code.contains("trashRef(")) {
                assertEquals("WorkTrash.java", name, "a trash ref is deleted only when a person empties it: " + file);
            }
        }
        String workTrash = withoutComments(Files.readString(files.stream().filter(path -> path.getFileName().toString().equals("WorkTrash.java"))
                .findFirst().orElseThrow()));
        assertEquals(1, REF_DELETION.matcher(workTrash).results().count(), "WorkTrash deletes a ref in one place, deleteRef");
    }

    private static Path source() {
        Path local = Path.of("src/main/java/org/synesis/workspace");
        return Files.isDirectory(local) ? local : Path.of("workspace/src/main/java/org/synesis/workspace");
    }

    private static String withoutComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }
}
