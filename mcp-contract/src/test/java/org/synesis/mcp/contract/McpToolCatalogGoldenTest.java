package org.synesis.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Pins the published tools/list projection and the catalog digests that persisted provider
 * manuals are compared against. A deliberate catalog change must update the golden file and the
 * digests here in the same change.
 */
class McpToolCatalogGoldenTest {

    static final String GOLDEN_RESOURCE = "tools-list.golden.json";

    private static final String WIRE_DIGEST = "d2d7a1993b698734c5966ef957f3f5d662b5838b5f8a7315480c4c82403eeb97";
    private static final String CONTENT_DIGEST = "fa064e32a8e6845ef2b2d4cdf5dc97711cc8d46efb08c3eb7503a3f806eea48b";
    private static final String GUIDANCE_DIGEST = "2f4b9e216d6afa33812086f2d5c83c715cab778c1a781d9cb9ece3c2ec766e45";

    /**
     * Serializes the current tools/list output with map keys sorted recursively and list order kept.
     */
    static String render() {
        StringBuilder out = new StringBuilder();
        write(McpToolCatalog.toolsList(), 0, out);
        return out.append('\n').toString();
    }

    private static void write(Object value, int depth, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int remaining = map.size();
            for (Map.Entry<String, Object> entry : new TreeMap<>(castMap(map)).entrySet()) {
                indent(depth + 1, out);
                quote(entry.getKey(), out);
                out.append(": ");
                write(entry.getValue(), depth + 1, out);
                out.append(--remaining > 0 ? ",\n" : "\n");
            }
            indent(depth, out);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(depth + 1, out);
                write(list.get(i), depth + 1, out);
                out.append(i < list.size() - 1 ? ",\n" : "\n");
            }
            indent(depth, out);
            out.append(']');
        } else if (value instanceof String text) {
            quote(text, out);
        } else {
            out.append(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static void indent(int depth, StringBuilder out) {
        out.append("  ".repeat(depth));
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        out.append('"');
    }

    @Test
    void toolsListMatchesTheGoldenProjection() throws IOException {
        String golden;
        try (InputStream in = McpToolCatalogGoldenTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(golden, render());
    }

    @Test
    void digestsMatchThePersistedManualIdentities() {
        assertEquals(WIRE_DIGEST, McpToolCatalog.wireCompatibilityDigest());
        assertEquals(CONTENT_DIGEST, McpToolCatalog.catalogContentDigest());
        assertEquals(GUIDANCE_DIGEST, McpToolCatalog.guidanceArtifactDigest("manual", "codex", "rendered manual".getBytes(StandardCharsets.UTF_8)));
    }
}
