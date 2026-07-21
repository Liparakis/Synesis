package org.synesis.projectrecord;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic, portable repository-relative scope matching engine.
 *
 * <p>Canonical internal paths use forward slashes ('/'). Paths containing
 * backslashes are normalized. Absolute paths and directory traversal ('..')
 * are rejected as invalid input.
 *
 * @since 1.0
 */
public final class ScopeMatcher {

    private ScopeMatcher() {
    }

    /**
     * Normalizes a repository-relative path to canonical representation.
     *
     * @param path raw path string
     * @return normalized repository-relative path
     * @throws IllegalArgumentException if path is null, absolute, or attempts traversal
     */
    public static String normalizePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Path contains null character");
        }

        String unixPath = path.replace('\\', '/');

        // Check for Windows drive letters like C:/ or C:
        if (unixPath.length() >= 2 && Character.isLetter(unixPath.charAt(0)) && unixPath.charAt(1) == ':') {
            throw new IllegalArgumentException("Absolute paths with drive letters are not supported: " + path);
        }

        // Check for absolute leading slash
        if (unixPath.startsWith("/")) {
            throw new IllegalArgumentException("Absolute paths are not supported: " + path);
        }

        // Collapse repeated slashes
        while (unixPath.contains("//")) {
            unixPath = unixPath.replace("//", "/");
        }

        // Split segments and check traversal / collapse current dir
        String[] segments = unixPath.split("/");
        StringBuilder normalized = new StringBuilder();
        int count = 0;

        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Directory traversal '..' is forbidden: " + path);
            }
            if (count > 0) {
                normalized.append("/");
            }
            normalized.append(segment);
            count++;
        }

        return normalized.toString();
    }

    /**
     * Evaluates whether a target repository-relative path matches a scope pattern.
     *
     * @param pattern scope pattern (e.g. {@code src/protocol/**})
     * @param path    target file or path to test
     * @return true if the path matches the pattern
     * @throws IllegalArgumentException if pattern or path are invalid
     */
    public static boolean matches(String pattern, String path) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(path, "path");

        String normPath = normalizePath(path);
        String normPattern = normalizePattern(pattern);

        if (normPattern.equals(normPath)) {
            return true;
        }

        String regex = globToRegex(normPattern);
        return Pattern.compile(regex).matcher(normPath).matches();
    }

    private static String normalizePattern(String pattern) {
        String unixPattern = pattern.replace('\\', '/');
        if (unixPattern.length() >= 2 && Character.isLetter(unixPattern.charAt(0)) && unixPattern.charAt(1) == ':') {
            throw new IllegalArgumentException("Absolute pattern paths are not supported: " + pattern);
        }
        if (unixPattern.startsWith("/")) {
            throw new IllegalArgumentException("Absolute pattern paths are not supported: " + pattern);
        }
        String[] segments = unixPattern.split("/");
        for (String seg : segments) {
            if ("..".equals(seg)) {
                throw new IllegalArgumentException("Directory traversal '..' in pattern is forbidden: " + pattern);
            }
        }
        while (unixPattern.contains("//")) {
            unixPattern = unixPattern.replace("//", "/");
        }
        if (unixPattern.startsWith("./")) {
            unixPattern = unixPattern.substring(2);
        }
        return unixPattern;
    }

    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        int len = pattern.length();
        for (int i = 0; i < len; i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < len && pattern.charAt(i + 1) == '*') {
                    // '**' wildcard
                    regex.append(".*");
                    i++; // skip second '*'
                    // skip trailing slash if pattern has '**/'
                    if (i + 1 < len && pattern.charAt(i + 1) == '/') {
                        i++;
                    }
                } else {
                    // single '*' wildcard (matches non-slash chars within one segment)
                    regex.append("[^/]*");
                }
            } else if ("().[]{}?^$\\+|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append("$");
        return regex.toString();
    }
}
