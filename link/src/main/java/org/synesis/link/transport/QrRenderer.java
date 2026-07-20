package org.synesis.link.transport;

/** Renders a share link as an alternate terminal representation. */
interface QrRenderer {
    /** Returns a terminal-safe QR representation of the exact input link. */
    String render(String link);
}
