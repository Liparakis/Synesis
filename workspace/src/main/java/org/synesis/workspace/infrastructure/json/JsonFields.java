package org.synesis.workspace.infrastructure.json;

import java.util.Map;
import java.util.function.Function;

/**
 * Typed readers for objects parsed by {@link ProviderJson}.
 *
 * <p>Each loader decides how it reports a bad persisted file, so the caller names the exception
 * type instead of this class choosing one.</p>
 */
public final class JsonFields {

    private JsonFields() {
    }

    /**
     * Reads a required, non-blank text field.
     *
     * @param object  parsed JSON object, or {@code null} when none was found
     * @param key     field name
     * @param failure creates the exception thrown for a missing, non-text, or blank field
     * @param <X>     exception type the caller handles
     * @return the field text
     * @throws X when the field is missing, not text, or blank
     */
    public static <X extends Exception> String requiredText(Map<?, ?> object, String key, Function<String, X> failure) throws X {
        Object value = object == null ? null : object.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw failure.apply("missing " + key);
        }
        return text;
    }
}
