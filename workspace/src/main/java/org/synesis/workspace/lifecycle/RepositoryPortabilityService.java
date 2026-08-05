package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validates a complete Git tree against the declared Windows/Linux portability
 * policy.
 *
 * <p>All identities come from Git tree entries and UTF-8 bytes. The host
 * filesystem is never enumerated, so the same tree produces the same result
 * on Windows and Linux.</p>
 */
public final class RepositoryPortabilityService {

    /** Stable portability finding categories. */
    public enum FindingCode {
        /** Git could not provide the requested tree. */
        TREE_UNAVAILABLE,
        /** A tree path is not valid UTF-8. */
        INVALID_UTF8,
        /** A path uses a backslash or ambiguous separator. */
        PATH_SEPARATOR_AMBIGUITY,
        /** A path contains an invalid dot segment or leading separator. */
        PATH_SYNTAX_INVALID,
        /** Two entries differ only by case folding. */
        CASE_COLLISION,
        /** Two entries differ only by Unicode normalization. */
        UNICODE_NORMALIZATION_COLLISION,
        /** Two entries alias after Windows trailing-dot/space handling. */
        TRAILING_ALIAS_COLLISION,
        /** A path contains a Windows reserved device name. */
        WINDOWS_RESERVED_NAME,
        /** A symlink is used as a directory ancestor. */
        SYMLINK_TRAVERSAL,
        /** A submodule entry is outside the supported snapshot model. */
        UNSUPPORTED_SUBMODULE,
        /** Two entries have the same canonical Git path. */
        DUPLICATE_PATH
    }

    /** One complete-tree Git entry used for deterministic validation.
     * @param path canonical decoded Git path
     * @param mode Git tree mode
     * @param type Git tree entry type
     * @param objectId Git object identity
     */
    public record TreeEntry(String path, int mode, String type, String objectId) {
        /** Validates one decoded entry. */
        public TreeEntry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(objectId, "objectId");
        }

        /**
         * Returns whether this entry is a Git symbolic link.
         *
         * @return true for a symbolic-link tree mode or type
         */
        public boolean symbolicLink() {
            return mode == 0120000 || "symlink".equals(type);
        }

