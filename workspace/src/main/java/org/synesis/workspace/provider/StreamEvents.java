package org.synesis.workspace.provider;

import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Reads the JSON event lines a headless provider prints, which are interleaved with diagnostics that are not JSON.
 */
public final class StreamEvents {

    private StreamEvents() {
    }

    /**
     * Parses one output line as a JSON event.
     *
     * @param outputLine one line the provider printed
     * @return the event object, or {@code null} when the line is not a JSON object
     */
    public static Map<?, ?> parse(String outputLine) {
        if (outputLine == null || !outputLine.startsWith("{")) {
            return null;
        }
        try {
            return ProviderJson.parse(outputLine) instanceof Map<?, ?> event ? event : null;
        } catch (RuntimeException notAnEvent) {
            return null;
        }
    }

    /**
     * Reads a text field.
     *
     * @param object a parsed JSON object
     * @param field  field name
     * @return the field's text, or the empty string when it is missing or not text
     */
    public static String text(Map<?, ?> object, String field) {
        return object.get(field) instanceof String text ? text : "";
    }
}
