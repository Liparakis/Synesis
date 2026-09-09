package org.synesis.link.candidate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal bounded RFC 8489 STUN Binding codec for server-reflexive discovery.
 *
 * <p>This codec handles Binding requests and success responses only. It does
 * not implement credentials, TURN allocation, relay behavior, or arbitrary STUN extensions. The
 * surrounding provider supplies transport deadlines and cancellation.
 */
public final class StunBindingMessage {

  /**
   * STUN magic cookie.
   */
  public static final int MAGIC_COOKIE = 0x2112A442;
  /**
   * STUN transaction identifier size.
   */
  public static final int TRANSACTION_ID_BYTES = 12;
  /**
   * Maximum response bytes inspected by this bounded codec.
   */
  public static final int MAX_RESPONSE_BYTES = 2_048;

  private static final int HEADER_BYTES = 20;
  private static final int BINDING_REQUEST = 0x0001;
  private static final int BINDING_SUCCESS = 0x0101;
  private static final int XOR_MAPPED_ADDRESS = 0x0020;
  private static final int MAPPED_ADDRESS = 0x0001;

  private StunBindingMessage() {
  }

  /**
   * Creates a canonical unauthenticated STUN Binding request.
   *
   * @param transactionId fresh 12-byte transaction ID
   * @return 20-byte Binding request
   */
  public static byte[] bindingRequest(byte[] transactionId) {
    requireTransactionId(transactionId);
    return ByteBuffer.allocate(HEADER_BYTES)
        .putShort((short) BINDING_REQUEST)
        .putShort((short) 0)
        .putInt(MAGIC_COOKIE)
        .put(transactionId)
        .array();
  }

  /**
   * Extracts an XOR-MAPPED-ADDRESS or legacy MAPPED-ADDRESS from a success response.
   *
   * @param response      complete bounded STUN response
   * @param transactionId request transaction ID
   * @return mapped public endpoint
   * @throws IOException if the response is malformed, mismatched, or has no address
   */
  public static InetSocketAddress xorMappedAddress(byte[] response, byte[] transactionId)
      throws IOException {
    Objects.requireNonNull(response, "response");
    requireTransactionId(transactionId);
    if (response.length < HEADER_BYTES || response.length > MAX_RESPONSE_BYTES) {
      throw new IOException("STUN response exceeds supported bound");
    }
    ByteBuffer input = ByteBuffer.wrap(response);
    int messageType = input.getShort() & 0xffff;
    int messageLength = input.getShort() & 0xffff;
    if (messageType != BINDING_SUCCESS || (messageType & 0xC000) != 0) {
      throw new IOException("not a STUN Binding success response");
    }
    if (messageLength > response.length - HEADER_BYTES || (messageLength & 3) != 0
        || input.getInt() != MAGIC_COOKIE) {
      throw new IOException("invalid STUN response header");
    }
    byte[] actualTransactionId = new byte[TRANSACTION_ID_BYTES];
    input.get(actualTransactionId);
    if (!Arrays.equals(actualTransactionId, transactionId)) {
      throw new IOException("STUN transaction mismatch");
    }
    int end = HEADER_BYTES + messageLength;
    InetSocketAddress mapped = null;
    while (input.position() < end) {
      if (end - input.position() < 4) {
        throw new IOException("truncated STUN attribute");
      }
      int type = input.getShort() & 0xffff;
      int length = input.getShort() & 0xffff;
      if (length > end - input.position()) {
        throw new IOException("STUN attribute exceeds message");
      }
      byte[] value = new byte[length];
      input.get(value);
      int padded = (length + 3) & ~3;
      if (padded - length > end - input.position()) {
        throw new IOException("truncated STUN attribute padding");
      }
      input.position(input.position() + padded - length);
      if (type == XOR_MAPPED_ADDRESS || (type == MAPPED_ADDRESS && mapped == null)) {
        InetSocketAddress candidate = decodeAddress(value, type == XOR_MAPPED_ADDRESS,
            transactionId);
        if (type == XOR_MAPPED_ADDRESS) {
          mapped = candidate;
        } else if (mapped == null) {
          mapped = candidate;
        }
      }
    }
    if (input.position() != end || mapped == null) {
      throw new IOException("STUN response has no mapped address");
    }
    return mapped;
  }

  private static InetSocketAddress decodeAddress(byte[] value, boolean xor, byte[] transactionId)
      throws IOException {
    if (value.length < 4 || value[0] != 0) {
      throw new IOException("invalid STUN address attribute");
    }
    int family = value[1] & 0xff;
    int addressLength = family == 0x01 ? 4 : family == 0x02 ? 16 : 0;
    if (addressLength == 0 || value.length != addressLength + 4) {
      throw new IOException("invalid STUN address family");
    }
    int port = ((value[2] & 0xff) << 8) | (value[3] & 0xff);
    byte[] address = Arrays.copyOfRange(value, 4, value.length);
    if (xor) {
      port ^= MAGIC_COOKIE >>> 16;
      byte[] mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(transactionId).array();
      for (int index = 0; index < address.length; index++) {
        address[index] ^= mask[index];
      }
    }
    try {
      return new InetSocketAddress(InetAddress.getByAddress(address), port);
    } catch (java.net.UnknownHostException exception) {
      throw new IOException("invalid STUN address", exception);
    }
  }

  private static void requireTransactionId(byte[] transactionId) {
    Objects.requireNonNull(transactionId, "transaction ID");
    if (transactionId.length != TRANSACTION_ID_BYTES) {
      throw new IllegalArgumentException("transaction ID must be 12 bytes");
    }
  }
}
