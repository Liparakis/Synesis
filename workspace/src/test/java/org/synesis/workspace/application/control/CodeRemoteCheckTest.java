package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.test.TestGit;

/**
 * The check a member runs at join time asks the code remote what a fetch would, without a prompt, and says what a
 * person can do when it fails (two homes, one project, defect 1).
 */
final class CodeRemoteCheckTest {

    @TempDir
    Path temp;
    private Path clone;

    @BeforeEach
    void cloneOfABareRemote() throws Exception {
        Path remote = temp.resolve("code.git");
        TestGit.output(temp, "init", "--bare", remote.toString());
        clone = temp.resolve("clone");
        TestGit.run(temp, "clone", remote.toString(), clone.toString());
    }

    @Test
    void aRemoteThatAnswersPasses() {
        assertDoesNotThrow(() -> CodeRemote.check(clone, "origin"));
    }

    @Test
    void aRemoteThatCannotBeReadSaysWhatGitSaidAndWhatToRun() throws Exception {
        TestGit.run(clone, "remote", "set-url", "origin", temp.resolve("gone.git").toString());

        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class, () -> CodeRemote.check(clone, "origin"));

        assertEquals(CodeRemote.UNAVAILABLE, refused.code());
        assertTrue(refused.detail().contains("does not appear to be a git repository"), refused.detail());
        assertTrue(refused.detail().contains("git fetch origin"), refused.detail());
    }

    @Test
    void credentialsInTheRemoteUrlNeverReachTheDetail() throws Exception {
        TestGit.run(clone, "remote", "set-url", "origin", "https://alice:s3cret@127.0.0.1:1/org/project.git");

        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class, () -> CodeRemote.check(clone, "origin"));

        assertFalse(refused.detail().contains("s3cret"), refused.detail());
        assertFalse(refused.getMessage().contains("s3cret"), refused.getMessage());
    }
}
