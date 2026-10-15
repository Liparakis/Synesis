package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.candidate.Candidate;

/**
 * The joiner's reply to a compact invitation: where the host should send packets so the joiner's
 * router lets the host's answers through.
 *
 * <p>Two routers that each drop what they did not ask for let traffic through only after each side
 * has sent toward the other. The joiner dials the invitation's endpoints, which opens its router to
 * the host; this answer tells the host where to send so its router opens to the joiner. The user
 * carries it back by hand, so no service learns either address.
 *
 * <p>The answer is unsigned routing input. It names the invitation's session so a host acts only on
 * an answer to its own invitation, and authorizes nothing: the session that follows is still
 * authenticated by the invitation's capability and both durable identities. A forged answer can at
 * most make the host send a few small packets to the addresses it names.
 *
 * @since 1.0
 */
public final class CompactAnswer {

  /** Maximum answer URI length accepted by the parser. */
  public static final int MAX_LINK_CHARS = 512;

  private static final String PREFIX = "synesis://answer/SLA3-";
  private static final int MAX_BYTES = Short.BYTES + 2 * Long.BYTES + Byte.BYTES
      + CompactEndpoints.MAX * CompactEndpoints.MAX_ENCODED_BYTES_EACH;

  private final UUID sessionId;
  private final List<Candidate> endpoints;

  private CompactAnswer(UUID sessionId, List<Candidate> endpoints) {
    this.sessionId = Objects.requireNonNull(sessionId, "session ID");
    this.endpoints = CompactEndpoints.validate(endpoints);
  }

  /**
   * Creates the answer to one invitation.
   *
   * @param invitation the invitation being answered
   * @param candidates the joiner's local candidates, from which the endpoints are selected
   * @return the answer
   * @throws IllegalArgumentException if no candidate is usable
   */
  public static CompactAnswer create(CompactBootstrapInvitation invitation,
      List<Candidate> candidates) {
    return new CompactAnswer(invitation.sessionId(), CompactEndpoints.select(candidates));
  }

  /**
   * Parses an answer URI.
   *
   * @param link answer URI
   * @return the decoded answer
   * @throws IOException if the URI or payload is malformed or oversized
   */
  public static CompactAnswer fromShareLink(String link) throws IOException {
    Objects.requireNonNull(link, "answer link");
    return ShareLinks.parse(link, "answer", "SLA3-", MAX_LINK_CHARS, "answer",
        "unsupported answer link format", CompactAnswer::decode);
  }

  /**
   * Checks whether a URI selects this answer revision.
   *
   * @param link candidate URI
   * @return {@code true} for the strict SLA3 answer prefix
   */
  public static boolean isShareLink(String link) {
    return link != null && link.startsWith(PREFIX);
  }

  static CompactAnswer decode(byte[] encoded) throws IOException {
    Objects.requireNonNull(encoded, "answer");
    if (encoded.length > MAX_BYTES) {
      throw new IOException("invalid answer length");
    }
    IncompatibleVersionException.requireCurrent(encoded, "answer");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      input.readUnsignedShort();
      UUID sessionId = new UUID(input.readLong(), input.readLong());
      List<Candidate> endpoints = CompactEndpoints.read(input);
      if (input.available() != 0) {
        throw new IOException("trailing answer bytes");
      }
      CompactAnswer value = new CompactAnswer(sessionId, endpoints);
      CodecSupport.requireCanonical(encoded, value.encoded(), "non-canonical answer");
      return value;
    } catch (EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed answer", exception);
    }
  }

  /**
   * Returns the canonical answer URI.
   *
   * @return copyable answer link
   */
  public String shareLink() {
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded());
  }

  /**
   * Returns the canonical answer bytes.
   *
   * @return a fresh encoded payload
   */
  public byte[] encoded() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeShort(LinkCompatibility.CURRENT);
        output.writeLong(sessionId.getMostSignificantBits());
        output.writeLong(sessionId.getLeastSignificantBits());
        CompactEndpoints.write(output, endpoints);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  /**
   * Returns the session of the invitation this answers.
   *
   * @return the invitation's session ID
   */
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * Returns the joiner's endpoints in its order of preference.
   *
   * @return immutable list of one to eight endpoints
   */
  public List<Candidate> endpoints() {
    return endpoints;
  }
}
