package org.synesis.mcp.application;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives candidate project roots from MCP {@code initialize} and roots parameters and from the
 * launcher's fallback environment.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because this is lexical decoding only: it neither
 * decides which candidate is accepted nor touches repository state. That authority decision stays in
 * {@link McpProjectRootAuthority} and in the handler, which owns the selected root.
 */
final class McpRootCandidates {

    /**
     * Project metadata file whose presence marks an initialized Synesis project.
     */
    private static final String PROJECT_METADATA = ".synesis/project.json";
    /**
     * Environment variables scanned for a workspace root when initialize yields none, in priority order.
     */
    private static final List<String> FALLBACK_ROOT_ENVIRONMENT_KEYS = List.of("WORKSPACE_ROOT", "WORKSPACE_FOLDER", "WORKSPACE_DIR", "PROJECT_ROOT", "PROJECT_DIR", "VSCODE_WORKSPACE_FOLDER", "INIT_CWD", "PWD");

    /** Utility class; these rules are exposed through static operations. */
    private McpRootCandidates() {
    }

    /**
     * Safely converts a URI string or local path string into a normalized absolute {@link Path}.
     *
     * @param input URI or local path string
     * @return normalized absolute Path, or {@code null} if empty/invalid
     */
    static Path parseUriOrPath(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.startsWith("file:")) {
            try {
                return Path.of(URI.create(trimmed)).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // Not a usable file URI; fall back to treating the text as a path.
            }
        }

        try {
            return Path.of(URLDecoder.decode(trimmed, StandardCharsets.UTF_8)).toAbsolutePath().normalize();
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * Extracts candidate workspace project root paths from MCP {@code initialize} request parameters
     * only. Does not include environment variables, file system scans, or process working directory.
     *
     * @param params JSON-RPC initialize request params map, or {@code null}
     * @return list of parsed candidate paths from protocol params
     */
    static List<Path> extractCandidateRoots(Map<String, Object> params) {
        List<Path> candidates = new ArrayList<>();
        if (params == null) {
            return candidates;
        }

        if (params.get("rootUri") instanceof String rootUriStr) {
            Path p = parseUriOrPath(rootUriStr);
            if (p != null) {
                candidates.add(p);
            }
        }

        if (params.get("workspaceFolders") instanceof List<?> folders) {
            addUris(folders, candidates);
        }

        if (params.get("roots") instanceof List<?> rootsList) {
            addUris(rootsList, candidates);
        }

        return candidates;
    }

    /**
     * Adds the parseable, not yet present {@code uri} of each folder or root entry.
     *
     * @param entries    JSON array of objects that may carry a {@code uri} string
     * @param candidates list receiving the parsed paths
     */
    private static void addUris(List<?> entries, List<Path> candidates) {
        for (Object item : entries) {
            if (item instanceof Map<?, ?> map && map.get("uri") instanceof String uriStr) {
                Path p = parseUriOrPath(uriStr);
                if (p != null && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }
    }

    /**
     * Collects fallback candidate project roots from supported environment variables, a trusted
     * launcher-provided root, and the process working directory. Only called when MCP
     * {@code initialize} params yield no initialized root.
     *
     * @param initialProjectRoot trusted project root supplied by the launcher
     * @return list of fallback candidate paths
     */
    static List<Path> extractFallbackCandidates(Path initialProjectRoot) {
        if (Files.exists(initialProjectRoot.resolve(PROJECT_METADATA))) {
            return List.of(initialProjectRoot.toAbsolutePath().normalize());
        }
        List<Path> candidates = new ArrayList<>();

        // Environment variables
        for (String envKey : FALLBACK_ROOT_ENVIRONMENT_KEYS) {
            String envVal = System.getenv(envKey);
            if (envVal != null && !envVal.isBlank()) {
                Path p = parseUriOrPath(envVal);
                if (p != null && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }

        // Trusted initial project root (for example, managed continuity).
        if (!candidates.contains(initialProjectRoot)) {
            candidates.add(initialProjectRoot.toAbsolutePath().normalize());
        }

        // Current working directory (last resort)
        try {
            Path cwd = Path.of(".").toAbsolutePath().normalize();
            if (!candidates.contains(cwd)) {
                candidates.add(cwd);
            }
        } catch (Exception ignored) {
            // No usable working directory; the other candidates stand.
        }

        return candidates;
    }
}
