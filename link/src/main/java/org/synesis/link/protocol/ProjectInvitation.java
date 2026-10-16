package org.synesis.link.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.synesis.link.identity.NodeIdentity;

/**
 * One link that invites a person into a project: the {@link CompactBootstrapInvitation} that opens the connection,
 * the project's identity, and the URL of the inviter's code remote, all covered by the inviter's signature
 * (ADR-0098, "Joining, revised").
 *
 * <p>The connection invitation keeps its own signature and stays byte for byte what the joiner hands to Link. The
 * project link adds a second signature, made with the same node key over the whole payload before it, so the
 * project identity and the URL cannot be changed (or moved onto another connection invitation) without the
 * inviter's key. Signing only the extra fields would let a signed connection invitation be re-wrapped with
 * another project's identity.
 *
 * <p>The payload starts with the {@link LinkCompatibility#CURRENT compatibility number}, like every link, then the
 * project's UUID and creation time, the code remote URL (empty when the inviter has none), the connection
 * invitation, and the signature. It is canonical and bounded. The URL never carries credentials:
 * {@link #publicUrl} is what the inviter writes and what a parser accepts.
 *
 * @since 1.0
 */
public final class ProjectInvitation {

  /** Maximum project link length accepted by the parser. */
  public static final int MAX_LINK_CHARS = 1_200;
  /** Maximum code remote URL length in bytes. */
  public static final int MAX_URL_BYTES = 256;

  private static final String PREFIX = "synesis://project/SLP1-";
  private static final int SIGNATURE_BYTES = 64;
  private static final int MAX_BYTES = Short.BYTES + 16 + Long.BYTES + Integer.BYTES + Short.BYTES
      + MAX_URL_BYTES + Short.BYTES + CompactBootstrapInvitation.MAX_BYTES + SIGNATURE_BYTES;
  private static final byte[] SIGNATURE_DOMAIN = "synesis-link/SLP1/project"
      .getBytes(StandardCharsets.US_ASCII);
  private static final Pattern PASSWORD_IN_AUTHORITY = Pattern.compile("^[a-z][a-z0-9+.-]*://[^/@]*:[^/@]*@",
      Pattern.CASE_INSENSITIVE);
  /**
   * The only forms a friend's computer is asked to fetch from: {@code https://host/path}, {@code ssh://[user@]host/path}
   * and {@code user@host:path}. A local path, {@code file://} or a UNC path ({@code \\host\share}) would make a later
   * fetch or push open another computer's share or write another repository on this one.
   */
  private static final Pattern FETCHABLE = Pattern.compile(
      "^(?:https://[^/\\\\@\\s]+(?:/[^\\\\\\s]*)?"
          + "|ssh://(?:[^/\\\\@\\s]+@)?[^/\\\\@\\s]+(?:/[^\\\\\\s]*)?"
          + "|[A-Za-z0-9._][A-Za-z0-9._-]*@[A-Za-z0-9.-]+:(?!//)[^\\\\\\s]*)$");
  private static final Pattern WEB_USERINFO = Pattern.compile("^(https?://)[^/@]*@",
      Pattern.CASE_INSENSITIVE);

  private final CompactBootstrapInvitation invitation;
  private final UUID projectId;
  private final Instant createdAt;
  private final String codeRemoteUrl;
  private final byte[] unsigned;
  private final byte[] signature;

  private ProjectInvitation(CompactBootstrapInvitation invitation, UUID projectId,
      Instant createdAt, String codeRemoteUrl, byte[] signature) {
    this.invitation = Objects.requireNonNull(invitation, "connection invitation");
    this.projectId = Objects.requireNonNull(projectId, "project ID");
    this.createdAt = Objects.requireNonNull(createdAt, "created at");
    this.codeRemoteUrl = checkedUrl(codeRemoteUrl);
    this.unsigned = encodeUnsigned();
    this.signature = Objects.requireNonNull(signature, "signature").clone();
    if (this.signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("project invitation signature must contain 64 bytes");
    }
  }

