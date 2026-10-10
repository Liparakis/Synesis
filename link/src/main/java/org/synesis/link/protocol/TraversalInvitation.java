package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import org.synesis.link.candidate.CandidateDescriptor;

/**
 * Human-mediated first traversal link containing the existing invitation and a host-signed
 * traversal offer.
 *
 * <p>The wrapper adds no trust of its own. The embedded {@link SessionInvitation}
 * and {@link TraversalOffer} remain independently canonical and signed, while the offer digest
 * binds them to the exact invitation bytes.
 *
 * @since 1.0
 */
public final class TraversalInvitation {

  /**
   * Maximum encoded SLO1 link payload.
   */
  static final int MAX_BYTES = SessionInvitation.MAX_BYTES + TraversalOffer.MAX_BYTES + 64;
  /**
   * Maximum SLO1 URI length accepted by the parser.
   */
  static final int MAX_LINK_CHARS = 65_536;

  private static final int MAGIC = 0x534C4F31;
  /** Legacy SLO1 wrapper format. */
  static final int FORMAT_VERSION_V1 = 1;
  /** SLO1 wrapper format carrying a v2 signed offer. */
  public static final int FORMAT_VERSION_V2 = 2;

  private final int formatVersion;
  private final SessionInvitation invitation;
  private final TraversalOffer offer;

  private TraversalInvitation(int formatVersion, SessionInvitation invitation, TraversalOffer offer) {
    if (formatVersion != FORMAT_VERSION_V1 && formatVersion != FORMAT_VERSION_V2) {
      throw new IllegalArgumentException("unsupported SLO1 format");
    }
    this.invitation = Objects.requireNonNull(invitation, "invitation");
    this.offer = Objects.requireNonNull(offer, "offer");
    if ((formatVersion == FORMAT_VERSION_V1
        && offer.formatVersion() != TraversalOffer.FORMAT_VERSION_V1)
        || (formatVersion == FORMAT_VERSION_V2
        && offer.formatVersion() != TraversalOffer.FORMAT_VERSION_V2)) {
      throw new IllegalArgumentException("SLO1 wrapper and offer format mismatch");
    }
    this.formatVersion = formatVersion;
    if (!invitation.sessionId().equals(offer.sessionId())) {
      throw new IllegalArgumentException("SLO1 session mismatch");
    }
    try {
      CandidateDescriptor descriptor = CandidateDescriptor.decode(invitation.descriptorEncoded());
      if (!descriptor.nodeId().equals(offer.initiatorNodeId())
          || !Arrays.equals(descriptor.publicKeyEncoded(), offer.initiatorPublicKeyEncoded())
          || !Arrays.equals(invitation.digest(), offer.invitationDigest())
          || offer.issuedAt().isBefore(invitation.issuedAt())
          || offer.expiresAt().isAfter(invitation.expiresAt())) {
        throw new IllegalArgumentException("SLO1 is not bound to its invitation");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("SLO1 contains an invalid invitation descriptor",
          exception);
    }
  }

  /**
   * Creates a canonical human-mediated SLO1 link payload.
   *
   * @param invitation existing signed host invitation
   * @param offer      host-signed traversal offer bound to that invitation
   * @return immutable link payload
   */
  public static TraversalInvitation create(SessionInvitation invitation, TraversalOffer offer) {
    return bounded(FORMAT_VERSION_V1, invitation, offer);
  }

  /**
   * Creates an explicit v2 SLO1 payload with a signed return target.
   *
   * @param invitation existing signed v1 host invitation
   * @param offer      signed v2 host offer bound to the invitation
   * @return immutable v2 SLO1 payload
   */
  public static TraversalInvitation createV2(SessionInvitation invitation, TraversalOffer offer) {
    return bounded(FORMAT_VERSION_V2, invitation, offer);
  }

  private static TraversalInvitation bounded(int formatVersion, SessionInvitation invitation,
      TraversalOffer offer) {
    TraversalInvitation value = new TraversalInvitation(formatVersion, invitation, offer);
    if (value.encoded().length > MAX_BYTES) {
      throw new IllegalArgumentException("SLO1 exceeds supported bound");
    }
    return value;
  }

  /**
   * Decodes a bounded SLO1 payload without trusting its signatures.
   *
   * @param encoded canonical SLO1 bytes
   * @return decoded payload
   * @throws IOException if framing or canonical binding is invalid
   */
  public static TraversalInvitation decode(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "SLO1 payload");
    if (encoded.length == 0 || encoded.length > MAX_BYTES) {
      throw new IOException("SLO1 exceeds supported bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (input.readInt() != MAGIC) {
        throw new IOException("unsupported SLO1 format");
      }
      int format = input.readUnsignedByte();
      if (format != FORMAT_VERSION_V1 && format != FORMAT_VERSION_V2) {
        throw new IOException("unsupported SLO1 format");
      }
      byte[] invitationBytes = readBytes(input, SessionInvitation.MAX_BYTES);
      byte[] offerBytes = readBytes(input, TraversalOffer.MAX_BYTES);
      if (input.available() != 0) {
        throw new IOException("trailing SLO1 bytes");
      }
      TraversalInvitation value = new TraversalInvitation(format,
          SessionInvitation.decode(invitationBytes), TraversalOffer.decode(offerBytes));
      CodecSupport.requireCanonical(encoded, value.encoded(), "non-canonical SLO1 payload");
      return value;
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed SLO1 payload", exception);
    }
  }

