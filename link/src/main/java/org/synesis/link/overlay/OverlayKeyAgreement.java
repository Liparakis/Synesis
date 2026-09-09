package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.synesis.link.identity.NodeIdentity;

/**
 * Bounded signed `SLK1` key agreement and pairwise E2E session support.
 *
 * <p>The records are designed to travel through authorized physical peers.
 * Only the origin and destination retain the ephemeral private keys and the derived session key.
 */
public final class OverlayKeyAgreement {

  /**
   * Maximum encoded key-agreement record size.
   */
  public static final int MAX_BYTES = 1_024;
  /**
   * Maximum E2E replay-window width.
   */
  public static final int REPLAY_WINDOW = 64;

  private static final int MAGIC = 0x534C4B31;
  private static final int VERSION = 1;
  private static final int INIT = 0;
  private static final int RESPONSE = 1;
  private static final byte[] E2E_INFO = "synesis-e2e-v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NONCE_INFO = "synesis-nonce-v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ORIGIN_TO_DESTINATION = "origin-to-destination".getBytes(
      StandardCharsets.US_ASCII);
  private static final byte[] DESTINATION_TO_ORIGIN = "destination-to-origin".getBytes(
      StandardCharsets.US_ASCII);

  private OverlayKeyAgreement() {
  }

  /**
   * Starts one signed E2E key agreement.
   *
   * @param projectId          project namespace
   * @param destinationNodeId  expected destination node ID
   * @param sessionId          fresh E2E session ID
   * @param membershipRevision accepted membership revision
   * @param origin             durable origin identity
   * @return initiator state containing the signed INIT record
   * @throws GeneralSecurityException if key generation or signing fails
   */
  public static Initiator start(UUID projectId, String destinationNodeId, UUID sessionId,
      long membershipRevision, NodeIdentity origin) throws GeneralSecurityException {
    Objects.requireNonNull(projectId, "project ID");
    OverlayCodecSupport.requireNodeId(destinationNodeId);
    Objects.requireNonNull(sessionId, "session ID");
    if (membershipRevision <= 0) {
      throw new IllegalArgumentException("membership revision must be positive");
    }
    Objects.requireNonNull(origin, "origin identity");
    KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
    KeyPair ephemeral = generator.generateKeyPair();
    byte[] nonce = randomBytes(OverlayCodecSupport.NONCE_BYTES);
    InitRecord unsignedRecord = InitRecord.unsigned(projectId, origin.nodeId(), destinationNodeId,
        sessionId,
        membershipRevision, nonce, OverlayCodecSupport.x25519PublicKeyBytes(ephemeral));
    return new Initiator(ephemeral.getPrivate(), InitRecord.signed(unsignedRecord, origin.sign(
        unsignedRecord.unsignedEncoded())));
  }

