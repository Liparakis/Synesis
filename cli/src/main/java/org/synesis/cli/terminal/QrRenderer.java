package org.synesis.cli.terminal;

/**
 * Renders the exact invitation link as a terminal QR representation.
 */
interface QrRenderer {
    /**
     * @param link exact share link @return rendered QR
     */
    String render(String link);
}
