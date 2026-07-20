package org.synesis.link.transport;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Renders an exact share link as a compact Unicode QR for terminals. */
final class CompactQrRenderer implements QrRenderer {
    private static final int BORDER_MODULES = 2;
    private static final int DEFAULT_TERMINAL_WIDTH = 120;
    private final int terminalWidth;

    CompactQrRenderer() {
        this(detectTerminalWidth());
    }

    CompactQrRenderer(int terminalWidth) {
        if (terminalWidth < 1) throw new IllegalArgumentException("terminal width must be positive");
        this.terminalWidth = terminalWidth;
    }

    @Override
    public String render(String link) {
        if (link == null || link.isBlank()) throw new IllegalArgumentException("share link is blank");
        try {
            BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 1, 1);
            int width = matrix.getWidth() + BORDER_MODULES * 2;
            if (width > terminalWidth) throw new IllegalArgumentException("TERMINAL_TOO_NARROW");
            int height = matrix.getHeight() + BORDER_MODULES * 2;
            StringBuilder output = new StringBuilder((height + 1) / 2 * (width + 1));
            for (int y = -BORDER_MODULES; y < matrix.getHeight() + BORDER_MODULES; y += 2) {
                for (int x = -BORDER_MODULES; x < matrix.getWidth() + BORDER_MODULES; x++) {
                    output.append(glyph(black(matrix, x, y), black(matrix, x, y + 1)));
                }
                output.append(System.lineSeparator());
            }
            return output.toString();
        } catch (WriterException exception) {
            throw new IllegalArgumentException("QR_ENCODING_FAILED", exception);
        }
    }

    private static int detectTerminalWidth() {
        String configured = System.getProperty("synesis.link.terminal.width");
        if (configured == null || configured.isBlank()) configured = System.getenv("COLUMNS");
        if (configured != null) {
            try {
                int width = Integer.parseInt(configured);
                if (width > 0) return width;
            } catch (NumberFormatException ignored) { }
        }
        return DEFAULT_TERMINAL_WIDTH;
    }

    private static char glyph(boolean upper, boolean lower) {
        return upper ? (lower ? '█' : '▀') : (lower ? '▄' : ' ');
    }

    private static boolean black(BitMatrix matrix, int x, int y) {
        return x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
    }
}
