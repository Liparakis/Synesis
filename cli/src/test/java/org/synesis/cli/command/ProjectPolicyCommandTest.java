package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.CliHarness.Result;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * The commands that sign the owner's decisions (ADR-0104, Part A): they ask for the operator key's passphrase at a
 * terminal, refuse where no one can type it, and leave the project file and the log as they were when they refuse.
 */
class ProjectPolicyCommandTest {

    private static final String PASSPHRASE = ProjectValidationCommandTest.PASSPHRASE;

    @TempDir
    Path tmp;
    private Path root;
    private ProjectLocation location;
    private String project;

    @BeforeEach
    void initProject() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Baseline\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "initial commit");
        location = new ProjectApplicationService().init(root, false).location();
        project = root.toString();
    }

    private static void git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + "\n" + output);
    }

    private Result run(String... arguments) {
        return CliHarness.runTyping(tmp.resolve("cli-profile"), List.of(PASSPHRASE, PASSPHRASE), arguments);
    }

    private Result runTyping(List<String> typed, String... arguments) {
        return CliHarness.runTyping(tmp.resolve("cli-profile"), typed, arguments);
    }

    private String document(String fields) {
        return "{\"schemaVersion\":2,\"projectId\":\"" + location.projectId() + "\",\"createdAt\":\"" + location.createdAt() + "\"" + fields + "}";
    }

    @Test
    void aCommandWithNoTerminalToTypeAtIsRefusedInTheErrorShapeAndSignsNothing() throws Exception {
        Result refused = CliHarness.run(tmp.resolve("cli-profile"), "project", "validation", "set", "--project", project, "--", "git", "status");

        refused.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(),
                CliHarness.shaped("OPERATOR_KEY_UNAVAILABLE", "~REASON=.+"));
        assertFalse(Files.exists(location.operatorKeyFile()), "no passphrase, no key");
        assertEquals("NOT_ADOPTED", policyBaseline(), "no baseline was written");
    }

    private String policyBaseline() {
        Result shown = run("project", "policy", "show", "--project", project);
        return shown.stdout().stream().filter(line -> line.startsWith("POLICY_BASELINE=")).findFirst().orElseThrow().substring("POLICY_BASELINE=".length());
    }

    @Test
    void aWrongPassphraseIsRefusedAndTheDecisionStays() throws Exception {
        assertEquals(0, run("project", "validation", "set", "--project", project, "--", "git", "status").exit());

        Result wrong = runTyping(List.of("not the passphrase"), "project", "validation", "clear", "--project", project);

        wrong.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("OPERATOR_PASSPHRASE_WRONG", "~REASON=.+"));
        assertTrue(run("project", "validation", "show", "--project", project).stdout().contains("VALIDATION=DECLARED"));
    }

    @Test
    void aNewKeyNeedsTheSamePassphraseTwiceAndAtLeastEightCharacters() throws Exception {
        Result mismatch = runTyping(List.of(PASSPHRASE, "something else entirely"), "project", "review", "set", "mandatory", "--project", project);
        Result tooShort = runTyping(List.of("short", "short"), "project", "review", "set", "mandatory", "--project", project);

        mismatch.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("OPERATOR_PASSPHRASE_MISMATCH", "~REASON=.+"));
        tooShort.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("OPERATOR_PASSPHRASE_TOO_SHORT", "~REASON=.+"));
        assertFalse(Files.exists(location.operatorKeyFile()));
    }

    @Test
    void theKeyFileHasNoPlaintextOfThePassphrase() throws Exception {
        assertEquals(0, run("project", "review", "set", "mandatory", "--project", project).exit());

        byte[] file = Files.readAllBytes(location.operatorKeyFile());

        assertFalse(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains(PASSPHRASE));
    }

    @Test
    void reviewIsSetShownAndNoLongerAHandEditedFileField() throws Exception {
        Result set = run("project", "review", "set", "mandatory", "--project", project);

        assertEquals(0, set.exit(), set.toString());
        assertTrue(set.stdout().contains("REVIEW=MANDATORY"), set.toString());
        run("project", "review", "set", "none", "--project", project);
        assertTrue(run("project", "review", "show", "--project", project).stdout().contains("REVIEW=NONE"));
        assertEquals(ExitCodes.USAGE, run("project", "review", "set", "sometimes", "--project", project).exit());
    }

    @Test
    void unsandboxedCommandsAreAllowedAndDeniedBySignedDecisionsOnly() throws Exception {
        assertTrue(run("project", "sandbox", "show", "--project", project).stdout().contains("UNSANDBOXED_COMMANDS=DENY"));

        Result allowed = run("project", "sandbox", "allow-unsandboxed", "--project", project);
        assertEquals(0, allowed.exit(), allowed.toString());
        assertTrue(allowed.stdout().contains("UNSANDBOXED_COMMANDS=ALLOW"), allowed.toString());
        assertTrue(allowed.stdout().stream().anyMatch(line -> line.startsWith("WARNING=")), allowed.toString());
        assertTrue(allowed.stdout().stream().anyMatch(line -> line.startsWith("DECISION_ENTRY=")), allowed.toString());
        assertTrue(run("project", "sandbox", "show", "--project", project).stdout().contains("UNSANDBOXED_COMMANDS=ALLOW"));

        assertEquals(0, run("project", "sandbox", "deny-unsandboxed", "--project", project).exit());
        assertTrue(run("project", "sandbox", "show", "--project", project).stdout().contains("UNSANDBOXED_COMMANDS=DENY"));
    }

    @Test
    void anAllowanceWithNoTerminalToTypeAtIsRefusedAndSignsNothing() throws Exception {
        Result refused = CliHarness.run(tmp.resolve("cli-profile"), "project", "sandbox", "allow-unsandboxed", "--project", project);

        refused.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("OPERATOR_KEY_UNAVAILABLE", "~REASON=.+"));
        assertTrue(run("project", "sandbox", "show", "--project", project).stdout().contains("UNSANDBOXED_COMMANDS=DENY"));
    }

    @Test
    void anEditedProjectFileChangesNothingAndPolicyShowSaysItIsIgnored() throws Exception {
        assertEquals(0, run("project", "validation", "set", "--project", project, "--also-in-lane", "--", "git", "status").exit());
        assertEquals(0, run("project", "review", "set", "mandatory", "--project", project).exit());

        Files.writeString(location.metadataFile(), document(""));
        Result shown = run("project", "policy", "show", "--project", project);

        assertTrue(shown.stdout().contains("POLICY_BASELINE=SIGNED"), shown.toString());
        assertTrue(shown.stdout().contains("POLICY_SOURCE=LOG"), shown.toString());
        assertTrue(shown.stdout().contains("VALIDATION=DECLARED"), shown.toString());
        assertTrue(shown.stdout().contains("VALIDATION_ALSO_IN_LANE=true"), shown.toString());
        assertTrue(shown.stdout().contains("REVIEW=MANDATORY"), shown.toString());
        assertTrue(shown.stdout().contains("UNSANDBOXED_COMMANDS=DENY"), shown.toString());
        assertTrue(shown.stdout().contains("POLICY_FILE=IGNORED"), shown.toString());
    }

    @Test
    void adoptSignsTheCommittedFileAsTheBaselineAndPrintsWhatItAdopted() throws Exception {
        Files.writeString(location.metadataFile(), document(",\"validation\":{\"argv\":[\"git\",\"status\"],\"workingDirectory\":\".\",\"timeoutSeconds\":30},\"review\":{\"mandatory\":true}"));
        git(root, "add", ".synesis/project.json");
        git(root, "commit", "-m", "policy");
        assertEquals("NOT_ADOPTED", policyBaseline());

        Result adopted = run("project", "policy", "adopt", "--project", project);

        assertEquals(0, adopted.exit(), adopted.toString());
        assertTrue(adopted.stdout().contains("POLICY_BASELINE=SIGNED"), adopted.toString());
        assertTrue(adopted.stdout().contains("POLICY_ADOPTED=validation"), adopted.toString());
        assertTrue(adopted.stdout().contains("POLICY_ADOPTED=review"), adopted.toString());
        assertEquals("SIGNED", policyBaseline());
        String signedWith = adopted.stdout().stream().filter(line -> line.startsWith("OPERATOR_KEY=")).findFirst().orElseThrow();
        assertTrue(signedWith.matches("OPERATOR_KEY=sl1-[0-9a-f]{64}"), "the key the project now trusts is shown to the person who made it: " + adopted);
        assertTrue(run("project", "policy", "show", "--project", project).stdout().contains(signedWith), "policy show names the same key");
        Result again = run("project", "policy", "adopt", "--project", project);
        assertEquals(0, again.exit());
        assertTrue(again.stdout().contains("POLICY_ADOPTED=NONE"), "adopting again writes nothing: " + again);
    }

    @Test
    void aDifferingWorkingFileIsNotAdoptedUntilAPersonPicksWhichOne() throws Exception {
        Files.writeString(location.metadataFile(), document(",\"review\":{\"mandatory\":true}"));
        git(root, "add", ".synesis/project.json");
        git(root, "commit", "-m", "policy");
        Files.writeString(location.metadataFile(), document(""));

        Result refused = run("project", "validation", "set", "--project", project, "--", "git", "status");
        Result refusedAdopt = run("project", "policy", "adopt", "--project", project);

        refused.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("POLICY_ADOPTION_NEEDS_CONFIRMATION", "~REASON=.+"));
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, refusedAdopt.exit());
        assertFalse(Files.exists(location.operatorKeyFile()), "nothing was signed");
        assertTrue(run("project", "policy", "show", "--project", project).stdout().contains("POLICY_ADOPTION=CONFIRMATION_NEEDED"));

        Result head = run("project", "policy", "adopt", "--from", "head", "--project", project);

        assertEquals(0, head.exit(), head.toString());
        assertTrue(run("project", "review", "show", "--project", project).stdout().contains("REVIEW=MANDATORY"), "the committed copy was picked");
        assertEquals(ExitCodes.USAGE, run("project", "policy", "adopt", "--from", "elsewhere", "--project", project).exit());
    }

    @Test
    void aLogThatCannotBeReadRefusesTheGateReadersInTheErrorShapeAndNeverFallsBackToTheFile() throws Exception {
        assertEquals(0, run("project", "validation", "set", "--project", project, "--", "git", "status").exit());
        try (var files = Files.walk(location.coordinationDirectory())) {
            for (Path entry : files.filter(Files::isRegularFile).filter(path -> path.toString().contains("events")).toList()) {
                byte[] bytes = Files.readAllBytes(entry);
                Files.write(entry, java.util.Arrays.copyOf(bytes, bytes.length / 2));
            }
        }

        Result shown = run("project", "validation", "show", "--project", project);

        shown.assertIs(ExitCodes.LOCAL_CONFIGURATION, List.of(), CliHarness.shaped("POLICY_UNAVAILABLE", "~REASON=.*"));
        assertEquals(ExitCodes.LOCAL_CONFIGURATION, run("project", "review", "show", "--project", project).exit());
    }

    @Test
    void anInvalidValidationCommandIsRefusedBeforeAnythingIsSigned() throws Exception {
        Result refused = run("project", "validation", "set", "--project", project, "--directory", "/abs", "--", "git", "status");

        assertTrue(refused.exit() != 0, refused.toString());
        assertFalse(Files.exists(location.operatorKeyFile()), "a refused command creates no key");
        assertEquals("NOT_ADOPTED", policyBaseline());
    }
}