  /**
   * Parses a human-copyable {@code synesis://join/SLO1-...} link.
   *
   * @param link exact SLO1 link
   * @return decoded link payload
   * @throws IOException if the URI, encoding, or payload is invalid
   */
  public static TraversalInvitation fromShareLink(String link) throws IOException {
    Objects.requireNonNull(link, "SLO1 link");
    return ShareLinks.parse(link, "join", "SLO1-", MAX_LINK_CHARS, "SLO1",
        TraversalInvitation::decode);
  }

  private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
    output.writeInt(value.length);
    output.write(value);
  }

  private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
    int length = input.readInt();
    if (length <= 0 || length > maximum || length > input.available()) {
      throw new IOException("invalid bounded SLO1 field");
    }
    return input.readNBytes(length);
  }

  /**
   * Verifies both embedded signatures, their validity, and the expected responder binding.
   *
   * @param now                     current verification time
   * @param allowedClockSkew        accepted issue-time skew
   * @param expectedResponderNodeId local responder ID, or the expected peer ID
   * @return whether the link is current and authentic
   * @throws GeneralSecurityException if signature verification fails
   * @throws IOException              if an embedded record is malformed
   */
  public boolean verifyAt(Instant now, Duration allowedClockSkew,
      String expectedResponderNodeId) throws GeneralSecurityException, IOException {
    return invitation.verifyAt(now, allowedClockSkew)
        && offer.verifyAt(now, allowedClockSkew, expectedResponderNodeId);
  }

  /**
   * Returns the embedded signed invitation.
   *
   * @return the embedded signed invitation
   */
  public SessionInvitation invitation() {
    return invitation;
  }

  /**
   * Returns the explicit outer SLO1 format.
   *
   * @return SLO1 format
   */
  public int formatVersion() {
    return formatVersion;
  }

  /**
   * Returns the embedded signed traversal offer.
   *
   * @return the embedded signed traversal offer
   */
  public TraversalOffer offer() {
    return offer;
  }

  /**
   * Returns the signed v2 return target, or empty for v1.
   *
   * @return optional return target
   */
  public Optional<ReturnTarget> returnTarget() {
    return offer.returnTarget();
  }

  /**
   * Returns the canonical SLO1 payload bytes.
   *
   * @return canonical SLO1 payload bytes
   */
  public byte[] encoded() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(formatVersion);
        writeBytes(output, invitation.encoded());
        writeBytes(output, offer.encoded());
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Returns the canonical copyable SLO1 URI.
   *
   * @return copyable SLO1 URI
   */
  public String shareLink() {
    return "synesis://join/SLO1-" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(encoded());
  }
}
