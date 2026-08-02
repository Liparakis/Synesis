package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures the semantic staged state of a Git index separately from incidental
 * index bytes such as stat and untracked-cache metadata.
 */
public final class SemanticIndexFingerprint {

    private SemanticIndexFingerprint() {
    }

    /**
     * Captures the current index semantics and raw-byte digest.
     *
     * @param repositoryRoot repository worktree
     * @return semantic index fingerprint
     * @throws IOException when the index cannot be inspected
     */
    public static Fingerprint capture(Path repositoryRoot) throws IOException {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
        String raw = rawIndexDigest(root);
        List<Entry> entries = parseEntries(root);
        Map<String, List<String>> stages = new LinkedHashMap<>();
        Map<String, String> blobs = new LinkedHashMap<>();
        Map<String, String> modes = new LinkedHashMap<>();
        List<String> unmerged = new ArrayList<>();
        List<String> intentToAdd = new ArrayList<>();
        for (Entry entry : entries) {
            stages.computeIfAbsent(entry.path(), ignored -> new ArrayList<>()).add(Integer.toString(entry.stage()));
            blobs.put(entry.path(), entry.blob());
            modes.put(entry.path(), entry.mode());
            if (entry.stage() != 0 && !unmerged.contains(entry.path())) {
                unmerged.add(entry.path());
            }
            if (entry.intentToAdd()) {
                intentToAdd.add(entry.path());
            }
        }
        String tree;
        try {
            tree = run(root, "write-tree");
        } catch (IOException unmergedIndex) {
            tree = "UNMERGED";
        }
        List<String> skip = flagPaths(root, 'S', 's');
        List<String> assume = flagPaths(root, 'h', 'H');
        boolean sparse = boolConfig(root, "index.sparse");
        boolean split = boolConfig(root, "core.splitIndex");
        List<String> extensions = new ArrayList<>();
        if (sparse) extensions.add("sparse-index");
        if (split) extensions.add("split-index");
        return new Fingerprint(raw, tree, new ArrayList<>(stages.keySet()), blobs, modes,
                stages, unmerged, intentToAdd, skip, assume, sparse, split, extensions);
    }

