package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Verifies that every share-link parser rejects a malformed link with its own message.
 */
final class ShareLinksTest {

  private interface Parser {
    void parse(String link) throws IOException;
  }

  private record Format(String name, Parser parser, String host, String prefix, int maxChars,
      String label, String wrongPrefixMessage) {
  }

  private static final List<Format> FORMATS = List.of(
      new Format("SYN1", SessionInvitation::fromShareLink, "join", "SYN1-",
          SessionInvitation.MAX_LINK_CHARS, "invitation", "invalid invitation link format"),
      new Format("SLO1", TraversalInvitation::fromShareLink, "join", "SLO1-",
          TraversalInvitation.MAX_LINK_CHARS, "SLO1", "invalid SLO1 link format"),
      new Format("SLA2", TraversalAnswer::fromShareLink, "answer", "SLA2-",
          TraversalAnswer.MAX_LINK_CHARS, "SLA2", "invalid SLA2 link format"),
      new Format("SLB2", CompactBootstrapInvitation::fromShareLink, "join", "SLB2-",
          CompactBootstrapInvitation.MAX_LINK_CHARS, "compact invitation",
          "unsupported compact invitation link format"));

  @Test
  void rejectsWrongSchemeHostAndQuery() {
    for (Format format : FORMATS) {
      String uri = "invalid " + format.label() + " URI";
      assertRejected(format, "http://" + format.host() + "/" + format.prefix() + "AAAA", uri);
      assertRejected(format, "synesis://other/" + format.prefix() + "AAAA", uri);
      assertRejected(format, "synesis://" + format.host() + "/" + format.prefix() + "AAAA?x=1",
          uri);
      assertRejected(format, "synesis://" + format.host() + "/" + format.prefix() + "AAAA#x", uri);
    }
  }

  @Test
  void rejectsMissingOrWrongPrefixWithItsOwnMessage() {
    for (Format format : FORMATS) {
      String shape = "invalid " + format.label() + " link format";
      assertRejected(format, "synesis://" + format.host(), shape);
      assertRejected(format, "synesis://" + format.host() + "/" + format.prefix(), shape);
      assertRejected(format, "synesis://" + format.host() + "/XXXX-AAAA",
          format.wrongPrefixMessage());
    }
  }

  @Test
  void rejectsOversizedAndBadBase64Links() {
    for (Format format : FORMATS) {
      assertRejected(format,
          "synesis://" + format.host() + "/" + format.prefix() + "A".repeat(format.maxChars()),
          format.label() + " link is oversized");
      assertRejected(format, "synesis://" + format.host() + "/" + format.prefix() + "***",
          "invalid " + format.label() + " link");
      assertRejected(format, "synesis://" + format.host() + "/" + format.prefix() + "A",
          "invalid " + format.label() + " link");
    }
  }

  private static void assertRejected(Format format, String link, String message) {
    IOException failure = assertThrows(IOException.class, () -> format.parser().parse(link),
        format.name() + " " + link);
    assertEquals(message, failure.getMessage(), format.name() + " " + link);
  }
}
