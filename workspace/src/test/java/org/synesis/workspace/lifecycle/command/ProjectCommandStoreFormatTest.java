package org.synesis.workspace.lifecycle.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Pins the JSON a {@link ProjectCommandStore} writes for each command phase and the directory
 * layout it creates.
 *
 * <p>Record file names are SHA-256 of the key-sorted JSON of the anchor and request ID, so they are
 * the same in every JVM run.
 */
class ProjectCommandStoreFormatTest {

  @TempDir Path temp;

  @Test
  void startingRecordFileAndLayout() throws IOException {
    Path root = temp.resolve("commands");
    ProjectCommandRecord record =
        new ProjectCommandRecord(
            "anchor-1",
            "scope-1",
            "n:1",
            "request-digest",
            "semantic-digest",
            ProjectCommandPhase.STARTING,
            null,
            false,
            null,
            false,
            false,
            null,
            1L,
            1_700_000_000_000L,
            1_700_000_000_000L,
            Map.of("status", "starting"),
            Map.of());

    new ProjectCommandStore(root).save(record);

    Path records = root.resolve("scopes/scope-1/records");
    Path file =
        records.resolve("01ae1f44fe93719f8af8eb3974f3453e4e741aa84d0dbb60c4e23b620c61a9a4.json");
    assertTrue(Files.isRegularFile(file));
    assertEquals(
        List.of(
            "locks",
            "namespace.json",
            "namespace.lock",
            "process-scopes",
            "scopes",
            "scopes/scope-1",
            "scopes/scope-1/records",
            "scopes/scope-1/records/<KEY>.json"),
        layout(root));
    assertEquals(
        """
        {"anchorId":"anchor-1","canonicalizationVersion":2,"commandProcessIdentity":{},"createdAtEpochMillis":1700000000000,"integrityAlgorithm":"SHA-256","integrityDigest":"c46d9ae54178d23ae2773732edd3cf2a9b035e7505b29b50301246d4e0e6cdf4","minimumReaderSchemaVersion":1,"objectRevision":1,"outcomeKnown":false,"phase":"STARTING","requestDigest":"request-digest","requestId":"n:1","response":{"status":"starting"},"schemaVersion":2,"scopeLocator":"scope-1","semanticDigest":"semantic-digest","stderrComplete":false,"stdoutComplete":false,"updatedAtEpochMillis":1700000000000,"writerVersion":"0.1.0-SNAPSHOT"}""",
        Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  void runningAndTerminalRecordFiles() throws IOException {
    Path root = temp.resolve("commands");
    PhysicalWorktreeIdentity worktree =
        PhysicalWorktreeIdentity.capture(Files.createDirectories(temp.resolve("worktree")));
    ProjectCommandProcessAnchor anchor;
    try (ProjectCommandNamespace namespace = ProjectCommandNamespace.open(root)) {
      namespace.publishScope(worktree);
      anchor =
          ProjectCommandProcessAnchor.capture(
              worktree.locator(),
              new SessionProcessIdentity(1L, "java", "java", 2L, "nonce-1"),
              1_700_000_000_000L);
      namespace.writeAnchor(anchor);
    }
    ProjectCommandStore store = new ProjectCommandStore(root);

    store.save(
        phase(anchor, worktree, ProjectCommandPhase.STARTING, null, 1L, false, null, Map.of(), Map.of()));
    String starting = recordText(root, worktree, anchor, mask(anchor, worktree));
    store.save(
        phase(anchor, worktree, ProjectCommandPhase.RUNNING, null, 2L, false, null, Map.of("status", "running"), Map.of("pid", 4242L)));
    String running = recordText(root, worktree, anchor, mask(anchor, worktree));
    store.save(
        phase(
            anchor,
            worktree,
            ProjectCommandPhase.TERMINAL,
            ProjectCommandTerminalResolution.OBSERVED_COMMAND_TERMINAL,
            3L,
            true,
            0,
            Map.of("status", "completed"),
            Map.of()));
    String terminal = recordText(root, worktree, anchor, mask(anchor, worktree));

    assertEquals(
        """
        {"anchorId":"<ANCHOR>","canonicalizationVersion":2,"commandProcessIdentity":{},"createdAtEpochMillis":1700000000000,"integrityAlgorithm":"SHA-256","integrityDigest":"<DIGEST>","minimumReaderSchemaVersion":1,"objectRevision":1,"outcomeKnown":false,"phase":"STARTING","requestDigest":"request-digest","requestId":"n:1","response":{},"schemaVersion":2,"scopeLocator":"<SCOPE>","semanticDigest":"semantic-digest","stderrComplete":false,"stdoutComplete":false,"updatedAtEpochMillis":1700000000001,"writerVersion":"0.1.0-SNAPSHOT"}""",
        starting);
    assertEquals(
        """
        {"anchorId":"<ANCHOR>","canonicalizationVersion":2,"commandProcessIdentity":{"pid":4242},"createdAtEpochMillis":1700000000000,"integrityAlgorithm":"SHA-256","integrityDigest":"<DIGEST>","minimumReaderSchemaVersion":1,"objectRevision":2,"outcomeKnown":false,"phase":"RUNNING","requestDigest":"request-digest","requestId":"n:1","response":{"status":"running"},"schemaVersion":2,"scopeLocator":"<SCOPE>","semanticDigest":"semantic-digest","stderrComplete":false,"stdoutComplete":false,"updatedAtEpochMillis":1700000000002,"writerVersion":"0.1.0-SNAPSHOT"}""",
        running);
    assertEquals(
        """
        {"anchorId":"<ANCHOR>","canonicalizationVersion":2,"commandProcessIdentity":{},"createdAtEpochMillis":1700000000000,"exitCode":0,"integrityAlgorithm":"SHA-256","integrityDigest":"<DIGEST>","minimumReaderSchemaVersion":1,"objectRevision":3,"outcomeKnown":true,"phase":"TERMINAL","requestDigest":"request-digest","requestId":"n:1","response":{"status":"completed"},"schemaVersion":2,"scopeLocator":"<SCOPE>","semanticDigest":"semantic-digest","stderrComplete":true,"stdoutComplete":true,"terminalResolution":"OBSERVED_COMMAND_TERMINAL","updatedAtEpochMillis":1700000000003,"writerVersion":"0.1.0-SNAPSHOT"}""",
        terminal);
  }

  private static ProjectCommandRecord phase(
      ProjectCommandProcessAnchor anchor,
      PhysicalWorktreeIdentity worktree,
      ProjectCommandPhase phase,
      ProjectCommandTerminalResolution resolution,
      long revision,
      boolean finished,
      Integer exitCode,
      Map<String, Object> response,
      Map<String, Object> processIdentity) {
    return new ProjectCommandRecord(
        anchor.anchorId(),
        worktree.locator(),
        "n:1",
        "request-digest",
        "semantic-digest",
        phase,
        resolution,
        finished,
        exitCode,
        finished,
        finished,
        null,
        revision,
        1_700_000_000_000L,
        1_700_000_000_000L + revision,
        response,
        processIdentity);
  }

  private static List<String> mask(ProjectCommandProcessAnchor anchor, PhysicalWorktreeIdentity worktree) {
    return List.of(anchor.anchorId(), worktree.locator());
  }

  private static String recordText(
      Path root, PhysicalWorktreeIdentity worktree, ProjectCommandProcessAnchor anchor, List<String> masked)
      throws IOException {
    Path file;
    try (Stream<Path> files = Files.list(root.resolve("scopes").resolve(worktree.locator()).resolve("records"))) {
      file = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
    }
    String text = Files.readString(file, StandardCharsets.UTF_8);
    Map<?, ?> parsed = (Map<?, ?>) ProviderJson.parse(text);
    CommandDurableFormat.verify(ProviderJson.stringKeyedObject(parsed));
    String digest = (String) parsed.get("integrityDigest");
    assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}\\.json"));
    return text.replace(masked.get(0), "<ANCHOR>").replace(masked.get(1), "<SCOPE>").replace(digest, "<DIGEST>");
  }

  private static List<String> layout(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(path -> !path.equals(root))
          .map(path -> root.relativize(path).toString().replace('\\', '/'))
          .map(path -> path.replaceAll("[0-9a-f]{64}\\.json$", "<KEY>.json"))
          .sorted()
          .toList();
    }
  }
}
