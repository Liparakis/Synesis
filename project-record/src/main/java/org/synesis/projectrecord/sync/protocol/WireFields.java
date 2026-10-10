package org.synesis.projectrecord.sync.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Field encodings shared by the SRP1 and PRP1 codecs. Each codec supplies its own bounds and
 * exception messages, so the accepted inputs and their diagnostics stay per protocol.
 */
final class WireFields {

  private static final int MAX_LENGTH_PREFIXED = 65_535;

  private WireFields() {
  }

  static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  /** Reads a non-empty unsigned-short length-prefixed block that must fit in the input. */
  static byte[] readBytes(DataInputStream input, String invalidMessage) throws IOException {
    int length = input.readUnsignedShort();
    if (length == 0 || length > input.available()) {
      throw new IOException(invalidMessage);
    }
    return input.readNBytes(length);
  }

  static void writeBytes(DataOutputStream output, byte[] value, String invalidMessage)
      throws IOException {
    if (value == null || value.length == 0 || value.length > MAX_LENGTH_PREFIXED) {
      throw new IllegalArgumentException(invalidMessage);
    }
    output.writeShort(value.length);
    output.write(value);
  }

  /** Reads strict UTF-8 text of at most {@code maxBytes} encoded bytes. */
  static String readText(DataInputStream input, int maxBytes, String invalidBytesMessage)
      throws IOException {
    byte[] bytes = readBytes(input, invalidBytesMessage);
    if (bytes.length > maxBytes) {
      throw new IOException("text exceeds bound");
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IOException("invalid UTF-8", exception);
    }
  }

  /**
   * Writes non-empty text of at most {@code maxBytes} encoded bytes. Empty text is rejected by
   * the length-prefixed block rule with {@code invalidBytesMessage}.
   */
  static void writeText(DataOutputStream output, String value, int maxBytes,
      String invalidBytesMessage) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException("text exceeds bound");
    }
    writeBytes(output, bytes, invalidBytesMessage);
  }
}
