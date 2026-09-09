package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/**
 * Signed, bounded `SLT1` advertisement of one node's current direct peers.
 *
 * <p>An advertisement is a signed observation, not a membership grant and
 * not a promise that every listed socket remains usable.
 */
public final class OverlayTopologyAdvertisement {

  /**
   * Maximum encoded topology advertisement size.
   */
  public static final int MAX_BYTES = 1_024;
  /**
   * Maximum advertised direct neighbors.
   */
  public static final int MAX_ADJACENCIES = OverlayTopologyPolicy.MAX_DEGREE;

  private static final int MAGIC = 0x534C5431;
  private static final int VERSION = 1;
  private static final int SIGNATURE_BYTES = OverlayCodecSupport.SIGNATURE_BYTES;
  private final UUID projectId;
  private final String originNodeId;
  private final long membershipRevision;
  private final long sequence;
  private final Instant expiresAt;
  private final List<String> adjacentNodeIds;
  private final byte[] unsignedBytes;
  private final byte[] signature;
  private final byte[] encoded;

  private OverlayTopologyAdvertisement(UUID projectId, String originNodeId, long membershipRevision,
      long sequence,
      Instant expiresAt, List<String> adjacentNodeIds, byte[] unsignedBytes, byte[] signature) {
    this.projectId = Objects.requireNonNull(projectId, "project ID");
    OverlayCodecSupport.requireNodeId(originNodeId);
    this.originNodeId = originNodeId;
    if (membershipRevision <= 0 || sequence <= 0) {
      throw new IllegalArgumentException("topology revisions must be positive");
    }
    this.membershipRevision = membershipRevision;
    this.sequence = sequence;
    OverlayCodecSupport.requireMillis(expiresAt, "topology expiry");
    this.expiresAt = expiresAt;
    this.adjacentNodeIds = normalizeNeighbors(originNodeId, adjacentNodeIds);
    this.unsignedBytes = Objects.requireNonNull(unsignedBytes, "unsigned bytes").clone();
    this.signature = Objects.requireNonNull(signature, "signature").clone();
    if (this.signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("topology signature must be Ed25519-sized");
    }
    this.encoded = encodeComplete();
    if (encoded.length > MAX_BYTES) {
      throw new IllegalArgumentException("topology advertisement exceeds supported bound");
    }
  }

  /**
   * Creates and signs one topology advertisement.
   *
   * @param projectId          project namespace
   * @param membershipRevision accepted membership revision
   * @param sequence           monotonic origin sequence
   * @param expiresAt          bounded expiry time
   * @param adjacentNodeIds    currently observed direct peers
   * @param origin             signing identity
   * @return signed advertisement
   * @throws GeneralSecurityException if signing fails
   */
  public static OverlayTopologyAdvertisement create(UUID projectId, long membershipRevision,
      long sequence,
      Instant expiresAt, List<String> adjacentNodeIds, NodeIdentity origin)
      throws GeneralSecurityException {
    Objects.requireNonNull(origin, "origin identity");
    List<String> normalized = normalizeNeighbors(origin.nodeId(), adjacentNodeIds);
    byte[] unsigned = encodeUnsigned(projectId, origin.nodeId(), membershipRevision, sequence,
        expiresAt,
        normalized);
    return new OverlayTopologyAdvertisement(projectId, origin.nodeId(), membershipRevision,
        sequence, expiresAt,
        normalized, unsigned, origin.sign(unsigned));
  }

