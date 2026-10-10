package org.synesis.projectrecord.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.DecisionRecordWireTest;

/**
 * Pins the on-disk layout and head-pointer bytes written by {@link DecisionStore}.
 */
final class DecisionStoreWireTest {

  private static final UUID RECORD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String HEAD_REVISION_ONE = "5344483101" + "0000000000000001"
      + DecisionRecordWireTest.DECISION_DIGEST;

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static List<String> relativePaths(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(path -> !path.equals(root))
          .map(path -> root.relativize(path).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  @TempDir
  Path root;

  private Path storeWithVector() throws Exception {
    DecisionStore store = new DecisionStore(root, DecisionRecordWireTest.PROJECT);
    DecisionRecord record = DecisionRecord.decode(bytes(DecisionRecordWireTest.DECISION));
    assertEquals(DecisionStore.SaveResult.APPLIED, store.save(record, null));
    return root;
  }

  @Test
  void savingOneRecordWritesTheExactHeadFileAndLayout() throws Exception {
    Path root = storeWithVector();

    assertEquals(List.of("conflicts", "decisions", "decisions/" + RECORD,
        "decisions/" + RECORD + "/1.sdr", "heads", "heads/" + RECORD + ".head"),
        relativePaths(root));
    assertArrayEquals(bytes(HEAD_REVISION_ONE),
        Files.readAllBytes(root.resolve("heads").resolve(RECORD + ".head")));
    assertArrayEquals(bytes(DecisionRecordWireTest.DECISION),
        Files.readAllBytes(root.resolve("decisions").resolve(RECORD.toString()).resolve("1.sdr")));
  }

  @Test
  void reopeningRewritesNothingWhenTheHeadMatches() throws Exception {
    Path root = storeWithVector();
    new DecisionStore(root, DecisionRecordWireTest.PROJECT);

    assertArrayEquals(bytes(HEAD_REVISION_ONE),
        Files.readAllBytes(root.resolve("heads").resolve(RECORD + ".head")));
  }

  @Test
  void headFileWithTrailingBytesOrAnyTruncationFailsTheStoreOpenAsIoException() throws Exception {
    Path root = storeWithVector();
    Path head = root.resolve("heads").resolve(RECORD + ".head");
    byte[] valid = bytes(HEAD_REVISION_ONE);

    Files.write(head, Arrays.copyOf(valid, valid.length + 1));
    assertThrows(IOException.class, () -> new DecisionStore(root, DecisionRecordWireTest.PROJECT));
    for (int length = 0; length < valid.length; length++) {
      Files.write(head, Arrays.copyOf(valid, length));
      assertThrows(IOException.class,
          () -> new DecisionStore(root, DecisionRecordWireTest.PROJECT));
    }
  }
}
