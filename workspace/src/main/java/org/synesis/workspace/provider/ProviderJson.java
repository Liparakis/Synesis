package org.synesis.workspace.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JDK-only JSON parser/writer for provider configuration files. */
public final class ProviderJson {
    private ProviderJson() {
    }

    /**
     * Parses one JSON document.
     * @param text JSON text
     * @return JSON value
     * @throws IllegalArgumentException if JSON is malformed
     */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.whitespace();
        if (!parser.end()) throw new IllegalArgumentException("trailing JSON content");
        return value;
    }

    /**
     * Writes one JSON value.
     * @param value JSON value
     * @return compact JSON
     */
    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output) {
        if (value == null) { output.append("null"); return; }
        if (value instanceof String string) { output.append('"').append(escape(string)).append('"'); return; }
        if (value instanceof Boolean || value instanceof Number) { output.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) output.append(',');
                first = false;
                write(String.valueOf(entry.getKey()), output);
                output.append(':');
                write(entry.getValue(), output);
            }
            output.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            output.append('[');
            for (int i = 0; i < list.size(); i++) { if (i > 0) output.append(','); write(list.get(i), output); }
            output.append(']');
            return;
        }
        throw new IllegalArgumentException("unsupported JSON value");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static final class Parser {
        private final String text;
        private int index;
        private Parser(String text) { this.text = text == null ? "" : text; }
        private boolean end() { return index == text.length(); }
        private void whitespace() { while (!end() && Character.isWhitespace(text.charAt(index))) index++; }
        private Object value() {
            whitespace();
            if (end()) throw error("missing value");
            return switch (text.charAt(index)) {
                case '{' -> object(); case '[' -> array(); case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE); case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null); default -> number();
            };
        }
        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>(); index++; whitespace();
            if (!end() && text.charAt(index) == '}') { index++; return result; }
            while (true) {
                whitespace(); if (end() || text.charAt(index) != '"') throw error("object key expected");
                String key = string(); whitespace(); if (end() || text.charAt(index++) != ':') throw error("colon expected");
                result.put(key, value()); whitespace(); if (end()) throw error("object not closed");
                char next = text.charAt(index++); if (next == '}') return result; if (next != ',') throw error("comma expected");
            }
        }
        private List<Object> array() {
            List<Object> result = new ArrayList<>(); index++; whitespace();
            if (!end() && text.charAt(index) == ']') { index++; return result; }
            while (true) { result.add(value()); whitespace(); if (end()) throw error("array not closed"); char next = text.charAt(index++); if (next == ']') return result; if (next != ',') throw error("comma expected"); }
        }
        private String string() {
            if (text.charAt(index++) != '"') throw error("string expected"); StringBuilder result = new StringBuilder();
            while (!end()) { char c = text.charAt(index++); if (c == '"') return result.toString(); if (c == '\\') { if (end()) throw error("escape missing"); char e = text.charAt(index++); result.append(switch (e) { case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/'; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> throw error("unsupported escape"); }); } else result.append(c); }
            throw error("string not closed");
        }
        private Object literal(String literal, Object value) { if (!text.startsWith(literal, index)) throw error("invalid literal"); index += literal.length(); return value; }
        private Number number() { int start = index; while (!end() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++; try { String value = text.substring(start, index); return value.contains(".") || value.contains("e") || value.contains("E") ? Double.valueOf(value) : Long.valueOf(value); } catch (RuntimeException failure) { throw error("invalid number"); } }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + index); }
    }
}