    /**
     * Compares two captures without treating raw cache bytes as staged work.
     *
     * @param before transaction-start fingerprint
     * @param after current fingerprint
     * @return semantic comparison result
     */
    public static Comparison compare(Fingerprint before, Fingerprint after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!before.relevantExtensions().equals(after.relevantExtensions())
                || before.sparseIndexMode() != after.sparseIndexMode()
                || before.splitIndexMode() != after.splitIndexMode()) {
            return Comparison.INDEX_EXTENSION_UNSUPPORTED;
        }
        if (before.rawIndexDigest().equals(after.rawIndexDigest())) {
            return Comparison.EXACT;
        }
        return before.semanticEquals(after) ? Comparison.NONSEMANTIC_REFRESH : Comparison.SEMANTIC_STATE_CHANGED;
    }

    private static List<Entry> parseEntries(Path root) throws IOException {
        String output = runBytes(root, "ls-files", "--stage", "-z");
        List<Entry> entries = new ArrayList<>();
        List<String> intentPaths = intentToAddPaths(root);
        for (String item : output.split("\\u0000")) {
            if (item.isBlank()) continue;
            int tab = item.indexOf('\t');
            if (tab < 0) throw new IOException("INDEX_CORRUPT");
            String[] header = item.substring(0, tab).split(" ");
            if (header.length != 3) throw new IOException("INDEX_CORRUPT");
            String blob = header[1];
            int stage;
            try {
                stage = Integer.parseInt(header[2]);
            } catch (NumberFormatException invalid) {
                throw new IOException("INDEX_CORRUPT", invalid);
            }
            String path = item.substring(tab + 1);
            entries.add(new Entry(path, header[0], blob, stage, intentPaths.contains(path)));
        }
        return List.copyOf(entries);
    }

    private static List<String> intentToAddPaths(Path root) throws IOException {
        String output = runBytes(root, "diff", "--diff-filter=A", "--name-only", "-z");
        List<String> paths = new ArrayList<>();
        for (String path : output.split("\\u0000")) {
            if (!path.isBlank()) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static List<String> flagPaths(Path root, char... markers) throws IOException {
        String output = runBytes(root, "ls-files", "-v", "-z");
        List<String> paths = new ArrayList<>();
        for (String item : output.split("\\u0000")) {
            if (item.length() < 3) continue;
            char marker = item.charAt(0);
            for (char candidate : markers) {
                if (marker == candidate) paths.add(item.substring(2));
            }
        }
        return List.copyOf(paths);
    }

    private static boolean boolConfig(Path root, String key) throws IOException {
        Result result = runResult(root, "config", "--bool", "--get", key);
        return result.exitCode() == 0 && "true".equalsIgnoreCase(result.output().trim());
    }

    private static String rawIndexDigest(Path root) throws IOException {
        Result result = runResult(root, "rev-parse", "--git-path", "index");
        if (result.exitCode() != 0) throw new IOException("INDEX_UNAVAILABLE");
        Path index = root.resolve(result.output().trim()).normalize();
        if (!Files.isRegularFile(index)) throw new IOException("INDEX_UNAVAILABLE");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(index)));
        } catch (Exception failure) {
            throw new IOException("INDEX_DIGEST_UNAVAILABLE", failure);
        }
    }

    private static String run(Path root, String... args) throws IOException {
        Result result = runResult(root, args);
        if (result.exitCode() != 0) throw new IOException(result.output().isBlank() ? "GIT_COMMAND_FAILED" : result.output());
        return result.output().trim();
    }

    private static String runBytes(Path root, String... args) throws IOException {
        return run(root, args);
    }

    private static Result runResult(Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new Result(exit, output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Git index inspection interrupted", interrupted);
        }
    }

    /** Semantic comparison categories used by recovery decisions. */
    public enum Comparison {
        /** Raw and semantic identity are unchanged. */
        EXACT,
        /** Raw bytes changed but staged semantics are equivalent. */
        NONSEMANTIC_REFRESH,
        /** Staged semantics changed and cannot be overwritten safely. */
        SEMANTIC_STATE_CHANGED,
        /** Relevant unsupported index mode or extension changed. */
        INDEX_EXTENSION_UNSUPPORTED
    }

    /** Immutable captured index fingerprint. */
    public record Fingerprint(String rawIndexDigest, String indexTreeId, List<String> stagedEntryPaths,
                              Map<String, String> stagedBlobIds, Map<String, String> entryModes,
                              Map<String, List<String>> entryStages, List<String> unmergedEntries,
                              List<String> intentToAddFlags, List<String> skipWorktreeFlags,
                              List<String> assumeUnchangedFlags, boolean sparseIndexMode,
                              boolean splitIndexMode, List<String> relevantExtensions) {
        /** Copies all collections into immutable values. */
        public Fingerprint {
            Objects.requireNonNull(rawIndexDigest, "rawIndexDigest");
            Objects.requireNonNull(indexTreeId, "indexTreeId");
            stagedEntryPaths = List.copyOf(stagedEntryPaths);
            stagedBlobIds = Map.copyOf(stagedBlobIds);
            entryModes = Map.copyOf(entryModes);
            entryStages = entryStages.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
            unmergedEntries = List.copyOf(unmergedEntries);
            intentToAddFlags = List.copyOf(intentToAddFlags);
            skipWorktreeFlags = List.copyOf(skipWorktreeFlags);
            assumeUnchangedFlags = List.copyOf(assumeUnchangedFlags);
            relevantExtensions = List.copyOf(relevantExtensions);
        }

        private boolean semanticEquals(Fingerprint other) {
            return indexTreeId.equals(other.indexTreeId)
                    && stagedEntryPaths.equals(other.stagedEntryPaths)
                    && stagedBlobIds.equals(other.stagedBlobIds)
                    && entryModes.equals(other.entryModes)
                    && entryStages.equals(other.entryStages)
                    && unmergedEntries.equals(other.unmergedEntries)
                    && intentToAddFlags.equals(other.intentToAddFlags)
                    && skipWorktreeFlags.equals(other.skipWorktreeFlags)
                    && assumeUnchangedFlags.equals(other.assumeUnchangedFlags);
        }

        /** Returns a JSON-safe diagnostic projection of this fingerprint.
         * @return serialized fingerprint map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("rawIndexDigest", rawIndexDigest);
            values.put("indexTreeId", indexTreeId);
            values.put("stagedEntryPaths", stagedEntryPaths);
            values.put("stagedBlobIds", stagedBlobIds);
            values.put("entryModes", entryModes);
            values.put("entryStages", entryStages);
            values.put("unmergedEntries", unmergedEntries);
            values.put("intentToAddFlags", intentToAddFlags);
            values.put("skipWorktreeFlags", skipWorktreeFlags);
            values.put("assumeUnchangedFlags", assumeUnchangedFlags);
            values.put("sparseIndexMode", sparseIndexMode);
            values.put("splitIndexMode", splitIndexMode);
            values.put("relevantExtensions", relevantExtensions);
            return Map.copyOf(values);
        }

        /** Reconstructs a fingerprint from a JSON-safe diagnostic projection.
         * @param map serialized fingerprint
         * @return reconstructed fingerprint
         */
        @SuppressWarnings("unchecked")
        public static Fingerprint fromMap(Map<String, Object> map) {
            Objects.requireNonNull(map, "map");
            return new Fingerprint(String.valueOf(map.get("rawIndexDigest")), String.valueOf(map.get("indexTreeId")),
                    (List<String>) map.get("stagedEntryPaths"), (Map<String, String>) map.get("stagedBlobIds"),
                    (Map<String, String>) map.get("entryModes"), (Map<String, List<String>>) map.get("entryStages"),
                    (List<String>) map.get("unmergedEntries"), (List<String>) map.get("intentToAddFlags"),
                    (List<String>) map.get("skipWorktreeFlags"), (List<String>) map.get("assumeUnchangedFlags"),
                    Boolean.parseBoolean(String.valueOf(map.get("sparseIndexMode"))),
                    Boolean.parseBoolean(String.valueOf(map.get("splitIndexMode"))),
                    (List<String>) map.get("relevantExtensions"));
        }
    }

    private record Entry(String path, String mode, String blob, int stage, boolean intentToAdd) {
    }

    private record Result(int exitCode, String output) {
    }
}