        /**
         * Returns whether this entry is a Git submodule.
         *
         * @return true for a submodule tree mode or type
         */
        public boolean submodule() {
            return mode == 0160000 || "commit".equals(type);
        }
    }

    /** One deterministic portability finding.
     * @param code finding category
     * @param paths affected complete-tree paths
     */
    public record Finding(FindingCode code, List<String> paths) {
        /** Copies and sorts finding paths. */
        public Finding {
            Objects.requireNonNull(code, "code");
            paths = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(paths, "paths")));
        }
    }

    /** Complete-tree portability report.
     * @param treeish validated Git tree identity
     * @param entries complete Git tree entries
     * @param findings deterministic policy findings
     */
    public record Report(String treeish, List<TreeEntry> entries, List<Finding> findings) {
        /** Copies entries and findings into deterministic immutable lists. */
        public Report {
            Objects.requireNonNull(treeish, "treeish");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        }

        /**
         * Returns whether the complete tree satisfies the policy.
         *
         * @return true when no portability finding exists
         */
        public boolean portable() {
            return findings.isEmpty();
        }
    }

    /** Creates a repository portability validator. */
    public RepositoryPortabilityService() {
    }

    /**
     * Validates the complete current control tree.
     *
     * @param repositoryRoot repository worktree
     * @return portability report
     * @throws IOException when Git inspection cannot be performed
     */
    public Report preflight(Path repositoryRoot) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        String head = git(repositoryRoot, "rev-parse", "--verify", "HEAD");
        return validateTree(repositoryRoot, head);
    }

    /**
     * Validates the complete resulting tree reached by a Git object.
     *
     * @param repositoryRoot repository worktree
     * @param treeish commit or tree object
     * @return portability report
     * @throws IOException when Git inspection cannot be performed
     */
    public Report validateTree(Path repositoryRoot, String treeish) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(treeish, "treeish");
        byte[] raw = gitBytes(repositoryRoot, "ls-tree", "-r", "-z", "--full-tree", treeish);
        List<TreeEntry> entries = new ArrayList<>();
        List<Finding> readFindings = new ArrayList<>();
        for (byte[] item : splitNul(raw)) {
            if (item.length == 0) {
                continue;
            }
            int tab = indexOf(item, (byte) '\t');
            if (tab < 0) {
                readFindings.add(new Finding(FindingCode.PATH_SYNTAX_INVALID, List.of("<malformed-tree-entry>")));
                continue;
            }
            String header = decodeAscii(item, 0, tab);
            String path;
            try {
                path = decodeUtf8(item, tab + 1, item.length - tab - 1);
            } catch (CharacterCodingException invalid) {
                path = "<invalid-utf8:" + hex(item, tab + 1, item.length - tab - 1) + ">";
                readFindings.add(new Finding(FindingCode.INVALID_UTF8, List.of(path)));
            }
            String[] fields = header.split(" ");
            if (fields.length != 3) {
                readFindings.add(new Finding(FindingCode.PATH_SYNTAX_INVALID, List.of(path)));
                continue;
            }
            int mode;
            try {
                mode = Integer.parseInt(fields[0], 8);
            } catch (NumberFormatException invalid) {
                readFindings.add(new Finding(FindingCode.PATH_SYNTAX_INVALID, List.of(path)));
                continue;
            }
            entries.add(new TreeEntry(path, mode, fields[1], fields[2]));
        }
        Report validated = validateEntries(treeish, entries);
        if (readFindings.isEmpty()) {
            return validated;
        }
        List<Finding> combined = new ArrayList<>(validated.findings());
        combined.addAll(readFindings);
        return new Report(treeish, entries, sortFindings(combined));
    }

    /**
     * Validates a deterministic complete-tree vector without invoking Git.
     *
     * @param treeish candidate tree identity
     * @param entries complete Git tree entries
     * @return portability report
     */
    public Report validateEntries(String treeish, List<TreeEntry> entries) {
        Objects.requireNonNull(treeish, "treeish");
        List<TreeEntry> immutable = List.copyOf(Objects.requireNonNull(entries, "entries"));
        List<Finding> findings = new ArrayList<>();
        Map<String, List<String>> exact = new LinkedHashMap<>();
        Map<String, List<String>> caseFolded = new LinkedHashMap<>();
        Map<String, List<String>> unicode = new LinkedHashMap<>();
        Map<String, List<String>> trailing = new LinkedHashMap<>();
        List<String> symlinks = new ArrayList<>();
        for (TreeEntry entry : immutable) {
            String path = entry.path();
            exact.computeIfAbsent(path, ignored -> new ArrayList<>()).add(path);
            String syntax = syntaxKey(path);
            if (syntax != null) {
                findings.add(new Finding(syntax.equals("separator")
                        ? FindingCode.PATH_SEPARATOR_AMBIGUITY : FindingCode.PATH_SYNTAX_INVALID, List.of(path)));
            }
            for (String segment : path.split("/", -1)) {
                if (isReserved(segment)) {
                    findings.add(new Finding(FindingCode.WINDOWS_RESERVED_NAME, List.of(path)));
                    break;
                }
            }
            String folded = path.toLowerCase(Locale.ROOT);
            caseFolded.computeIfAbsent(folded, ignored -> new ArrayList<>()).add(path);
            String normalized = Normalizer.normalize(path, Normalizer.Form.NFC);
            unicode.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(path);
            String windows = windowsAlias(path);
            trailing.computeIfAbsent(windows, ignored -> new ArrayList<>()).add(path);
            if (entry.submodule()) {
                findings.add(new Finding(FindingCode.UNSUPPORTED_SUBMODULE, List.of(path)));
            }
            if (entry.symbolicLink()) {
                symlinks.add(path);
            }
        }
        addCollisions(findings, exact, FindingCode.DUPLICATE_PATH);
        addCollisions(findings, caseFolded, FindingCode.CASE_COLLISION);
        addCollisions(findings, unicode, FindingCode.UNICODE_NORMALIZATION_COLLISION);
        addCollisions(findings, trailing, FindingCode.TRAILING_ALIAS_COLLISION);
        for (String path : immutable.stream().map(TreeEntry::path).toList()) {
            if (symlinks.stream().anyMatch(link -> !link.equals(path) && path.startsWith(link + "/"))) {
                findings.add(new Finding(FindingCode.SYMLINK_TRAVERSAL, List.of(path)));
            }
        }
        return new Report(treeish, immutable, sortFindings(findings));
    }

    private static void addCollisions(List<Finding> findings, Map<String, List<String>> values,
                                      FindingCode code) {
        for (List<String> paths : values.values()) {
            if (paths.size() > 1) {
                List<String> sorted = paths.stream().sorted().toList();
                findings.add(new Finding(code, sorted));
            }
        }
    }

    private static List<Finding> sortFindings(List<Finding> findings) {
        return findings.stream().sorted(Comparator.comparing((Finding f) -> f.code().name())
                .thenComparing(f -> String.join("\u0000", f.paths()))).toList();
    }

    private static String syntaxKey(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("//")) {
            return path.contains("\\") ? "separator" : "syntax";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return "syntax";
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return "syntax";
            }
        }
        return null;
    }

    private static boolean isReserved(String segment) {
        String stem = segment;
        int dot = stem.indexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        String upper = stem.toUpperCase(Locale.ROOT);
        return upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
                || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]");
    }

    private static String windowsAlias(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = segments[i].replaceFirst("[ .]+$", "");
        }
        return String.join("/", segments).toLowerCase(Locale.ROOT);
    }

    private static byte[][] splitNul(byte[] bytes) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                parts.add(java.util.Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) parts.add(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        return parts.toArray(byte[][]::new);
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) if (bytes[i] == value) return i;
        return -1;
    }

    private static String decodeAscii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, offset, length)).toString();
    }

    private static String hex(byte[] bytes, int offset, int length) {
        return java.util.HexFormat.of().formatHex(bytes, offset, offset + length);
    }

    private static String git(Path root, String... args) throws IOException {
        byte[] result = gitBytes(root, args);
        return new String(result, StandardCharsets.UTF_8).trim();
    }

    private static byte[] gitBytes(Path root, String... args) throws IOException {
        return GitProcessRunner.runBytes(root, args);
    }
}
