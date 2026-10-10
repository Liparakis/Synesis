package org.synesis.link.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the persisted identity files and the node ID derivation for one fixed Ed25519 key pair.
 * The encoding of an Ed25519 key is deterministic, so the same key material must always produce
 * the same {@code identity.bin} and {@code identity.pub}.
 */
final class IdentityWireCompatibilityTest {

  private static final String PUBLIC_KEY =
      "302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a";
  private static final String PRIVATE_KEY =
      "302e020100300506032b657004220420655d7c842f19a486f5fa729fb8bc31f56b7d0bc0b2c2fc46c6c7e21feeffcd36";
  private static final String NODE_ID =
      "sl1-4202b14fde83332a3f7223e907fb0d4f368847fc4b0e746ea314739ce94efe80";

  private static final String IDENTITY_BIN =
      "534c4944000000010000002c302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f"
      + "27ba53612c86d0d150e04a00000030302e020100300506032b657004220420655d7c842f19a486f5fa729fb8bc"
      + "31f56b7d0bc0b2c2fc46c6c7e21feeffcd36";

  private static byte[] unhex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static NodeIdentity identity() throws Exception {
    return NodeIdentity.fromEncoded(unhex(PUBLIC_KEY), unhex(PRIVATE_KEY));
  }

  @Test
  void nodeIdIsSl1PrefixAndLowercaseSha256OfTheEncodedPublicKey() throws Exception {
    assertEquals(NODE_ID, NodeIdentity.deriveNodeId(unhex(PUBLIC_KEY)));
    assertEquals(NODE_ID, identity().nodeId());
  }

  @Test
  void identityBinIsMagicVersionAndLengthPrefixedPublicAndPrivateKey(@TempDir Path directory)
      throws Exception {
    Path file = directory.resolve("identity.bin");
    new FileIdentityStore(file).save(identity());

    String written = HexFormat.of().formatHex(Files.readAllBytes(file));
    assertEquals(IDENTITY_BIN, written);
    assertEquals("534c4944" + "00000001" + "0000002c" + PUBLIC_KEY + "00000030" + PRIVATE_KEY,
        written);
    assertEquals(NODE_ID, new FileIdentityStore(file).load().nodeId());
  }

  @Test
  void identityBinRejectsTrailingAndTruncatedInput(@TempDir Path directory) throws Exception {
    byte[] bytes = unhex(IDENTITY_BIN);
    Path trailing = directory.resolve("trailing.bin");
    Files.write(trailing, Arrays.copyOf(bytes, bytes.length + 1));
    Path truncated = directory.resolve("truncated.bin");
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 1));

    assertThrows(IOException.class, () -> new FileIdentityStore(trailing).load());
    assertThrows(IOException.class, () -> new FileIdentityStore(truncated).load());
  }

  @Test
  void identityPubIsThreeAsciiLinesWrittenBesideTheStoredKey(@TempDir Path directory)
      throws Exception {
    new FileIdentityStore(directory.resolve("identity.bin")).save(identity());

    IdentityBootstrap.Result result = new IdentityBootstrap(directory).loadOrCreate();

    assertFalse(result.created());
    assertEquals(NODE_ID, result.identity().nodeId());
    String expected = "version=1\nnodeId=" + NODE_ID + "\npublicKey=" + PUBLIC_KEY + "\n";
    assertEquals(expected,
        Files.readString(directory.resolve("identity.pub"), StandardCharsets.US_ASCII));
    assertTrue(new IdentityBootstrap(directory).inspect().identityValid());
  }
}
