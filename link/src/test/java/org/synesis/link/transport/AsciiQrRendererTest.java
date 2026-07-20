package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies that the terminal QR renderer encodes the supplied share link directly. */
final class AsciiQrRendererTest {
    @Test
    void rendersDifferentMatricesForDifferentLinks() {
        AsciiQrRenderer renderer = new AsciiQrRenderer();
        String first = renderer.render("synesis://join/SYN1-first");
        String second = renderer.render("synesis://join/SYN1-second");
        assertTrue(first.contains("#"));
        assertNotEquals(first, second);
    }
}
