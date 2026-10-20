package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the predefined task of the onboarding measurement (ADR-0103, "Two onboarding times, measured apart"): the
 * checked-in fixture fails its one test, the task text is the agreed sentence, and the one-line fix passes the same
 * unchanged test, so every measured run asks for the same, possible work.
 */
@Timeout(60)
final class FirstTaskFixtureTest {

    @TempDir
    Path tmp;

    @Test
    void theTaskTextIsTheAgreedSentence() throws IOException {
        assertEquals("Make the failing test pass without changing the test", Files.readString(fixture().resolve("task.txt"), StandardCharsets.UTF_8).strip());
    }

    @Test
    void theFixtureTestFailsUntilTheOneLineFixIsMadeAndThePassingRunLeavesTheTestUnchanged() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "the fixture test is a POSIX shell script");
        Path work = Files.createDirectories(tmp.resolve("repo"));
        for (String name : new String[]{"lib.sh", "test.sh"}) {
            Files.copy(fixture().resolve("repo").resolve(name), work.resolve(name));
        }

        assertEquals(1, run(work), "the fixture's test must fail before the task is done");

        Path lib = work.resolve("lib.sh");
        Files.writeString(lib, Files.readString(lib, StandardCharsets.UTF_8).replace("$1 - $2", "$1 + $2"), StandardCharsets.UTF_8);
        assertEquals(0, run(work), "the fixture's test must pass once the task is done");
        assertEquals(Files.readString(fixture().resolve("repo/test.sh"), StandardCharsets.UTF_8), Files.readString(work.resolve("test.sh"), StandardCharsets.UTF_8));
    }

    private static int run(Path directory) throws Exception {
        Process process = new ProcessBuilder("sh", "test.sh").directory(directory.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
    }

    private static Path fixture() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("docs/fixtures/first-task"))) {
            directory = directory.getParent();
        }
        assertTrue(directory != null, "docs/fixtures/first-task not found above " + Path.of("").toAbsolutePath());
        return directory.resolve("docs/fixtures/first-task");
    }
}
