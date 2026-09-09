package org.synesis.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayForwardingFrame;

/**
 * Bounded relay control and frame codec.
 */
final class RelayWireCodec {

  static final int MAX_PAYLOAD_BYTES = OverlayForwardingFrame.MAX_BYTES + 64;
  private static final int MAGIC = 0x534C5231;
  private static final int VERSION = 1;
  private static final int HELLO = 0;
  private static final int HELLO_ACK = 1;
  private static final int FRAME = 2;
  private static final int INBOUND_FRAME = 3;
  private static final int FRAME_ACK = 4;
  private static final int NODE_ID_BYTES = 32;
  private static final int MAX_PUBLIC_KEY_BYTES = 256;
  private static final int SIGNATURE_BYTES = 64;
  private static final int NONCE_BYTES = 32;

  private RelayWireCodec() {
  }

  static byte[] encodeHello(NodeIdentity identity) throws GeneralSecurityException {
    Objects.requireNonNull(identity, "identity");
    byte[] nonce = new byte[NONCE_BYTES];
    new SecureRandom().nextBytes(nonce);
    byte[] unsigned = helloUnsigned(identity.nodeId(), identity.publicKeyEncoded(), nonce);
    return appendSignature(unsigned, identity.sign(unsigned));
  }

  static Hello decodeHello(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      readHeader(input, HELLO);
      String nodeId = nodeId(input.readNBytes(NODE_ID_BYTES));
      byte[] publicKey = readBytes(input, MAX_PUBLIC_KEY_BYTES);
      byte[] nonce = readFixed(input, NONCE_BYTES);
      byte[] signature = readSignature(input);
      requireTrailing(input);
      if (!NodeIdentity.deriveNodeId(publicKey).equals(nodeId)) {
        throw new IOException("relay hello node ID does not match key");
      }
      return new Hello(nodeId, publicKey, nonce, signature);
    } catch (EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed relay hello", failure);
    }
  }

  static void verifyHello(Hello hello) throws GeneralSecurityException {
    PublicKey publicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(hello.publicKey));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(hello.unsigned());
    if (!verifier.verify(hello.signature)) {
      throw new GeneralSecurityException("invalid relay hello signature");
    }
  }

  static byte[] encodeHelloAck(NodeIdentity relayIdentity, Hello hello)
      throws GeneralSecurityException {
    Objects.requireNonNull(relayIdentity, "relay identity");
    Objects.requireNonNull(hello, "hello");
    byte[] unsigned = ackUnsigned(relayIdentity, hello);
    return appendSignature(unsigned, relayIdentity.sign(unsigned));
  }

  static HelloAck decodeHelloAck(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      readHeader(input, HELLO_ACK);
      String relayNodeId = nodeId(input.readNBytes(NODE_ID_BYTES));
      byte[] publicKey = readBytes(input, MAX_PUBLIC_KEY_BYTES);
      String clientNodeId = nodeId(input.readNBytes(NODE_ID_BYTES));
      byte[] nonce = readFixed(input, NONCE_BYTES);
      byte[] signature = readSignature(input);
      requireTrailing(input);
      if (!NodeIdentity.deriveNodeId(publicKey).equals(relayNodeId)) {
        throw new IOException("relay acknowledgement node ID does not match key");
      }
      return new HelloAck(relayNodeId, publicKey, clientNodeId, nonce, signature);
    } catch (EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed relay hello acknowledgement", failure);
    }
  }

  static void verifyHelloAck(HelloAck ack, Hello hello, String expectedNodeId,
      byte[] expectedPublicKey)
      throws GeneralSecurityException {
    if (expectedNodeId != null && !expectedNodeId.equals(ack.relayNodeId)) {
      throw new GeneralSecurityException("unexpected relay identity");
    }
    if (expectedPublicKey != null && !Arrays.equals(expectedPublicKey, ack.publicKey)) {
      throw new GeneralSecurityException("unexpected relay public key");
    }
    if (!hello.nodeId.equals(ack.clientNodeId) || !Arrays.equals(hello.nonce, ack.nonce)) {
      throw new GeneralSecurityException("relay acknowledgement is not bound to the client hello");
    }
    PublicKey publicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(ack.publicKey));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(ackUnsigned(ack));
    if (!verifier.verify(ack.signature)) {
      throw new GeneralSecurityException("invalid relay acknowledgement signature");
    }
  }

  static byte[] encodeFrame(OverlayForwardingFrame frame) {
    return encodeFrameLike(FRAME, frame.encoded());
  }

  static byte[] encodeInboundFrame(OverlayForwardingFrame frame) {
    return encodeFrameLike(INBOUND_FRAME, frame.encoded());
  }

  static byte[] decodeFrame(byte[] payload, int expectedType) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      readHeader(input, expectedType);
      int length = input.readUnsignedShort();
      if (length <= 0 || length > OverlayForwardingFrame.MAX_BYTES || length > input.available()) {
        throw new IOException("relay frame length exceeds bound");
      }
      byte[] frame = input.readNBytes(length);
      requireTrailing(input);
      return frame;
    } catch (EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed relay frame", failure);
    }
  }

  static int typeOf(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported relay record");
      }
      return input.readUnsignedByte();
    } catch (EOFException failure) {
      throw new IOException("truncated relay record header", failure);
    }
  }

  static byte[] encodeFrameAck(UUID messageId, int status) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeHeader(output, FRAME_ACK);
        output.writeLong(messageId.getMostSignificantBits());
        output.writeLong(messageId.getLeastSignificantBits());
        output.writeByte(status);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static FrameAck decodeFrameAck(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      readHeader(input, FRAME_ACK);
      UUID messageId = new UUID(input.readLong(), input.readLong());
      int status = input.readUnsignedByte();
      requireTrailing(input);
      return new FrameAck(messageId, status);
    } catch (EOFException | IllegalArgumentException failure) {
      throw new IOException("malformed relay frame acknowledgement", failure);
    }
  }

  static byte[] helloUnsigned(String nodeId, byte[] publicKey, byte[] nonce) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeHeader(output, HELLO);
        output.write(nodeIdBytes(nodeId));
        writeBytes(output, publicKey, MAX_PUBLIC_KEY_BYTES);
        output.write(nonce);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] ackUnsigned(NodeIdentity relayIdentity, Hello hello) {
    return ackUnsigned(relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), hello.nodeId,
        hello.nonce);
  }

  private static byte[] ackUnsigned(HelloAck ack) {
    return ackUnsigned(ack.relayNodeId, ack.publicKey, ack.clientNodeId, ack.nonce);
  }

  private static byte[] ackUnsigned(String relayNodeId, byte[] publicKey, String clientNodeId,
      byte[] nonce) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeHeader(output, HELLO_ACK);
        output.write(nodeIdBytes(relayNodeId));
        writeBytes(output, publicKey, MAX_PUBLIC_KEY_BYTES);
        output.write(nodeIdBytes(clientNodeId));
        output.write(nonce);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] encodeFrameLike(int type, byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length == 0 || frame.length > OverlayForwardingFrame.MAX_BYTES) {
      throw new IllegalArgumentException("relay frame exceeds bound");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(frame.length + 8);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeHeader(output, type);
        output.writeShort(frame.length);
        output.write(frame);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] appendSignature(byte[] unsigned, byte[] signature) {
    if (signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("Ed25519 signature length is invalid");
    }
    byte[] result = Arrays.copyOf(unsigned, unsigned.length + Short.BYTES + signature.length);
    result[unsigned.length] = (byte) (signature.length >>> Byte.SIZE);
    result[unsigned.length + 1] = (byte) signature.length;
    System.arraycopy(signature, 0, result, unsigned.length + Short.BYTES, signature.length);
    return result;
  }

  private static byte[] readSignature(DataInputStream input) throws IOException {
    int length = input.readUnsignedShort();
    if (length != SIGNATURE_BYTES || length > input.available()) {
      throw new IOException("invalid relay signature");
    }
    return input.readNBytes(length);
  }

  private static byte[] readBytes(DataInputStream input, int max) throws IOException {
    int length = input.readUnsignedShort();
    if (length == 0 || length > max || length > input.available()) {
      throw new IOException("bounded relay field is invalid");
    }
    return input.readNBytes(length);
  }

  private static byte[] readFixed(DataInputStream input, int length) throws IOException {
    byte[] value = input.readNBytes(length);
    if (value.length != length) {
      throw new IOException("truncated relay field");
    }
    return value;
  }

  private static void writeBytes(DataOutputStream output, byte[] value, int max)
      throws IOException {
    Objects.requireNonNull(value, "bounded relay field");
    if (value.length == 0 || value.length > max || value.length > 0xFFFF) {
      throw new IllegalArgumentException("bounded relay field exceeds limit");
    }
    output.writeShort(value.length);
    output.write(value);
  }

  private static void writeHeader(DataOutputStream output, int type) throws IOException {
    output.writeInt(MAGIC);
    output.writeByte(VERSION);
    output.writeByte(type);
  }

  private static void readHeader(DataInputStream input, int expectedType) throws IOException {
    if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
        || input.readUnsignedByte() != expectedType) {
      throw new IOException("unsupported relay record");
    }
  }

  private static void requireTrailing(DataInputStream input) throws IOException {
    if (input.available() != 0) {
      throw new IOException("trailing relay record data");
    }
  }

  private static byte[] nodeIdBytes(String nodeId) {
    if (nodeId == null || !nodeId.matches("sl1-[0-9a-f]{64}")) {
      throw new IllegalArgumentException("invalid relay node ID");
    }
    return HexFormat.of().parseHex(nodeId.substring(4));
  }

  private static String nodeId(byte[] bytes) {
    if (bytes.length != NODE_ID_BYTES) {
      throw new IllegalArgumentException("invalid relay node ID bytes");
    }
    return "sl1-" + HexFormat.of().formatHex(bytes);
  }

  static final class Hello {

    private final String nodeId;
    private final byte[] publicKey;
    private final byte[] nonce;
    private final byte[] signature;

    private Hello(String nodeId, byte[] publicKey, byte[] nonce, byte[] signature) {
      this.nodeId = nodeId;
      this.publicKey = publicKey.clone();
      this.nonce = nonce.clone();
      this.signature = signature.clone();
    }

    private byte[] unsigned() {
      return helloUnsigned(nodeId, publicKey, nonce);
    }

    String nodeId() {
      return nodeId;
    }
  }

  static final class HelloAck {

    private final String relayNodeId;
    private final byte[] publicKey;
    private final String clientNodeId;
    private final byte[] nonce;
    private final byte[] signature;

    private HelloAck(String relayNodeId, byte[] publicKey, String clientNodeId, byte[] nonce,
        byte[] signature) {
      this.relayNodeId = relayNodeId;
      this.publicKey = publicKey.clone();
      this.clientNodeId = clientNodeId;
      this.nonce = nonce.clone();
      this.signature = signature.clone();
    }
  }

  static final class FrameAck {

    final UUID messageId;
    final int status;

    private FrameAck(UUID messageId, int status) {
      this.messageId = messageId;
      this.status = status;
    }
  }
}