  /**
   * Creates and signs the responder record after validating the initiator.
   *
   * @param init        decoded initiator record
   * @param destination durable destination identity
   * @param membership  current verified membership snapshot
   * @return responder state containing the response and derived session
   * @throws GeneralSecurityException if validation, key generation, or signing fails
   */
  public static Responder respond(InitRecord init, NodeIdentity destination,
      OverlayMembershipSnapshot membership) throws GeneralSecurityException {
    Objects.requireNonNull(init, "init");
    Objects.requireNonNull(destination, "destination identity");
    init.verifyAgainst(membership);
    if (!destination.nodeId().equals(init.destinationNodeId())) {
      throw new GeneralSecurityException("destination identity mismatch");
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
    KeyPair ephemeral = generator.generateKeyPair();
    byte[] responseNonce = randomBytes(OverlayCodecSupport.NONCE_BYTES);
    byte[] transcriptHash = OverlayCodecSupport.sha256(init.unsignedEncoded());
    ResponseRecord unsigned = ResponseRecord.unsigned(init, responseNonce,
        OverlayCodecSupport.x25519PublicKeyBytes(ephemeral), transcriptHash);
    ResponseRecord response = ResponseRecord.signed(unsigned,
        destination.sign(unsigned.unsignedEncoded()));
    E2eSession session = deriveSession(init, response, ephemeral.getPrivate(),
        init.ephemeralPublicKey(), false);
    return new Responder(response, session);
  }

  /**
   * Finishes the initiator side after validating a responder record.
   *
   * @param initiator  local initiator state
   * @param response   decoded responder record
   * @param membership current verified membership snapshot
   * @return derived E2E session
   * @throws GeneralSecurityException if validation or key derivation fails
   */
  public static E2eSession finish(Initiator initiator, ResponseRecord response,
      OverlayMembershipSnapshot membership) throws GeneralSecurityException {
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(response, "response");
    InitRecord init = initiator.init();
    init.verifyAgainst(membership);
    response.verifyAgainst(init, membership);
    return deriveSession(init, response, initiator.ephemeralPrivateKey,
        response.ephemeralPublicKey(), true);
  }

  private static void validateMembership(UUID projectId, long revision, String origin,
      String destination,
      OverlayMembershipSnapshot membership) throws GeneralSecurityException {
    Objects.requireNonNull(membership, "membership");
    if (!membership.projectId().equals(projectId) || membership.revision() != revision
        || !membership.verify() || !membership.allows(origin) || !membership.allows(destination)
        || !membership.isUsableAt(Instant.now())) {
      throw new GeneralSecurityException("membership snapshot is not valid for key agreement");
    }
  }

  private static E2eSession deriveSession(InitRecord init, ResponseRecord response,
      PrivateKey privateKey,
      byte[] peerPublicKey, boolean initiatorSide) throws GeneralSecurityException {
    byte[] shared = OverlayCodecSupport.deriveSharedSecret(privateKey, peerPublicKey);
    byte[] salt = OverlayCodecSupport.sha256(OverlayCodecSupport.concat(
        OverlayCodecSupport.OVERLAY_DOMAIN,
        OverlayCodecSupport.uuidBytes(init.projectId()),
        OverlayCodecSupport.uuidBytes(init.sessionId())));
    byte[] info = OverlayCodecSupport.concat(E2E_INFO,
        OverlayCodecSupport.uuidBytes(init.projectId()),
        OverlayCodecSupport.nodeIdBytes(init.originNodeId()),
        OverlayCodecSupport.nodeIdBytes(init.destinationNodeId()),
        longBytes(init.membershipRevision()),
        init.initNonce(),
        response.responseNonce(),
        response.transcriptHash());
    byte[] originToDestinationKey = OverlayCodecSupport.hkdf(shared, salt,
        OverlayCodecSupport.concat(info, ORIGIN_TO_DESTINATION), 32);
    byte[] destinationToOriginKey = OverlayCodecSupport.hkdf(shared, salt,
        OverlayCodecSupport.concat(info, DESTINATION_TO_ORIGIN), 32);
    byte[] originToDestinationNonce = OverlayCodecSupport.hkdf(shared, salt,
        OverlayCodecSupport.concat(NONCE_INFO, info, ORIGIN_TO_DESTINATION), 4);
    byte[] destinationToOriginNonce = OverlayCodecSupport.hkdf(shared, salt,
        OverlayCodecSupport.concat(NONCE_INFO, info, DESTINATION_TO_ORIGIN), 4);
    byte[] sendKey = initiatorSide ? originToDestinationKey : destinationToOriginKey;
    byte[] sendNonce = initiatorSide ? originToDestinationNonce : destinationToOriginNonce;
    byte[] receiveKey = initiatorSide ? destinationToOriginKey : originToDestinationKey;
    byte[] receiveNonce = initiatorSide ? destinationToOriginNonce : originToDestinationNonce;
    String sendOrigin = initiatorSide ? init.originNodeId() : init.destinationNodeId();
    String sendDestination = initiatorSide ? init.destinationNodeId() : init.originNodeId();
    String receiveOrigin = initiatorSide ? init.destinationNodeId() : init.originNodeId();
    String receiveDestination = initiatorSide ? init.originNodeId() : init.destinationNodeId();
    return new E2eSession(init.projectId(), init.sessionId(), sendOrigin, sendDestination,
        receiveOrigin,
        receiveDestination, sendKey, sendNonce, receiveKey, receiveNonce);
  }

  private static byte[] longBytes(long value) {
    return new byte[]{(byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40),
        (byte) (value >>> 32), (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
        (byte) value};
  }

  private static byte[] randomBytes(int length) {
    byte[] value = new byte[length];
    new SecureRandom().nextBytes(value);
    return value;
  }

  private static byte[] encodeUnsigned(int magic, int kind, UUID projectId, String originNodeId,
      String destinationNodeId, UUID sessionId, long membershipRevision, byte[] nonce,
      byte[] ephemeral,
      byte[] transcriptHash, byte[] signature) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(magic);
        output.writeByte(VERSION);
        output.writeByte(kind);
        OverlayCodecSupport.writeUuid(output, projectId);
        output.write(OverlayCodecSupport.nodeIdBytes(originNodeId));
        output.write(OverlayCodecSupport.nodeIdBytes(destinationNodeId));
        OverlayCodecSupport.writeUuid(output, sessionId);
        output.writeLong(membershipRevision);
        output.write(nonce);
        output.write(ephemeral);
        if (transcriptHash != null) {
          output.write(transcriptHash);
        }
        if (signature != null) {
          output.writeShort(signature.length);
          output.write(signature);
        }
      }
      if (bytes.size() > MAX_BYTES) {
        throw new IllegalArgumentException("key-agreement record exceeds supported bound");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] fixed(byte[] value, int length, String field) {
    Objects.requireNonNull(value, field);
    if (value.length != length) {
      throw new IllegalArgumentException(field + " must contain " + length + " bytes");
    }
    return value.clone();
  }

  /**
   * Holds the initiator's ephemeral private key and signed INIT record.
   */
  public static final class Initiator {

    private final PrivateKey ephemeralPrivateKey;
    private final InitRecord init;

    private Initiator(PrivateKey ephemeralPrivateKey, InitRecord init) {
      this.ephemeralPrivateKey = ephemeralPrivateKey;
      this.init = init;
    }

    /**
     * Returns the signed INIT record for forwarding.
     *
     * @return INIT record
     */
    public InitRecord init() {
      return init;
    }
  }

  /**
   * Holds the responder's signed RESPONSE record and derived session.
   */
  public static final class Responder {

    private final ResponseRecord response;
    private final E2eSession session;

    private Responder(ResponseRecord response, E2eSession session) {
      this.response = response;
      this.session = session;
    }

    /**
     * Returns the signed RESPONSE record for forwarding.
     *
     * @return response record
     */
    public ResponseRecord response() {
      return response;
    }

    /**
     * Returns the responder's derived E2E session.
     *
     * @return E2E session
     */
    public E2eSession session() {
      return session;
    }
  }

  /**
   * Signed `SLK1` initiator record.
   */
  public static final class InitRecord {

    private final UUID projectId;
    private final String originNodeId;
    private final String destinationNodeId;
    private final UUID sessionId;
    private final long membershipRevision;
    private final byte[] initNonce;
    private final byte[] ephemeralPublicKey;
    private final byte[] signature;
    private final byte[] unsignedBytes;
    private final byte[] encoded;

    private InitRecord(UUID projectId, String originNodeId, String destinationNodeId,
        UUID sessionId,
        long membershipRevision, byte[] initNonce, byte[] ephemeralPublicKey, byte[] signature) {
      this.projectId = Objects.requireNonNull(projectId, "project ID");
      OverlayCodecSupport.requireNodeId(originNodeId);
      OverlayCodecSupport.requireNodeId(destinationNodeId);
      this.originNodeId = originNodeId;
      this.destinationNodeId = destinationNodeId;
      this.sessionId = Objects.requireNonNull(sessionId, "session ID");
      if (membershipRevision <= 0) {
        throw new IllegalArgumentException("membership revision must be positive");
      }
      this.membershipRevision = membershipRevision;
      this.initNonce = fixed(initNonce, OverlayCodecSupport.NONCE_BYTES, "init nonce");
      this.ephemeralPublicKey = fixed(ephemeralPublicKey,
          OverlayCodecSupport.X25519_PUBLIC_KEY_BYTES,
          "ephemeral public key");
      this.signature = fixed(signature, OverlayCodecSupport.SIGNATURE_BYTES, "signature");
      this.unsignedBytes = encodeUnsigned();
      this.encoded = encodeComplete();
    }

    private static InitRecord unsigned(UUID projectId, String originNodeId,
        String destinationNodeId,
        UUID sessionId, long membershipRevision, byte[] nonce, byte[] ephemeralPublicKey) {
      return new InitRecord(projectId, originNodeId, destinationNodeId, sessionId,
          membershipRevision, nonce,
          ephemeralPublicKey, new byte[OverlayCodecSupport.SIGNATURE_BYTES]);
    }

    private static InitRecord signed(InitRecord unsigned, byte[] signature) {
      return new InitRecord(unsigned.projectId, unsigned.originNodeId, unsigned.destinationNodeId,
          unsigned.sessionId, unsigned.membershipRevision, unsigned.initNonce,
          unsigned.ephemeralPublicKey, signature);
    }

    /**
     * Decodes a bounded INIT record.
     *
     * @param bytes encoded record
     * @return decoded record
     * @throws IOException if framing or bounds are invalid
     */
    public static InitRecord decode(byte[] bytes) throws IOException {
      Objects.requireNonNull(bytes, "bytes");
      if (bytes.length == 0 || bytes.length > MAX_BYTES) {
        throw new IOException("key-agreement record exceeds supported bound");
      }
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
        readHeader(input, INIT);
        UUID project = OverlayCodecSupport.readUuid(input);
        String origin = OverlayCodecSupport.nodeId(
            input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
        String destination = OverlayCodecSupport.nodeId(
            input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
        UUID session = OverlayCodecSupport.readUuid(input);
        long revision = input.readLong();
        byte[] nonce = input.readNBytes(OverlayCodecSupport.NONCE_BYTES);
        byte[] ephemeral = input.readNBytes(OverlayCodecSupport.X25519_PUBLIC_KEY_BYTES);
        byte[] signature = readSignature(input);
        OverlayCodecSupport.requireNoTrailing(input);
        InitRecord result = new InitRecord(project, origin, destination, session, revision, nonce,
            ephemeral,
            signature);
        if (!Arrays.equals(result.encoded, bytes)) {
          throw new IOException("non-canonical key-agreement INIT");
        }
        return result;
      } catch (java.io.EOFException | IllegalArgumentException failure) {
        throw new IOException("malformed key-agreement INIT", failure);
      }
    }

    private static void readHeader(DataInputStream input, int expectedKind) throws IOException {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
          || input.readUnsignedByte() != expectedKind) {
        throw new IOException("unsupported key-agreement record");
      }
    }

    private static byte[] readSignature(DataInputStream input) throws IOException {
      int length = input.readUnsignedShort();
      if (length != OverlayCodecSupport.SIGNATURE_BYTES || length > input.available()) {
        throw new IOException("invalid key-agreement signature");
      }
      return input.readNBytes(length);
    }

    /**
     * Verifies this INIT against the accepted project membership.
     *
     * @param membership current verified membership
     * @throws GeneralSecurityException if the signature or membership binding is invalid
     */
    public void verifyAgainst(OverlayMembershipSnapshot membership)
        throws GeneralSecurityException {
      OverlayKeyAgreement.validateMembership(projectId, membershipRevision, originNodeId,
          destinationNodeId,
          membership);
      OverlayMembershipSnapshot.Member member = membership.member(originNodeId).orElseThrow(
          () -> new GeneralSecurityException("origin is not a member"));
      if (!OverlayCodecSupport.verifyEd25519(member.publicKeyEncoded(), unsignedBytes, signature)) {
        throw new GeneralSecurityException("invalid key-agreement INIT signature");
      }
    }

    private byte[] encodeUnsigned() {
      return OverlayKeyAgreement.encodeUnsigned(MAGIC, INIT, projectId, originNodeId,
          destinationNodeId, sessionId,
          membershipRevision, initNonce, ephemeralPublicKey, null, null);
    }

    private byte[] encodeComplete() {
      return OverlayKeyAgreement.encodeUnsigned(MAGIC, INIT, projectId, originNodeId,
          destinationNodeId, sessionId,
          membershipRevision, initNonce, ephemeralPublicKey, null, signature);
    }

    /**
     * Returns the exact bytes covered by the durable Ed25519 signature.
     *
     * @return unsigned canonical bytes
     */
    public byte[] unsignedEncoded() {
      return unsignedBytes.clone();
    }

    /**
     * Returns the complete encoded INIT record.
     *
     * @return encoded bytes
     */
    public byte[] encoded() {
      return encoded.clone();
    }

    /**
     * Returns the project ID.
     *
     * @return project ID
     */
    public UUID projectId() {
      return projectId;
    }

    /**
     * Returns the origin node ID.
     *
     * @return origin node ID
     */
    public String originNodeId() {
      return originNodeId;
    }

    /**
     * Returns the destination node ID.
     *
     * @return destination node ID
     */
    public String destinationNodeId() {
      return destinationNodeId;
    }

    /**
     * Returns the E2E session ID.
     *
     * @return session ID
     */
    public UUID sessionId() {
      return sessionId;
    }

    /**
     * Returns the membership revision used by this agreement.
     *
     * @return membership revision
     */
    public long membershipRevision() {
      return membershipRevision;
    }

    /**
     * Returns the initiator nonce.
     *
     * @return nonce copy
     */
    public byte[] initNonce() {
      return initNonce.clone();
    }

    /**
     * Returns the raw X25519 ephemeral public key.
     *
     * @return public key copy
     */
    public byte[] ephemeralPublicKey() {
      return ephemeralPublicKey.clone();
    }
  }

  /**
   * Signed `SLK1` responder record.
   */
  public static final class ResponseRecord {

    private final UUID projectId;
    private final String originNodeId;
    private final String destinationNodeId;
    private final UUID sessionId;
    private final long membershipRevision;
    private final byte[] responseNonce;
    private final byte[] ephemeralPublicKey;
    private final byte[] transcriptHash;
    private final byte[] signature;
    private final byte[] unsignedBytes;
    private final byte[] encoded;

    private ResponseRecord(UUID projectId, String originNodeId, String destinationNodeId,
        UUID sessionId,
        long membershipRevision, byte[] responseNonce, byte[] ephemeralPublicKey,
        byte[] transcriptHash,
        byte[] signature) {
      this.projectId = Objects.requireNonNull(projectId, "project ID");
      OverlayCodecSupport.requireNodeId(originNodeId);
      OverlayCodecSupport.requireNodeId(destinationNodeId);
      this.originNodeId = originNodeId;
      this.destinationNodeId = destinationNodeId;
      this.sessionId = Objects.requireNonNull(sessionId, "session ID");
      if (membershipRevision <= 0) {
        throw new IllegalArgumentException("membership revision must be positive");
      }
      this.membershipRevision = membershipRevision;
      this.responseNonce = fixed(responseNonce, OverlayCodecSupport.NONCE_BYTES, "response nonce");
      this.ephemeralPublicKey = fixed(ephemeralPublicKey,
          OverlayCodecSupport.X25519_PUBLIC_KEY_BYTES,
          "ephemeral public key");
      this.transcriptHash = fixed(transcriptHash, 32, "transcript hash");
      this.signature = fixed(signature, OverlayCodecSupport.SIGNATURE_BYTES, "signature");
      this.unsignedBytes = encodeUnsigned();
      this.encoded = encodeComplete();
    }

    private static ResponseRecord unsigned(InitRecord init, byte[] responseNonce,
        byte[] ephemeralPublicKey,
        byte[] transcriptHash) {
      return new ResponseRecord(init.projectId, init.originNodeId, init.destinationNodeId,
          init.sessionId,
          init.membershipRevision, responseNonce, ephemeralPublicKey, transcriptHash,
          new byte[OverlayCodecSupport.SIGNATURE_BYTES]);
    }

    private static ResponseRecord signed(ResponseRecord unsigned, byte[] signature) {
      return new ResponseRecord(unsigned.projectId, unsigned.originNodeId,
          unsigned.destinationNodeId,
          unsigned.sessionId, unsigned.membershipRevision, unsigned.responseNonce,
          unsigned.ephemeralPublicKey, unsigned.transcriptHash, signature);
    }

    /**
     * Decodes a bounded RESPONSE record.
     *
     * @param bytes encoded record
     * @return decoded record
     * @throws IOException if framing or bounds are invalid
     */
    public static ResponseRecord decode(byte[] bytes) throws IOException {
      Objects.requireNonNull(bytes, "bytes");
      if (bytes.length == 0 || bytes.length > MAX_BYTES) {
        throw new IOException("key-agreement record exceeds supported bound");
      }
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
        InitRecord.readHeader(input, RESPONSE);
        UUID project = OverlayCodecSupport.readUuid(input);
        String origin = OverlayCodecSupport.nodeId(
            input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
        String destination = OverlayCodecSupport.nodeId(
            input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
        UUID session = OverlayCodecSupport.readUuid(input);
        long revision = input.readLong();
        byte[] nonce = input.readNBytes(OverlayCodecSupport.NONCE_BYTES);
        byte[] ephemeral = input.readNBytes(OverlayCodecSupport.X25519_PUBLIC_KEY_BYTES);
        byte[] transcript = input.readNBytes(32);
        byte[] signature = InitRecord.readSignature(input);
        OverlayCodecSupport.requireNoTrailing(input);
        ResponseRecord result = new ResponseRecord(project, origin, destination, session, revision,
            nonce,
            ephemeral, transcript, signature);
        if (!Arrays.equals(result.encoded, bytes)) {
          throw new IOException("non-canonical key-agreement RESPONSE");
        }
        return result;
      } catch (java.io.EOFException | IllegalArgumentException failure) {
        throw new IOException("malformed key-agreement RESPONSE", failure);
      }
    }

    private byte[] encodeUnsigned() {
      return OverlayKeyAgreement.encodeUnsigned(MAGIC, RESPONSE, projectId, originNodeId,
          destinationNodeId, sessionId,
          membershipRevision, responseNonce, ephemeralPublicKey, transcriptHash, null);
    }

    private byte[] encodeComplete() {
      return OverlayKeyAgreement.encodeUnsigned(MAGIC, RESPONSE, projectId, originNodeId,
          destinationNodeId, sessionId,
          membershipRevision, responseNonce, ephemeralPublicKey, transcriptHash, signature);
    }

    /**
     * Verifies this response against its exact INIT and membership snapshot.
     *
     * @param init       original INIT record
     * @param membership current verified membership
     * @throws GeneralSecurityException if the response is not authentic
     */
    public void verifyAgainst(InitRecord init, OverlayMembershipSnapshot membership)
        throws GeneralSecurityException {
      Objects.requireNonNull(init, "init");
      Objects.requireNonNull(membership, "membership");
      if (!projectId.equals(init.projectId) || !originNodeId.equals(init.originNodeId)
          || !destinationNodeId.equals(init.destinationNodeId) || !sessionId.equals(init.sessionId)
          || membershipRevision != init.membershipRevision
          || !Arrays.equals(transcriptHash, OverlayCodecSupport.sha256(init.unsignedEncoded()))) {
        throw new GeneralSecurityException("key-agreement response transcript mismatch");
      }
      OverlayKeyAgreement.validateMembership(projectId, membershipRevision, originNodeId,
          destinationNodeId,
          membership);
      OverlayMembershipSnapshot.Member member = membership.member(destinationNodeId).orElseThrow(
          () -> new GeneralSecurityException("destination is not a member"));
      if (!OverlayCodecSupport.verifyEd25519(member.publicKeyEncoded(), unsignedBytes, signature)) {
        throw new GeneralSecurityException("invalid key-agreement response signature");
      }
    }

    /**
     * Returns the exact bytes covered by the destination signature.
     *
     * @return unsigned canonical bytes
     */
    public byte[] unsignedEncoded() {
      return unsignedBytes.clone();
    }

    /**
     * Returns the complete encoded response.
     *
     * @return encoded bytes
     */
    public byte[] encoded() {
      return encoded.clone();
    }

    /**
     * Returns the project ID.
     *
     * @return project ID
     */
    public UUID projectId() {
      return projectId;
    }

    /**
     * Returns the origin node ID.
     *
     * @return origin node ID
     */
    public String originNodeId() {
      return originNodeId;
    }

    /**
     * Returns the destination node ID.
     *
     * @return destination node ID
     */
    public String destinationNodeId() {
      return destinationNodeId;
    }

    /**
     * Returns the session ID.
     *
     * @return session ID
     */
    public UUID sessionId() {
      return sessionId;
    }

    /**
     * Returns the membership revision.
     *
     * @return revision
     */
    public long membershipRevision() {
      return membershipRevision;
    }

    /**
     * Returns the responder nonce.
     *
     * @return nonce copy
     */
    public byte[] responseNonce() {
      return responseNonce.clone();
    }

    /**
     * Returns the raw X25519 ephemeral public key.
     *
     * @return public key copy
     */
    public byte[] ephemeralPublicKey() {
      return ephemeralPublicKey.clone();
    }

    /**
     * Returns the INIT transcript hash.
     *
     * @return transcript hash copy
     */
    public byte[] transcriptHash() {
      return transcriptHash.clone();
    }
  }

  /**
   * Pairwise authenticated E2E session derived from one `SLK1` exchange.
   */
  public static final class E2eSession implements AutoCloseable {

    private final UUID projectId;
    private final UUID sessionId;
    private final String sendOriginNodeId;
    private final String sendDestinationNodeId;
    private final String receiveOriginNodeId;
    private final String receiveDestinationNodeId;
    private final byte[] sendKey;
    private final byte[] sendNoncePrefix;
    private final byte[] receiveKey;
    private final byte[] receiveNoncePrefix;
    private final ReplayWindow replay = new ReplayWindow();
    private final AtomicLong nextSequence = new AtomicLong();
    private boolean closed;

    private E2eSession(UUID projectId, UUID sessionId, String sendOriginNodeId,
        String sendDestinationNodeId,
        String receiveOriginNodeId, String receiveDestinationNodeId, byte[] sendKey,
        byte[] sendNoncePrefix,
        byte[] receiveKey, byte[] receiveNoncePrefix) {
      this.projectId = projectId;
      this.sessionId = sessionId;
      this.sendOriginNodeId = sendOriginNodeId;
      this.sendDestinationNodeId = sendDestinationNodeId;
      this.receiveOriginNodeId = receiveOriginNodeId;
      this.receiveDestinationNodeId = receiveDestinationNodeId;
      this.sendKey = sendKey.clone();
      this.sendNoncePrefix = sendNoncePrefix.clone();
      this.receiveKey = receiveKey.clone();
      this.receiveNoncePrefix = receiveNoncePrefix.clone();
    }

    private static byte[] nonce(byte[] noncePrefix, long sequence) {
      byte[] nonce = new byte[OverlayCodecSupport.AEAD_NONCE_BYTES];
      System.arraycopy(noncePrefix, 0, nonce, 0, noncePrefix.length);
      for (int index = 0; index < Long.BYTES; index++) {
        nonce[noncePrefix.length + index] = (byte) (sequence >>> (56 - (index * 8)));
      }
      return nonce;
    }

    /**
     * Encrypts one bounded logical message using the next session sequence.
     *
     * @param messageId logical message ID
     * @param plaintext bounded plaintext
     * @return immutable E2E envelope
     * @throws GeneralSecurityException if encryption fails
     */
    public synchronized OverlayEnvelope encrypt(UUID messageId, byte[] plaintext)
        throws GeneralSecurityException {
      ensureOpen();
      long sequence = nextSequence.getAndIncrement();
      if (sequence < 0) {
        throw new GeneralSecurityException("E2E sequence exhausted");
      }
      return seal(messageId, sequence, plaintext);
    }

    /**
     * Encrypts one bounded logical message at an explicit sequence.
     *
     * @param messageId logical message ID
     * @param sequence  non-negative sequence number
     * @param plaintext bounded plaintext
     * @return immutable E2E envelope
     * @throws GeneralSecurityException if encryption fails
     */
    public synchronized OverlayEnvelope encrypt(UUID messageId, long sequence, byte[] plaintext)
        throws GeneralSecurityException {
      ensureOpen();
      Objects.requireNonNull(messageId, "message ID");
      Objects.requireNonNull(plaintext, "plaintext");
      if (sequence < 0 || plaintext.length > OverlayEnvelope.MAX_PLAINTEXT_BYTES) {
        throw new IllegalArgumentException("plaintext or sequence exceeds supported bound");
      }
      long next = nextSequence.get();
      if (next < 0 || sequence < next) {
        throw new GeneralSecurityException("E2E sequence was already allocated");
      }
      nextSequence.set(sequence == Long.MAX_VALUE ? Long.MIN_VALUE : sequence + 1);
      return seal(messageId, sequence, plaintext);
    }

    private OverlayEnvelope seal(UUID messageId, long sequence, byte[] plaintext)
        throws GeneralSecurityException {
      Objects.requireNonNull(messageId, "message ID");
      Objects.requireNonNull(plaintext, "plaintext");
      if (sequence < 0 || plaintext.length > OverlayEnvelope.MAX_PLAINTEXT_BYTES) {
        throw new IllegalArgumentException("plaintext or sequence exceeds supported bound");
      }
      int ciphertextLength = Math.addExact(plaintext.length, OverlayCodecSupport.AEAD_TAG_BYTES);
      byte[] aad = OverlayEnvelope.associatedData(projectId, messageId, sendOriginNodeId,
          sendDestinationNodeId, sessionId, sequence, ciphertextLength);
      return OverlayEnvelope.sealed(projectId, messageId, sendOriginNodeId, sendDestinationNodeId,
          sessionId,
          sequence, OverlayCodecSupport.aeadEncrypt(sendKey, nonce(sendNoncePrefix, sequence), aad,
              plaintext));
    }

    /**
     * Decrypts and replay-checks one envelope for this exact E2E session.
     *
     * @param envelope E2E envelope
     * @return plaintext
     * @throws GeneralSecurityException if the binding, replay window, or AEAD check fails
     */
    public synchronized byte[] decrypt(OverlayEnvelope envelope) throws GeneralSecurityException {
      ensureOpen();
      Objects.requireNonNull(envelope, "envelope");
      if (!projectId.equals(envelope.projectId()) || !receiveOriginNodeId.equals(
          envelope.originNodeId())
          || !receiveDestinationNodeId.equals(envelope.destinationNodeId())
          || !sessionId.equals(envelope.sessionId())) {
        throw new GeneralSecurityException("E2E envelope session binding mismatch");
      }
      if (!replay.isNew(envelope.sequence())) {
        throw new GeneralSecurityException("E2E replay rejected");
      }
      byte[] plaintext = OverlayCodecSupport.aeadDecrypt(receiveKey,
          nonce(receiveNoncePrefix, envelope.sequence()),
          envelope.associatedData(), envelope.ciphertext());
      replay.accept(envelope.sequence());
      return plaintext;
    }

    private void ensureOpen() throws GeneralSecurityException {
      if (closed) {
        throw new GeneralSecurityException("E2E session is closed");
      }
    }

    /**
     * Erases the derived session key and prevents further use.
     */
    @Override
    public synchronized void close() {
      Arrays.fill(sendKey, (byte) 0);
      Arrays.fill(sendNoncePrefix, (byte) 0);
      Arrays.fill(receiveKey, (byte) 0);
      Arrays.fill(receiveNoncePrefix, (byte) 0);
      closed = true;
    }

    /**
     * Returns the E2E session ID.
     *
     * @return session ID
     */
    public UUID sessionId() {
      return sessionId;
    }
  }

  private static final class ReplayWindow {

    private long highest = -1;
    private long bits;

    private boolean isNew(long sequence) {
      if (sequence < 0) {
        return false;
      }
      if (highest < 0 || sequence > highest) {
        return true;
      }
      long distance = highest - sequence;
      return distance < REPLAY_WINDOW && (bits & (1L << distance)) == 0;
    }

    private void accept(long sequence) {
      if (highest < 0 || sequence > highest) {
        long distance = highest < 0 ? REPLAY_WINDOW : sequence - highest;
        bits = distance >= REPLAY_WINDOW ? 1 : (bits << distance) | 1;
        highest = sequence;
      } else {
        bits |= 1L << (highest - sequence);
      }
    }
  }
}
