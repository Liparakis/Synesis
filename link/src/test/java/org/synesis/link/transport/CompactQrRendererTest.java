package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.jupiter.api.Test;

/** Verifies exact-link QR encoding, compact dimensions, and narrow terminals. */
final class CompactQrRendererTest {
    @Test
    void rendersTheExactZxingMatrixForTheOriginalLink() throws Exception {
        String link = "synesis://join/SYN1-exact-link";
        BitMatrix expected = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 1, 1);
        String[] rows = new CompactQrRenderer(200).render(link).split("\\R");
        int border = 2;

        assertEquals(expected.getWidth() + border * 2, rows[0].length());
        assertEquals((expected.getHeight() + border * 2 + 1) / 2, rows.length);
        for (int row = 0; row < rows.length; row++) {
            int y = row * 2 - border;
            for (int x = 0; x < rows[row].length(); x++) {
                char glyph = rows[row].charAt(x);
                assertEquals(black(expected, x - border, y), upper(glyph));
                assertEquals(black(expected, x - border, y + 1), lower(glyph));
            }
        }
    }

    @Test
    void skipsBeforeRenderingWhenTheTerminalCannotFitTheQr() {
        String link = "synesis://join/SYN1-narrow";
        BitMatrix expected = encode(link);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new CompactQrRenderer(expected.getWidth() + 3).render(link));
        assertEquals("TERMINAL_TOO_NARROW", failure.getMessage());
    }

    @Test
    void skipsBeforeRenderingWhenUnicodeCannotBeEncoded() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new CompactQrRenderer(200, false).render("synesis://join/SYN1-unicode"));
        assertEquals("UNICODE_UNSUPPORTED", failure.getMessage());
    }

    private static BitMatrix encode(String link) {
        try {
            return new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 1, 1);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean upper(char glyph) { return glyph == '█' || glyph == '▀'; }

    private static boolean lower(char glyph) { return glyph == '█' || glyph == '▄'; }

    private static boolean black(BitMatrix matrix, int x, int y) {
        return x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
    }
}