  /**
   * Creates and signs a project link around a connection invitation the same node made.
   *
   * @param host          the inviter, whose key signed {@code invitation}
   * @param invitation    the connection invitation the joiner dials
   * @param projectId     the project's identity
   * @param createdAt     the project's creation time
   * @param codeRemoteUrl URL of the inviter's code remote without credentials ({@link #publicUrl}), or
   *                      {@code null} when the project has none
   * @return the signed project link
   * @throws GeneralSecurityException if signing fails
   * @throws IllegalArgumentException if {@code host} did not make {@code invitation} or a field is out of bounds
   */
  public static ProjectInvitation create(NodeIdentity host, CompactBootstrapInvitation invitation,
      UUID projectId, Instant createdAt, String codeRemoteUrl) throws GeneralSecurityException {
    Objects.requireNonNull(host, "host identity");
    if (!host.nodeId().equals(Objects.requireNonNull(invitation, "connection invitation").hostNodeId())) {
      throw new IllegalArgumentException("project invitation must be signed by the connection invitation's host");
    }
    ProjectInvitation unsigned = new ProjectInvitation(invitation, projectId, createdAt, codeRemoteUrl,
        new byte[SIGNATURE_BYTES]);
    return new ProjectInvitation(invitation, projectId, createdAt, codeRemoteUrl,
        host.sign(unsigned.signedBytes()));
  }

  /**
   * Parses a project link without trusting its signatures.
   *
   * @param link {@code synesis://project/SLP1-...}
   * @return the decoded link; {@link #verifyAt} must be true before it is used
   * @throws IncompatibleVersionException if the link carries another compatibility number
   * @throws IOException                  if the link or its payload is malformed or oversized
   */
  public static ProjectInvitation fromShareLink(String link) throws IOException {
    Objects.requireNonNull(link, "project invitation link");
    return ShareLinks.parse(link, "project", "SLP1-", MAX_LINK_CHARS, "project invitation",
        "unsupported project invitation link format", ProjectInvitation::decode);
  }

  /**
   * Checks whether a URI selects the project link revision.
   *
   * @param link candidate URI
   * @return true for the {@code synesis://project/SLP1-} prefix
   */
  public static boolean isShareLink(String link) {
    return link != null && link.startsWith(PREFIX);
  }

