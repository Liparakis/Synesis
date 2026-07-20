package org.synesis.link.transport;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Minimal Unicode-block QR renderer for terminals with a usable character grid.
 */
final class AsciiQrRenderer implements QrRenderer {
    @Override
    public String render(String link) {
        if (link == null || link.isBlank()) throw new IllegalArgumentException("share link is blank");
        try {
            BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 1, 1);
            StringBuilder output = new StringBuilder();
            int border = 2;
            for (int y = -border; y < matrix.getHeight() + border; y++) {
                for (int x = -border; x < matrix.getWidth() + border; x++) {
                    output.append(black(matrix, x, y) ? "##" : "  ");
                }
                output.append(System.lineSeparator());
            }
            return output.toString();
        } catch (WriterException exception) {
            throw new IllegalArgumentException("share link is too large for a terminal QR", exception);
        }
    }

    private static boolean black(BitMatrix matrix, int x, int y) {
        return x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
    }
}
