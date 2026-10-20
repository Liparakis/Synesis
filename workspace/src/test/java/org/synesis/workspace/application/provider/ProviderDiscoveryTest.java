package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.provider.ProviderDiscovery.Answer;
import org.synesis.workspace.application.provider.ProviderDiscovery.Finding;
import org.synesis.workspace.provider.ProviderIntegration.SignIn;

/**
 * ADR-0103 stage 2: which agents are installed, their version, and whether each says someone is signed
 * in, learned only from the agents' own commands.
 */
class ProviderDiscoveryTest {

    private static final String TOKEN = "sk-planted-secret-0123456789";

    @TempDir
    Path tmp;

    private Path bin(String... executables) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String name : executables) {
            Path file = Files.writeString(bin.resolve(name), "#!/bin/sh\n");
            assertTrue(file.toFile().setExecutable(true));
        }
        return bin;
    }

    /** Answers like the two harnesses do, and records every command it was asked to run. */
    private static ProviderDiscovery.Runner harness(List<List<String>> asked, Answer claudeStatus, Answer codexStatus) {
        return command -> {
            asked.add(command);
            String joined = String.join(" ", command);
            if (joined.contains("--version")) {
                return new Answer(0, joined.contains("claude") ? "2.1.302 (Claude Code)\n" : "codex-cli 0.163.0\n");
            }
            return joined.contains("claude") ? claudeStatus : codexStatus;
        };
    }

    private static Finding of(List<Finding> findings, String provider) {
        return findings.stream().filter(finding -> finding.provider().equals(provider)).findFirst().orElseThrow();
    }

    @Test
    void noAgentOnThePathIsNotFoundAndNothingIsRun() throws Exception {
        List<List<String>> asked = new ArrayList<>();
        List<Finding> findings = new ProviderDiscovery(bin().toString(), false, harness(asked, new Answer(0, ""), new Answer(0, ""))).discover();

        assertEquals(List.of("claude", "codex"), findings.stream().map(Finding::provider).toList());
        assertTrue(findings.stream().noneMatch(Finding::found));
        assertTrue(asked.isEmpty(), "an agent that is not there is not asked anything");
    }

    @Test
    void oneAgentIsFoundWithItsVersionAndItsOwnSignInAnswer() throws Exception {
        List<List<String>> asked = new ArrayList<>();
        List<Finding> findings = new ProviderDiscovery(bin("claude").toString(), false,
                harness(asked, new Answer(0, "{\"loggedIn\": true, \"authMethod\": \"claude.ai\"}"), new Answer(0, ""))).discover();

        assertEquals(new Finding("claude", true, Optional.of("2.1.302 (Claude Code)"), SignIn.SIGNED_IN), of(findings, "claude"));
        assertFalse(of(findings, "codex").found());
        String claude = tmp.resolve("bin/claude").toAbsolutePath().toString();
        assertEquals(List.of(List.of(claude, "--version"), List.of(claude, "auth", "status", "--json")), asked, "what was found is what is run");
    }

    @Test
    void twoAgentsAreFoundAndEachAnswersForItself() throws Exception {
        List<Finding> findings = new ProviderDiscovery(bin("claude", "codex").toString(), false,
                harness(new java.util.concurrent.CopyOnWriteArrayList<>(), new Answer(1, "{\"loggedIn\": false}"), new Answer(0, "Logged in using ChatGPT\n"))).discover();

        assertEquals(new Finding("claude", true, Optional.of("2.1.302 (Claude Code)"), SignIn.SIGNED_OUT), of(findings, "claude"));
        assertEquals(new Finding("codex", true, Optional.of("codex-cli 0.163.0"), SignIn.SIGNED_IN), of(findings, "codex"));
    }

    @Test
    void aHarnessWithoutAStatusCommandOrWithAnAnswerWeDoNotKnowIsCouldNotTell() throws Exception {
        // Codex before `login status` answers with a usage error; Claude's wording could change.
        List<Finding> findings = new ProviderDiscovery(bin("claude", "codex").toString(), false,
                harness(new java.util.concurrent.CopyOnWriteArrayList<>(), new Answer(0, "authentication is fine, probably"), new Answer(2, "error: unrecognized subcommand 'status'"))).discover();

        assertEquals(SignIn.UNKNOWN, of(findings, "claude").signIn());
        assertEquals(SignIn.UNKNOWN, of(findings, "codex").signIn());
        assertTrue(of(findings, "claude").found() && of(findings, "codex").found());
    }

    @Test
    void aCommandThatFailsLeavesTheAnswerUnknownButTheAgentFound() throws Exception {
        ProviderDiscovery.Runner broken = command -> {
            throw new IOException("did not finish");
        };
        Finding finding = of(new ProviderDiscovery(bin("codex").toString(), false, broken).discover(), "codex");

        assertEquals(new Finding("codex", true, Optional.empty(), SignIn.UNKNOWN), finding);
    }

    @Test
    void aVersionCommandThatFailsGivesNoVersion() throws Exception {
        Finding finding = of(new ProviderDiscovery(bin("codex").toString(), false, command -> new Answer(1, "boom")).discover(), "codex");

        assertTrue(finding.found());
        assertEquals(Optional.empty(), finding.version());
    }

    @Test
    void aFileThatIsNotExecutableIsNotAnAgent() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path file = Files.writeString(bin.resolve("claude"), "#!/bin/sh\n");
        Assumptions.assumeTrue(file.toFile().setExecutable(false) && !Files.isExecutable(file), "this file system has no execute permission");

        assertFalse(of(new ProviderDiscovery(bin.toString(), false, command -> new Answer(0, "")).discover(), "claude").found());
    }

    @Test
    void windowsLooksForAnExeOrACmdShim() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("codex.cmd"), "@echo off\r\n");
        List<List<String>> asked = new ArrayList<>();

        List<Finding> findings = new ProviderDiscovery(bin.toString(), true, harness(asked, new Answer(0, ""), new Answer(0, "Not logged in\n"))).discover();

        assertTrue(of(findings, "codex").found());
        assertEquals(SignIn.SIGNED_OUT, of(findings, "codex").signIn());
        assertEquals("cmd.exe", asked.getFirst().getFirst(), "a shim is started through the command interpreter");
        assertFalse(of(findings, "claude").found());
    }

    /**
     * A status command may print an account name or a token. Discovery keeps the sign-in state and the version line, so
     * nothing of the rest reaches a finding, a log or an error, with the real process runner.
     */
    @Test
    void aTokenInTheirOutputOrInTheirCredentialFilesNeverSurfaces() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path claudeCredentials = Files.writeString(Files.createDirectories(home.resolve(".claude")).resolve(".credentials.json"), "{\"accessToken\":\"" + TOKEN + "\"}");
        Path codexCredentials = Files.writeString(Files.createDirectories(home.resolve(".codex")).resolve("auth.json"), "{\"OPENAI_API_KEY\":\"" + TOKEN + "\"}");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        String claudeStatus = "{\"loggedIn\": true, \"email\": \"person@example.com\", \"token\": \"" + TOKEN + "\"}";
        String codexStatus = "Logged in using an API key - " + TOKEN;
        if (windows) {
            // Shims, as Codex ships on Windows: started through the command interpreter.
            Files.writeString(bin.resolve("claude.cmd"), "@echo off\r\nif \"%1\"==\"--version\" goto version\r\necho " + claudeStatus + "\r\nexit /b 0\r\n:version\r\necho 3.0.0 (Claude Code)\r\n");
            Files.writeString(bin.resolve("codex.cmd"), "@echo off\r\nif \"%1\"==\"--version\" goto version\r\necho " + codexStatus + "\r\nexit /b 0\r\n:version\r\necho codex-cli 1.0.0\r\n");
        } else {
            Files.writeString(bin.resolve("claude"), "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo \"3.0.0 (Claude Code)\"; else echo '" + claudeStatus + "'; fi\n");
            Files.writeString(bin.resolve("codex"), "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo \"codex-cli 1.0.0\"; else echo \"" + codexStatus + "\"; fi\n");
            assertTrue(bin.resolve("claude").toFile().setExecutable(true) && bin.resolve("codex").toFile().setExecutable(true));
        }
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            if (!windows) {
                try {
                    new ProcessBuilder(bin.resolve("claude").toString(), "--version").start().waitFor();
                } catch (IOException cannotRun) {
                    Assumptions.abort("this file system does not run scripts: " + cannotRun.getMessage());
                }
            }
            List<Finding> findings = new ProviderDiscovery(bin.toString(), windows).discover();

            assertEquals(Optional.of("3.0.0 (Claude Code)"), of(findings, "claude").version());
            assertEquals(SignIn.SIGNED_IN, of(findings, "claude").signIn());
            assertEquals(SignIn.SIGNED_IN, of(findings, "codex").signIn());
            String everything = findings.toString();
            assertFalse(everything.contains(TOKEN), everything);
            assertFalse(everything.contains("person@example.com"), everything);
            assertEquals("{\"accessToken\":\"" + TOKEN + "\"}", Files.readString(claudeCredentials));
            assertEquals("{\"OPENAI_API_KEY\":\"" + TOKEN + "\"}", Files.readString(codexCredentials));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    /**
     * Discovery's only file access is looking up executables on the search path; it never reads a file, so it cannot open
     * a credential file (ADR-0103: the planted-token test guards what the output shows, this guards what is read).
     */
    @Test
    void discoveryNeverReadsAFile() throws Exception {
        Path sources = Path.of("src/main/java/org/synesis/workspace/application/provider");
        if (!Files.isDirectory(sources)) {
            sources = Path.of("workspace").resolve(sources);
        }
        for (String name : List.of("ProviderDiscovery.java", "ProviderExecutables.java")) {
            String source = Files.readString(sources.resolve(name));
            for (String reading : List.of("readString", "readAllBytes", "readAllLines", "newInputStream", "newBufferedReader", "FileInputStream", "FileReader", "user.home", "Files.lines", "Files.copy")) {
                assertFalse(source.contains(reading), name + " must not use " + reading);
            }
        }
    }
}
