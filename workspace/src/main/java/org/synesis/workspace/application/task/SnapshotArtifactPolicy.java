package org.synesis.workspace.application.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Classifies provider and Synesis administration paths explicitly before a
 * source snapshot is materialized.
 */
public final class SnapshotArtifactPolicy {

    /** One classification of a changed repository-relative path. */
    public enum Classification {
        /** Path is source content and belongs in the snapshot delta. */
        SOURCE,
        /** Path is attested provider/runtime material omitted from source trees. */
        ALLOWED_RUNTIME_ARTIFACT,
        /** Path is a managed contract and cannot be silently omitted. */
        MANAGED_CONTRACT,
        /** Path is a Synesis/provider-looking artifact outside the allowlist. */
        UNSUPPORTED_ARTIFACT
    }

    /** Explicit artifact manifest recorded in snapshot provenance.
     * @param allowedArtifacts attested provider/runtime paths omitted from source
     * @param rejectedArtifacts paths that must block publication
     * @param digest deterministic manifest digest
     */
    public record Manifest(List<String> allowedArtifacts, List<String> rejectedArtifacts, String digest) {
        /** Copies paths and validates the manifest digest. */
        public Manifest {
            allowedArtifacts = List.copyOf(Objects.requireNonNull(allowedArtifacts, "allowedArtifacts"));
            rejectedArtifacts = List.copyOf(Objects.requireNonNull(rejectedArtifacts, "rejectedArtifacts"));
            Objects.requireNonNull(digest, "digest");
        }

        /**
         * Returns whether the changed paths are permitted by the policy.
         *
         * @return true when no path requires rejection
         */
        public boolean valid() {
            return rejectedArtifacts.isEmpty();
        }
    }

    /** Creates the fixed first-release artifact policy. */
    public SnapshotArtifactPolicy() {
    }

    /**
     * Classifies changed paths and creates a deterministic manifest.
     *
     * @param paths complete changed-path list from Git
     * @return explicit artifact manifest
     */
    public Manifest classify(List<String> paths) {
        Objects.requireNonNull(paths, "paths");
        List<String> allowed = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String input : paths) {
            String path = normalize(input);
            Classification classification = classify(path);
            if (classification == Classification.ALLOWED_RUNTIME_ARTIFACT) {
                allowed.add(path);
            } else if (classification != Classification.SOURCE) {
                rejected.add(path);
            }
        }
        allowed = allowed.stream().distinct().sorted().toList();
        rejected = rejected.stream().distinct().sorted().toList();
        return new Manifest(allowed, rejected, digest(allowed, rejected));
    }

    /**
     * Classifies one repository-relative path.
     *
     * @param path repository-relative path
     * @return path classification
     */
    public Classification classify(String path) {
        String normalized = normalize(path);
        if (normalized.equals("AGENTS.md") || normalized.equals(".synesis/project.json")) {
            return Classification.MANAGED_CONTRACT;
        }
        if (normalized.equals(".mcp.json") || normalized.startsWith(".codex/")
                || normalized.startsWith(".claude/") || normalized.startsWith(".agents/")
                || normalized.startsWith(".synesis/local/") || normalized.startsWith(".synesis/shared/")
                || normalized.startsWith(".synesis/coordination/")) {
            return Classification.ALLOWED_RUNTIME_ARTIFACT;
        }
        if (normalized.equals(".synesis") || normalized.startsWith(".synesis/")) {
            return Classification.UNSUPPORTED_ARTIFACT;
        }
        return Classification.SOURCE;
    }

    private static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//") || normalized.contains("../")
                || normalized.equals("..") || normalized.isBlank()) {
            throw new IllegalArgumentException("invalid snapshot path: " + path);
        }
        return normalized;
    }

    private static String digest(List<String> allowed, List<String> rejected) {
        String value = "allowed\n" + String.join("\n", allowed) + "\nrejected\n"
                + String.join("\n", rejected);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SNAPSHOT_ARTIFACT_DIGEST_FAILED", failure);
        }
    }
}
