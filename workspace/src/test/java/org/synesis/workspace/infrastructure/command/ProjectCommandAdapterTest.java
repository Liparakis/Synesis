package org.synesis.workspace.infrastructure.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.infrastructure.command.DotNetProjectCommandAdapter;
import org.synesis.workspace.infrastructure.git.GitProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.GradleProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.MavenProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.NpmProjectCommandAdapter;
import org.synesis.workspace.application.ProjectCommandIntent;

class ProjectCommandAdapterTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("synesis-adapter-test-");
    }

    @Test
    void testGradleAdapterDetectionAndTokenGeneration() throws Exception {
        GradleProjectCommandAdapter adapter = new GradleProjectCommandAdapter();
        assertFalse(adapter.supports(tempDir));

        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle build\n");
        assertTrue(adapter.supports(tempDir));

        // Build intent
        ProjectCommandIntent buildIntent = new ProjectCommandIntent("build", null, List.of());
        List<String> buildTokens = adapter.buildCommandTokens(tempDir, buildIntent);
        assertTrue(buildTokens.get(buildTokens.size() - 1).equals("build"));

        // Test intent with target
        ProjectCommandIntent testIntent = new ProjectCommandIntent("test", "CatalogTest", List.of());
        List<String> testTokens = adapter.buildCommandTokens(tempDir, testIntent);
        assertTrue(testTokens.contains("test"));
        assertTrue(testTokens.contains("--tests"));
        assertTrue(testTokens.contains("CatalogTest"));
    }

    @Test
    void testMavenAdapterDetectionAndTokenGeneration() throws Exception {
        MavenProjectCommandAdapter adapter = new MavenProjectCommandAdapter();
        assertFalse(adapter.supports(tempDir));

        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");
        assertTrue(adapter.supports(tempDir));

        // Test intent
        ProjectCommandIntent testIntent = new ProjectCommandIntent("test", "CatalogTest", List.of());
        List<String> testTokens = adapter.buildCommandTokens(tempDir, testIntent);
        assertTrue(testTokens.contains("-Dtest=CatalogTest"));
        assertTrue(testTokens.contains("test"));
    }

    @Test
    void testDotNetAdapterDetectionAndTokenGeneration() throws Exception {
        DotNetProjectCommandAdapter adapter = new DotNetProjectCommandAdapter();
        assertFalse(adapter.supports(tempDir));

        Files.writeString(tempDir.resolve("App.csproj"), "<Project></Project>\n");
        assertTrue(adapter.supports(tempDir));

        ProjectCommandIntent buildIntent = new ProjectCommandIntent("build", null, List.of());
        List<String> tokens = adapter.buildCommandTokens(tempDir, buildIntent);
        assertEquals("dotnet", tokens.get(0));
        assertEquals("build", tokens.get(1));
    }

    @Test
    void testNpmAdapterDetectionAndTokenGeneration() throws Exception {
        NpmProjectCommandAdapter adapter = new NpmProjectCommandAdapter();
        assertFalse(adapter.supports(tempDir));

        Files.writeString(tempDir.resolve("package.json"), "{\"name\":\"test\"}\n");
        assertTrue(adapter.supports(tempDir));

        ProjectCommandIntent buildIntent = new ProjectCommandIntent("build", null, List.of());
        List<String> tokens = adapter.buildCommandTokens(tempDir, buildIntent);
        assertTrue(tokens.contains("run"));
        assertTrue(tokens.contains("build"));
    }

    @Test
    void testGitAdapterTokenGeneration() throws Exception {
        GitProjectCommandAdapter adapter = new GitProjectCommandAdapter();
        ProjectCommandIntent statusIntent = new ProjectCommandIntent("git_status", null, List.of());
        List<String> tokens = adapter.buildCommandTokens(tempDir, statusIntent);
        assertEquals("git", tokens.get(0));
        assertEquals("status", tokens.get(1));
        assertEquals("--porcelain", tokens.get(2));
    }

    @Test
    void testProhibitsMetacharactersAndShellStrings() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProjectCommandIntent("test", "CatalogTest; rm -rf /", List.of()));

        assertThrows(IllegalArgumentException.class, () ->
                new ProjectCommandIntent("test", "CatalogTest", List.of("$(whoami)")));
    }
}