  /**
   * Decodes one canonical bounded topology advertisement.
   *
   * @param bytes encoded advertisement
   * @return decoded advertisement
   * @throws IOException if framing or bounds are invalid
   */
  public static OverlayTopologyAdvertisement decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IOException("topology advertisement exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported topology advertisement");
      }
      UUID project = OverlayCodecSupport.readUuid(input);
      String origin = OverlayCodecSupport.nodeId(
          input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
      long membershipRevision = input.readLong();
      long sequence = input.readLong();
      Instant expires = Instant.ofEpochMilli(input.readLong());
      int count = input.readUnsignedByte();
      if (count > MAX_ADJACENCIES) {
        throw new IOException("topology adjacency count exceeds bound");
      }
      List<String> neighbors = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        neighbors.add(
            OverlayCodecSupport.nodeId(input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES)));
      }
      int signatureLength = input.readUnsignedShort();
      if (signatureLength != SIGNATURE_BYTES || signatureLength > input.available()) {
        throw new IOException("invalid topology signature");
      }
      byte[] signature = input.readNBytes(signatureLength);
      OverlayCodecSupport.requireNoTrailing(input);
      List<String> normalized = normalizeNeighbors(origin, neighbors);
      byte[] unsigned = encodeUnsigned(project, origin, membershipRevision, sequence, expires,
          normalized);
      OverlayTopologyAdvertisement result = new OverlayTopologyAdvertisement(project, origin,
          membershipRevision, sequence, expires, normalized, unsigned, signature);
      if (!Arrays.equals(result.encoded, bytes)) {
        throw new IOException("non-canonical topology advertisement");
      }
      return result;
    } catch (java.io.EOFException | IllegalArgumentException | DateTimeException failure) {
      throw new IOException("malformed topology advertisement", failure);
    }
  }

  private static List<String> normalizeNeighbors(String originNodeId, List<String> values) {
    Objects.requireNonNull(values, "adjacent node IDs");
    if (values.size() > MAX_ADJACENCIES) {
      throw new IllegalArgumentException("topology adjacency count exceeds bound");
    }
    List<String> sorted = values.stream().map(value -> {
      OverlayCodecSupport.requireNodeId(value);
      return value;
    }).sorted(Comparator.naturalOrder()).toList();
    for (String neighbor : sorted) {
      if (originNodeId.equals(neighbor)) {
        throw new IllegalArgumentException("topology cannot advertise a self-edge");
      }
    }
    for (int index = 1; index < sorted.size(); index++) {
      if (sorted.get(index - 1).equals(sorted.get(index))) {
        throw new IllegalArgumentException("topology contains duplicate neighbor");
      }
    }
    return List.copyOf(sorted);
  }

  private static byte[] encodeUnsigned(UUID projectId, String originNodeId, long membershipRevision,
      long sequence, Instant expiresAt, List<String> adjacentNodeIds) {
    Objects.requireNonNull(projectId, "project ID");
    OverlayCodecSupport.requireNodeId(originNodeId);
    if (membershipRevision <= 0 || sequence <= 0) {
      throw new IllegalArgumentException("topology revisions must be positive");
    }
    OverlayCodecSupport.requireMillis(expiresAt, "topology expiry");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        OverlayCodecSupport.writeUuid(output, projectId);
        output.write(OverlayCodecSupport.nodeIdBytes(originNodeId));
        output.writeLong(membershipRevision);
        output.writeLong(sequence);
        output.writeLong(expiresAt.toEpochMilli());
        output.writeByte(adjacentNodeIds.size());
        for (String adjacentNodeId : adjacentNodeIds) {
          output.write(OverlayCodecSupport.nodeIdBytes(adjacentNodeId));
        }
      }
      if (bytes.size() > MAX_BYTES) {
        throw new IllegalArgumentException("topology advertisement exceeds supported bound");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Verifies the origin signature and membership/time binding.
   *
   * @param membership current verified project membership
   * @param now        current time
   * @throws GeneralSecurityException if this advertisement is not accepted
   */
  public void verifyAgainst(OverlayMembershipSnapshot membership, Instant now)
      throws GeneralSecurityException {
    Objects.requireNonNull(membership, "membership");
    Objects.requireNonNull(now, "now");
    if (!membership.projectId().equals(projectId) || membership.revision() != membershipRevision
        || !membership.isUsableAt(now) || !now.isBefore(expiresAt) || !membership.allows(
        originNodeId)) {
      throw new GeneralSecurityException("topology advertisement is not current for membership");
    }
    for (String neighbor : adjacentNodeIds) {
      if (!membership.allows(neighbor)) {
        throw new GeneralSecurityException("topology advertisement names an unauthorized neighbor");
      }
    }
    OverlayMembershipSnapshot.Member member = membership.member(originNodeId)
        .orElseThrow(() -> new GeneralSecurityException("topology origin is not a member"));
    if (!OverlayCodecSupport.verifyEd25519(member.publicKeyEncoded(), unsignedBytes, signature)) {
      throw new GeneralSecurityException("invalid topology signature");
    }
  }

  /**
   * Returns whether this advertisement is expired at the supplied time.
   *
   * @param now current time
   * @return true when expired
   */
  public boolean isExpiredAt(Instant now) {
    return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
  }

  /**
   * Returns the project namespace.
   *
   * @return project ID
   */
  public UUID projectId() {
    return projectId;
  }

  /**
   * Returns the signed origin node ID.
   *
   * @return origin node ID
   */
  public String originNodeId() {
    return originNodeId;
  }

  /**
   * Returns the accepted membership revision.
   *
   * @return membership revision
   */
  public long membershipRevision() {
    return membershipRevision;
  }

  /**
   * Returns the origin sequence.
   *
   * @return sequence
   */
  public long sequence() {
    return sequence;
  }

  /**
   * Returns the expiry time.
   *
   * @return expiry time
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns immutable sorted adjacent node IDs.
   *
   * @return adjacent node IDs
   */
  public List<String> adjacentNodeIds() {
    return adjacentNodeIds;
  }

  /**
   * Returns the exact bytes covered by the origin signature.
   *
   * @return unsigned canonical bytes
   */
  public byte[] unsignedEncoded() {
    return unsignedBytes.clone();
  }

  /**
   * Returns the complete canonical advertisement.
   *
   * @return encoded advertisement
   */
  public byte[] encoded() {
    return encoded.clone();
  }

  private byte[] encodeComplete() {
    byte[] result = Arrays.copyOf(unsignedBytes,
        unsignedBytes.length + Short.BYTES + signature.length);
    int signatureOffset = unsignedBytes.length;
    result[signatureOffset] = (byte) (signature.length >>> Byte.SIZE);
    result[signatureOffset + 1] = (byte) signature.length;
    System.arraycopy(signature, 0, result, signatureOffset + Short.BYTES, signature.length);
    return result;
  }
}