  private static ProjectInvitation decode(byte[] encoded) throws IOException {
    if (encoded.length > MAX_BYTES) {
      throw new IOException("invalid project invitation length");
    }
    IncompatibleVersionException.requireCurrent(encoded, "invitation");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      input.readUnsignedShort();
      UUID projectId = new UUID(input.readLong(), input.readLong());
      Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
      int urlLength = input.readUnsignedShort();
      if (urlLength > MAX_URL_BYTES || urlLength > input.available()) {
        throw new IOException("invalid project invitation URL length");
      }
      String url = new String(input.readNBytes(urlLength), StandardCharsets.UTF_8);
      int innerLength = input.readUnsignedShort();
      if (innerLength == 0 || innerLength > input.available() - SIGNATURE_BYTES) {
        throw new IOException("invalid project invitation connection length");
      }
      CompactBootstrapInvitation inner = CompactBootstrapInvitation.decodeMinimized(
          input.readNBytes(innerLength));
      byte[] signature = input.readNBytes(SIGNATURE_BYTES);
      if (signature.length != SIGNATURE_BYTES || input.available() != 0) {
        throw new IOException("malformed project invitation");
      }
      ProjectInvitation value = new ProjectInvitation(inner, projectId, createdAt,
          url.isEmpty() ? null : url, signature);
      CodecSupport.requireCanonical(encoded, value.encoded(), "non-canonical project invitation");
      return value;
    } catch (EOFException | IllegalArgumentException | java.time.DateTimeException exception) {
      throw new IOException("malformed project invitation", exception);
    }
  }

  /**
   * Checks both signatures, the inviter binding and the validity interval of the connection invitation.
   *
   * @param now              local wall-clock instant
   * @param allowedClockSkew tolerated issue-time skew
   * @return true only for a currently authentic project link
   * @throws GeneralSecurityException if key parsing or signature verification fails
   */
  public boolean verifyAt(Instant now, Duration allowedClockSkew) throws GeneralSecurityException {
    return invitation.verifyAt(now, allowedClockSkew)
        && NodeIdentity.verifySignature(invitation.publicKeyEncoded(), signedBytes(), signature);
  }

  /**
   * Returns the canonical project link.
   *
   * @return copyable signed link
   */
  public String shareLink() {
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded());
  }

  /**
   * Returns the canonical payload.
   *
   * @return a fresh encoded payload
   */
  public byte[] encoded() {
    byte[] result = Arrays.copyOf(unsigned, unsigned.length + signature.length);
    System.arraycopy(signature, 0, result, unsigned.length, signature.length);
    return result;
  }

  /**
   * Returns the connection invitation the joiner dials.
   *
   * @return the unchanged {@code SLB3} invitation
   */
  public CompactBootstrapInvitation invitation() {
    return invitation;
  }

  /**
   * Returns the project's identifier.
   *
   * @return project UUID
   */
  public UUID projectId() {
    return projectId;
  }

  /**
   * Returns the project's creation time.
   *
   * @return creation instant
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * Returns the inviter's code remote URL.
   *
   * @return the URL without credentials, or empty when the inviter has none
   */
  public Optional<String> codeRemoteUrl() {
    return Optional.ofNullable(codeRemoteUrl);
  }

  /**
   * Returns the inviter's node ID.
   *
   * @return node ID derived from the signing key
   */
  public String hostNodeId() {
    return invitation.hostNodeId();
  }

  /**
   * Removes what must never travel in a link from a Git URL: the user and password of an {@code http} or
   * {@code https} URL, and the user of any other URL that has a password. A user name alone ({@code git@host:path},
   * {@code ssh://git@host/path}) is kept, because it is how the clone is reached and is not a secret.
   *
   * @param url a Git remote URL
   * @return the URL as it may be written into a link
   */
  public static String publicUrl(String url) {
    String stripped = url.strip();
    if (PASSWORD_IN_AUTHORITY.matcher(stripped).find() || WEB_USERINFO.matcher(stripped).find()) {
      return stripped.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@", "$1");
    }
    return stripped;
  }

  /**
   * Reduces a Git URL to a form in which two spellings of one repository are equal: lower-case scheme and host,
   * no user, no trailing slash or {@code .git}, and {@code user@host:path} written like {@code ssh://host/path}.
   *
   * @param url a Git remote URL
   * @return the comparison form
   */
  public static String normalizedUrl(String url) {
    String value = url.strip();
    java.util.regex.Matcher scp = Pattern.compile("^(?:[^/@:]+@)?([^/@:]+):(?!//)(.+)$").matcher(value);
    if (!value.contains("://") && scp.matches()) {
      value = "ssh://" + scp.group(1) + "/" + scp.group(2).replaceFirst("^/+", "");
    }
    value = value.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@", "$1");
    int schemeEnd = value.indexOf("://");
    // A local path has no host to lower-case.
    int pathStart = schemeEnd < 0 ? 0 : value.indexOf('/', schemeEnd + 3);
    String head = pathStart < 0 ? value : value.substring(0, pathStart);
    String path = pathStart < 0 ? "" : value.substring(pathStart);
    path = path.replaceFirst("/+$", "").replaceFirst("\\.git$", "");
    return head.toLowerCase(Locale.ROOT) + path;
  }

  /**
   * Tells whether a code remote URL may be written into a link and fetched from by another computer.
   *
   * @param url a Git remote URL without credentials
   * @return true only for {@code https://}, {@code ssh://} and {@code user@host:path} forms
   */
  public static boolean isFetchableUrl(String url) {
    return url != null && FETCHABLE.matcher(url).matches();
  }

  private static String checkedUrl(String url) {
    if (url == null) {
      return null;
    }
    if (url.isBlank() || url.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES
        || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
        || !publicUrl(url).equals(url)
        // Neither an option for the command it is given to, nor a Git remote helper ("ext::cmd") that runs one.
        || url.startsWith("-") || url.matches("^[A-Za-z0-9]+::.*") || !isFetchableUrl(url)) {
      throw new IllegalArgumentException("code remote URL is empty, too long, carries credentials or is not an https, ssh or user@host:path URL");
    }
    return url;
  }

  private byte[] signedBytes() {
    byte[] result = Arrays.copyOf(SIGNATURE_DOMAIN, SIGNATURE_DOMAIN.length + unsigned.length);
    System.arraycopy(unsigned, 0, result, SIGNATURE_DOMAIN.length, unsigned.length);
    return result;
  }

  private byte[] encodeUnsigned() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeShort(LinkCompatibility.CURRENT);
        output.writeLong(projectId.getMostSignificantBits());
        output.writeLong(projectId.getLeastSignificantBits());
        output.writeLong(createdAt.getEpochSecond());
        output.writeInt(createdAt.getNano());
        byte[] url = codeRemoteUrl == null ? new byte[0] : codeRemoteUrl.getBytes(StandardCharsets.UTF_8);
        output.writeShort(url.length);
        output.write(url);
        byte[] inner = invitation.encoded();
        output.writeShort(inner.length);
        output.write(inner);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }
}
