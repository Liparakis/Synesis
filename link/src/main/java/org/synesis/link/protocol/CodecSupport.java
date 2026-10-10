package org.synesis.link.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Package-private bounded field codecs shared by the protocol records.
 *
 * <p>Each record keeps its own bounds and failure messages and passes them in, so the wire bytes
 * and the rejections of every record stay exactly its own.
 */
final class CodecSupport {

  private CodecSupport() {
  }

  static String boundedText(String value, int maximum, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " exceeds supported bound");
    }
    return value;
  }

  static byte[] boundedBytes(byte[] value, int maximum, String label) {
    Objects.requireNonNull(value, label);
    if (value.length == 0 || value.length > maximum) {
      throw new IllegalArgumentException(label + " exceeds supported bound");
    }
    return value.clone();
  }

  static byte[] exactBytes(byte[] value, int expected, String label) {
    byte[] copy = boundedBytes(value, expected, label);
    if (copy.length != expected) {
      throw new IllegalArgumentException(label + " has an invalid size");
    }
    return copy;
  }

  static void writeText(DataOutputStream output, String value) throws IOException {
    writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes a two-byte length prefix and the bytes; the caller has already bounded the length. */
  static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
    output.writeShort(value.length);
    output.write(value);
  }

  /** Reads a non-empty two-byte-length field no longer than {@code maximum}. */
  static byte[] readBytes(DataInputStream input, int maximum, String message) throws IOException {
    int length = input.readUnsignedShort();
    if (length == 0 || length > maximum || length > input.available()) {
      throw new IOException(message);
    }
    return input.readNBytes(length);
  }

  static String readText(DataInputStream input, int maximum, String message) throws IOException {
    return new String(readBytes(input, maximum, message), StandardCharsets.UTF_8);
  }

  static byte[] readExactBytes(DataInputStream input, int expected, String message,
      String sizeMessage) throws IOException {
    byte[] value = readBytes(input, expected, message);
    if (value.length != expected) {
      throw new IOException(sizeMessage);
    }
    return value;
  }

  /** Returns the unsigned bytes followed by the two-byte-length-prefixed signature. */
  static byte[] signed(byte[] unsigned, byte[] signature) {
    return ByteBuffer.allocate(unsigned.length + 2 + signature.length)
        .put(unsigned)
        .putShort((short) signature.length)
        .put(signature)
        .array();
  }

  /** Rejects a decoded record whose canonical re-encoding differs from the input. */
  static void requireCanonical(byte[] encoded, byte[] canonical, String message)
      throws IOException {
    if (!Arrays.equals(encoded, canonical)) {
      throw new IOException(message);
    }
  }

  static byte[] sha256(byte[]... values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (byte[] value : values) {
        digest.update(value);
      }
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by the Java platform", impossible);
    }
  }
}
