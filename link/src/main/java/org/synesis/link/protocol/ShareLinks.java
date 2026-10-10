package org.synesis.link.protocol;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;

/**
 * Package-private parser for {@code synesis://<host>/<PREFIX>-<base64url>} share links.
 *
 * <p>The link label names the record in every failure message, so each record keeps its own text.
 */
final class ShareLinks {

  /** Decodes the base64url payload of an already-validated link. */
  @FunctionalInterface
  interface Decoder<T> {
    T decode(byte[] encoded) throws IOException;
  }

  private ShareLinks() {
  }

  static <T> T parse(String link, String host, String prefix, int maxChars, String label,
      Decoder<T> decoder) throws IOException {
    return parse(link, host, prefix, maxChars, label, "invalid " + label + " link format", decoder);
  }

  /**
   * Parses a link whose wrong-prefix path fails with {@code wrongPrefixMessage} instead of the
   * generic format message.
   */
  static <T> T parse(String link, String host, String prefix, int maxChars, String label,
      String wrongPrefixMessage, Decoder<T> decoder) throws IOException {
    if (link.length() > maxChars) {
      throw new IOException(label + " link is oversized");
    }
    try {
      URI uri = new URI(link);
      if (!"synesis".equals(uri.getScheme()) || !host.equals(uri.getHost())
          || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IOException("invalid " + label + " URI");
      }
      String path = uri.getPath();
      String pathPrefix = "/" + prefix;
      if (path == null || path.length() <= pathPrefix.length()) {
        throw new IOException("invalid " + label + " link format");
      }
      if (!path.startsWith(pathPrefix)) {
        throw new IOException(wrongPrefixMessage);
      }
      return decoder.decode(Base64.getUrlDecoder().decode(path.substring(pathPrefix.length())));
    } catch (IllegalArgumentException | URISyntaxException exception) {
      throw new IOException("invalid " + label + " link", exception);
    }
  }
}
