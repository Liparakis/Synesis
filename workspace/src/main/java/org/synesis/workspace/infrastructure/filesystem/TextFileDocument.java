package org.synesis.workspace.infrastructure.filesystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Loss-aware UTF-8 text document separating raw storage from provider-facing text.
 *
 * <p>Revisions are derived from the original bytes. Logical content always uses LF
 * separators, while successful writes restore the document's original newline and BOM
 * policy. Mixed newline files are readable but are conservatively not writable.
 */
public final class TextFileDocument {

    /** Supported physical newline styles. */
    public enum LineEndingStyle {
        /** No newline separators are present. */
        NONE,
        /** LF separators are present. */
        LF,
        /** CRLF separators are present. */
        CRLF,
        /** CR separators are present. */
        CR,
        /** More than one physical separator style is present. */
        MIXED
    }

    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private final byte[] rawBytes;
    private final String logicalText;
    private final LineEndingStyle lineEndingStyle;
    private final boolean utf8Bom;

    private TextFileDocument(byte[] rawBytes, String logicalText, LineEndingStyle lineEndingStyle,
            boolean utf8Bom) {
        this.rawBytes = rawBytes.clone();
        this.logicalText = logicalText;
        this.lineEndingStyle = lineEndingStyle;
        this.utf8Bom = utf8Bom;
    }

    /**
     * Decodes a raw UTF-8 file into its logical provider representation.
     *
     * @param bytes exact bytes read from storage
     * @return decoded document
     * @throws IOException when the bytes are not valid UTF-8 text
     */
    public static TextFileDocument decode(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("text bytes are missing");
        }
        boolean bom = startsWithBom(bytes);
        int offset = bom ? UTF8_BOM.length : 0;
        String physical = decodeUtf8(bytes, offset);
        LineEndingStyle style = detectStyle(physical);
        String logical = physical.replace("\r\n", "\n").replace('\r', '\n');
        return new TextFileDocument(bytes, logical, style, bom);
    }

    /**
     * Returns the exact raw bytes captured for the revision check.
     *
     * @return defensive copy of the raw bytes
     */
    public byte[] rawBytes() {
        return rawBytes.clone();
    }

    /**
     * Returns normalized provider-facing text using LF separators and no BOM character.
     *
     * @return logical text
     */
    public String logicalText() {
        return logicalText;
    }

    /**
     * Returns the physical line-ending style detected in the raw document.
     *
     * @return detected style
     */
    public LineEndingStyle lineEndingStyle() {
        return lineEndingStyle;
    }

    /**
     * Indicates whether the original document carried a UTF-8 BOM.
     *
     * @return {@code true} when a BOM was present
     */
    public boolean utf8Bom() {
        return utf8Bom;
    }

    /**
     * Returns the SHA-256 revision of the exact raw bytes.
     *
     * @return lowercase hexadecimal revision
     */
    public String revision() {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(rawBytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Encodes logical text using the original physical storage policy.
     *
     * @param newLogicalText normalized logical replacement text
     * @return bytes suitable for persistence
     * @throws IOException when a mixed-line-ending document would be rewritten
     */
    public byte[] encode(String newLogicalText) throws IOException {
        if (newLogicalText == null) {
            throw new IOException("logical text is missing");
        }
        if (lineEndingStyle == LineEndingStyle.MIXED) {
            throw new IOException("mixed_line_endings_require_review");
        }
        String physical = switch (lineEndingStyle) {
            case CRLF -> newLogicalText.replace("\n", "\r\n");
            case CR -> newLogicalText.replace('\n', '\r');
            case LF, NONE -> newLogicalText;
            case MIXED -> throw new IOException("mixed_line_endings_require_review");
        };
        byte[] body = physical.getBytes(StandardCharsets.UTF_8);
        if (!utf8Bom) {
            return body;
        }
        byte[] encoded = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, encoded, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, encoded, UTF8_BOM.length, body.length);
        return encoded;
    }

    private static String decodeUtf8(byte[] bytes, int offset) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("unsupported_or_invalid_utf8_text", ex);
        }
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
                && Arrays.equals(UTF8_BOM, Arrays.copyOf(bytes, UTF8_BOM.length));
    }

    private static LineEndingStyle detectStyle(String physical) {
        boolean lf = false;
        boolean crlf = false;
        boolean cr = false;
        for (int i = 0; i < physical.length(); i++) {
            char c = physical.charAt(i);
            if (c == '\r') {
                if (i + 1 < physical.length() && physical.charAt(i + 1) == '\n') {
                    crlf = true;
                    i++;
                } else {
                    cr = true;
                }
            } else if (c == '\n') {
                lf = true;
            }
        }
        int kinds = (lf ? 1 : 0) + (crlf ? 1 : 0) + (cr ? 1 : 0);
        if (kinds > 1) {
            return LineEndingStyle.MIXED;
        }
        if (crlf) {
            return LineEndingStyle.CRLF;
        }
        if (lf) {
            return LineEndingStyle.LF;
        }
        if (cr) {
            return LineEndingStyle.CR;
        }
        return LineEndingStyle.NONE;
    }
}
