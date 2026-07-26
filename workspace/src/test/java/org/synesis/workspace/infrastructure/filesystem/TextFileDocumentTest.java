package org.synesis.workspace.infrastructure.filesystem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;

/** Tests the raw-byte and logical-text normalization contract. */
class TextFileDocumentTest {

    @Test
    void crlfRoundTripUsesCopiedLogicalTextAndPreservesBytesPolicy() throws Exception {
        byte[] raw = "one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8);
        TextFileDocument document = TextFileDocument.decode(raw);

        assertEquals("one\ntwo\nthree", document.logicalText());
        assertEquals(TextFileDocument.LineEndingStyle.CRLF, document.lineEndingStyle());
        byte[] changed = document.encode(document.logicalText().replace("two", "TWO"));
        assertEquals("one\r\nTWO\r\nthree", new String(changed, StandardCharsets.UTF_8));
        assertEquals(64, document.revision().length());
    }

    @Test
    void lfNoFinalNewlineAndBomArePreserved() throws Exception {
        byte[] raw = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                'a', '\n', 'b'};
        TextFileDocument document = TextFileDocument.decode(raw);

        assertEquals("a\nb", document.logicalText());
        assertEquals(TextFileDocument.LineEndingStyle.LF, document.lineEndingStyle());
        assertArrayEquals(raw, document.encode(document.logicalText()));
    }

    @Test
    void noNewlineDocumentRemainsWithoutFinalNewline() throws Exception {
        byte[] raw = "plain".getBytes(StandardCharsets.UTF_8);
        TextFileDocument document = TextFileDocument.decode(raw);

        assertEquals(TextFileDocument.LineEndingStyle.NONE, document.lineEndingStyle());
        assertArrayEquals("changed".getBytes(StandardCharsets.UTF_8), document.encode("changed"));
    }

    @Test
    void mixedLineEndingsAreReadableButMutationRequiresReview() throws Exception {
        TextFileDocument document = TextFileDocument.decode("a\r\nb\nc".getBytes(StandardCharsets.UTF_8));

        assertEquals("a\nb\nc", document.logicalText());
        assertEquals(TextFileDocument.LineEndingStyle.MIXED, document.lineEndingStyle());
        assertThrows(java.io.IOException.class, () -> document.encode(document.logicalText()));
    }
}
