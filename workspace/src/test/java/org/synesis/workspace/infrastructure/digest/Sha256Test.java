package org.synesis.workspace.infrastructure.digest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the lower-case hexadecimal SHA-256 helper against known vectors.
 */
class Sha256Test {

  private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  /**
   * Verifies the digest of raw bytes.
   */
  @Test
  void hashesBytes() {
    assertEquals(ABC, Sha256.hex("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  /**
   * Verifies that text is hashed as UTF-8, not a platform charset.
   */
  @Test
  void hashesTextAsUtf8() {
    assertEquals("850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e", Sha256.hex("café"));
  }

  /**
   * Verifies that file content is hashed.
   *
   * @param directory temporary directory
   * @throws IOException if the file cannot be written
   */
  @Test
  void hashesFileContent(@TempDir Path directory) throws IOException {
    Path file = Files.writeString(directory.resolve("abc.txt"), "abc");
    assertEquals(ABC, Sha256.hex(file));
  }
}
