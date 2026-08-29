package org.synesis.coordination.domain.collaboration;

import java.util.Objects;

/**
 * Canonical repository-relative resource selector used for collaboration claims.
 * The first protocol slice supports exact files and directory subtrees only.
 *
 * @param kind  selector kind
 * @param value normalized repository-relative value
 */
public record ResourceSelector(Kind kind, String value) {

    /**
     * Validates and canonicalizes a selector.
     */
    public ResourceSelector {
        Objects.requireNonNull(kind, "kind");
        value = normalize(value, kind);
    }

    /**
     * Creates an exact repository-relative file selector.
     *
     * @param path path
     * @return selector
     */
    public static ResourceSelector pathExact(String path) {
        return new ResourceSelector(Kind.PATH_EXACT, path);
    }

    /**
     * Creates a repository-relative subtree selector.
     *
     * @param path directory
     * @return selector
     */
    public static ResourceSelector pathSubtree(String path) {
        return new ResourceSelector(Kind.PATH_SUBTREE, path);
    }

    private static String normalize(String raw, Kind kind) {
        Objects.requireNonNull(raw, "value");
        String normalized = raw.trim()
                .replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:/.*") || normalized.contains("//")) {
            throw new IllegalArgumentException("resource selector must be repository-relative");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.equalsIgnoreCase(".git") || segment.equalsIgnoreCase(".synesis")) {
                throw new IllegalArgumentException("protected or traversal selector");
            }
        }
        if (kind == Kind.PATH_SUBTREE && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Returns whether this selector overlaps another selector.
     *
     * @param other selector
     * @return overlap
     */
    public boolean overlaps(ResourceSelector other) {
        Objects.requireNonNull(other, "other");
        if (kind == Kind.PATH_EXACT && other.kind == Kind.PATH_EXACT) {
            return value.equals(other.value);
        }
        String left = value;
        String right = other.value;
        if (kind == Kind.PATH_SUBTREE && other.kind == Kind.PATH_EXACT) {
            return right.equals(left) || right.startsWith(left + "/");
        }
        if (kind == Kind.PATH_EXACT && other.kind == Kind.PATH_SUBTREE) {
            return left.equals(right) || left.startsWith(right + "/");
        }
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    /**
     * Supported selector kinds in the first collaboration slice.
     */
    public enum Kind {
        /**
         * One exact repository-relative file.
         */
        PATH_EXACT,
        /**
         * One repository-relative directory subtree.
         */
        PATH_SUBTREE
    }
}
