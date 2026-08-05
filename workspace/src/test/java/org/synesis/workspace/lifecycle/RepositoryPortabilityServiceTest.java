package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies complete-tree, host-independent portability checks. */
class RepositoryPortabilityServiceTest {

    private final RepositoryPortabilityService service = new RepositoryPortabilityService();

    @Test
    void changedPathCollidingWithUnchangedBaselineIsRejected() {
        RepositoryPortabilityService.Report report = service.validateEntries("tree", List.of(
                entry("src/Parser.java"), entry("src/parser.java")));

        assertTrue(has(report, RepositoryPortabilityService.FindingCode.CASE_COLLISION));
    }

    @Test
    void unicodeAndWindowsAliasesAreDetectedAcrossTheCompleteTree() {
        RepositoryPortabilityService.Report report = service.validateEntries("tree", List.of(
                entry("café.txt"), entry("cafe\u0301.txt"), entry("README"), entry("README."),
                entry("CON.txt")));

        assertTrue(has(report, RepositoryPortabilityService.FindingCode.UNICODE_NORMALIZATION_COLLISION));
        assertTrue(has(report, RepositoryPortabilityService.FindingCode.TRAILING_ALIAS_COLLISION));
        assertTrue(has(report, RepositoryPortabilityService.FindingCode.WINDOWS_RESERVED_NAME));
    }

    @Test
    void separatorsSymlinksAndSubmodulesFailClosed() {
        RepositoryPortabilityService.Report report = service.validateEntries("tree", List.of(
                new RepositoryPortabilityService.TreeEntry("link", 0120000, "symlink", "l"),
                new RepositoryPortabilityService.TreeEntry("link/child.txt", 0100644, "blob", "b"),
                new RepositoryPortabilityService.TreeEntry("vendor/module", 0160000, "commit", "c"),
                entry("src\\generated.txt")));

        assertTrue(has(report, RepositoryPortabilityService.FindingCode.SYMLINK_TRAVERSAL));
        assertTrue(has(report, RepositoryPortabilityService.FindingCode.UNSUPPORTED_SUBMODULE));
        assertTrue(has(report, RepositoryPortabilityService.FindingCode.PATH_SEPARATOR_AMBIGUITY));
    }

    @Test
    void validationOrderIsIndependentOfHostAndInputOrder() {
        List<RepositoryPortabilityService.TreeEntry> entries = List.of(
                entry("src/Parser.java"), entry("src/parser.java"), entry("docs/readme"));
        RepositoryPortabilityService.Report first = service.validateEntries("tree", entries);
        RepositoryPortabilityService.Report second = service.validateEntries("tree", List.of(
                entries.get(2), entries.get(1), entries.get(0)));

        assertEqualsFindings(first, second);
    }

    @Test
    void gitPreflightValidatesTheCompleteHeadTree(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Files.createDirectories(root);
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "portable\n", StandardCharsets.UTF_8);
        git(root, "add", ".");
        git(root, "commit", "-m", "portable baseline");

        assertTrue(service.preflight(root).portable());
    }

    private static RepositoryPortabilityService.TreeEntry entry(String path) {
        return new RepositoryPortabilityService.TreeEntry(path, 0100644, "blob", "blob");
    }

    private static boolean has(RepositoryPortabilityService.Report report,
                               RepositoryPortabilityService.FindingCode code) {
        return report.findings().stream().anyMatch(finding -> finding.code() == code);
    }

    private static void assertEqualsFindings(RepositoryPortabilityService.Report first,
                                             RepositoryPortabilityService.Report second) {
        assertTrue(first.findings().equals(second.findings()));
        assertFalse(first.portable());
    }

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }
}
