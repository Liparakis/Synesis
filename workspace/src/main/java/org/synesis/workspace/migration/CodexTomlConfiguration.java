package org.synesis.workspace.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded, lossless editor for the Codex TOML MCP table. */
public final class CodexTomlConfiguration {

    /** Configuration inspection outcome. */
    public enum Outcome {
        /** File is absent. */ MISSING,
        /** Managed values already match. */ UP_TO_DATE,
        /** Managed values differ. */ MIGRATION_REQUIRED,
        /** TOML is malformed. */ MALFORMED,
        /** Synesis is defined more than once. */ DUPLICATE_SYNSESIS_ENTRY,
        /** TOML shape is not safely supported. */ UNSUPPORTED_SCHEMA
    }

    /** Read-only inspection result.
     * @param outcome inspection outcome
     * @param sourceHash exact source hash
     * @param unrelatedFingerprint unrelated text fingerprint
     * @param synesisFingerprint managed table fingerprint
     */
    public record Inspection(Outcome outcome, String sourceHash, String unrelatedFingerprint, String synesisFingerprint) {
        /** Validates an inspection. */
        public Inspection {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(sourceHash, "sourceHash");
            Objects.requireNonNull(unrelatedFingerprint, "unrelatedFingerprint");
            Objects.requireNonNull(synesisFingerprint, "synesisFingerprint");
        }
    }

    private CodexTomlConfiguration() {
    }

    /** Inspects the configured Codex TOML without writing it.
     * @param path config path
     * @param launcher stable launcher
     * @return inspection result
     * @throws IOException when the file cannot be read
     */
    public static Inspection inspect(Path path, Path launcher) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) return new Inspection(Outcome.MISSING, "", "", "");
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        Parsed parsed = parse(text);
        if (parsed.outcome != Outcome.UP_TO_DATE && parsed.outcome != Outcome.MIGRATION_REQUIRED) {
            return new Inspection(parsed.outcome, sha(bytes), parsed.unrelated, parsed.synesis);
        }
        String desired = canonical(desired(launcher));
        String current = canonical(parsed.parentDirect);
        Outcome outcome = desired.equals(current) ? Outcome.UP_TO_DATE : Outcome.MIGRATION_REQUIRED;
        return new Inspection(outcome, sha(bytes), parsed.unrelated, sha(current.getBytes(StandardCharsets.UTF_8)));
    }

    /** Ensures the Synesis table exists and is current, preserving all other text.
     * @param path config path
     * @param launcher stable launcher
     * @return result after the operation
     * @throws IOException when the file cannot be safely updated
     */
    public static Inspection upsert(Path path, Path launcher) throws IOException {
        Objects.requireNonNull(path, "path");
        Inspection before = inspect(path, launcher);
        if (before.outcome() == Outcome.UP_TO_DATE) return before;
        if (before.outcome() == Outcome.MALFORMED || before.outcome() == Outcome.DUPLICATE_SYNSESIS_ENTRY
                || before.outcome() == Outcome.UNSUPPORTED_SCHEMA) return before;
        String newline = "\r\n";
        if (Files.exists(path)) {
            String text = Files.readString(path);
            newline = text.contains("\r\n") ? "\r\n" : "\n";
            Parsed parsed = parse(text);
            String updated = parsed.parentStart < 0
                    ? appendTable(text, desired(launcher), newline)
                    : replaceParent(text, parsed, desired(launcher), newline);
            atomicWrite(path, updated);
        } else {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            atomicWrite(path, desired(launcher) + newline);
        }
        return inspect(path, launcher);
    }

    /** Removes only the Synesis table, preserving nested and unrelated TOML.
     * @param path config path
     * @return true when bytes changed
     * @throws IOException when the file cannot be safely updated
     */
    public static boolean remove(Path path) throws IOException {
        if (!Files.exists(path)) return false;
        String text = Files.readString(path);
        Parsed parsed = parse(text);
        if (parsed.outcome != Outcome.UP_TO_DATE && parsed.outcome != Outcome.MIGRATION_REQUIRED) return false;
        if (parsed.parentStart < 0) return false;
        String updated = text.substring(0, parsed.parentStart) + text.substring(parsed.parentEnd);
        atomicWrite(path, updated);
        return true;
    }

    private static String appendTable(String text, String table, String nl) {
        String prefix = text.isEmpty() || text.endsWith("\n") ? text : text + nl;
        return prefix + table.replace("\n", nl) + nl;
    }

    private static String replaceParent(String text, Parsed p, String table, String nl) {
        String block = text.substring(p.parentStart, p.parentEnd);
        String header = "[mcp_servers.synesis]";
        int headerEnd = block.indexOf('\n');
        if (headerEnd < 0) headerEnd = block.length();
        String rest = block.substring(headerEnd);
        StringBuilder kept = new StringBuilder();
        String[] lines = rest.split("\\r?\\n", -1);
        for (String line : lines) {
            String key = assignmentKey(line);
            if (key != null && List.of("enabled", "command", "args", "startup_timeout_sec", "version", "url").contains(key)) continue;
            kept.append(line).append(nl);
        }
        String direct = table.substring(table.indexOf('\n') + 1);
        String suffix = kept.toString();
        return text.substring(0, p.parentStart) + header + nl + direct + nl + suffix + text.substring(p.parentEnd);
    }

    private static String desired(Path launcher) {
        String command = launcher != null && Files.isRegularFile(launcher) ? launcher.toAbsolutePath().normalize().toString() : "synesis.cmd";
        return "[mcp_servers.synesis]\n"
                + "command = '" + escapeLiteral(command) + "'\n"
                + "args = [\"mcp\", \"--provider\", \"codex\"]";
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private static Parsed parse(String text) {
        List<Section> sections = new ArrayList<>();
        String[] lines = text.split("\\r?\\n", -1);
        int offset = 0;
        int newlineLength = text.contains("\r\n") ? 2 : 1;
        boolean malformed = false;
        boolean single = false, dbl = false, esc = false;
        int square = 0;
        for (String line : lines) {
            String trimmed = stripComment(line).trim();
            boolean headerPosition = !single && !dbl && square == 0 && trimmed.startsWith("[");
            if (headerPosition) {
                if (!(trimmed.endsWith("]")) || trimmed.startsWith("[[")) malformed = true;
                else sections.add(new Section(trimmed.substring(1, trimmed.length() - 1).trim(), offset));
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '#' && !single && !dbl) break;
                if (c == '"' && !single && !esc) dbl = !dbl;
                else if (c == '\'' && !dbl) single = !single;
                else if (!single && !dbl) {
                    if (c == '[') square++;
                    if (c == ']') square--;
                    if (square < 0) malformed = true;
                }
                esc = dbl && c == '\\' && !esc;
                if (c != '\\') esc = false;
            }
            offset += line.length() + (offset + line.length() < text.length() ? newlineLength : 0);
        }
        if (single || dbl || square != 0) malformed = true;
        if (malformed) return new Parsed(Outcome.MALFORMED, -1, -1, "", "", "");
        long parentCount = sections.stream().filter(s -> s.name.equals("mcp_servers.synesis")).count();
        boolean dotted = false;
        for (String line : lines) {
            String key = assignmentKey(line);
            if (key != null && (key.equals("mcp_servers.synesis") || key.startsWith("mcp_servers.synesis."))) dotted = true;
            if (key != null && key.equals("mcp_servers")) {
                String rhs = stripComment(line).substring(line.indexOf('=') + 1);
                if (rhs.contains("synesis")) dotted = true;
            }
        }
        if (parentCount > 1 || dotted) return new Parsed(Outcome.DUPLICATE_SYNSESIS_ENTRY, -1, -1, "", "", "");
        int start = -1, end = text.length();
        for (Section s : sections) {
            if (s.name.equals("mcp_servers.synesis")) start = s.offset;
            else if (start >= 0 && s.offset > start) { end = s.offset; break; }
        }
        String parentDirect = "";
        String unrelated = text;
        if (start >= 0) {
            String block = text.substring(start, end);
            String[] blockLines = block.split("\\r?\\n", -1);
            int nested = blockLines.length;
            for (int i = 1; i < blockLines.length; i++) if (stripComment(blockLines[i]).trim().startsWith("[")) { nested = i; break; }
            StringBuilder direct = new StringBuilder("[mcp_servers.synesis]");
            int[] counts = new int[6];
            for (int i = 1; i < nested; i++) {
                String key = assignmentKey(blockLines[i]);
                if (key != null) {
                    int idx = List.of("enabled", "command", "args", "startup_timeout_sec", "version", "url").indexOf(key);
                    if (idx >= 0 && ++counts[idx] > 1) return new Parsed(Outcome.DUPLICATE_SYNSESIS_ENTRY, -1, -1, "", "", "");
                    if (key.equals("url")) return new Parsed(Outcome.DUPLICATE_SYNSESIS_ENTRY, -1, -1, "", "", "");
                }
                direct.append('\n').append(blockLines[i]);
            }
            parentDirect = direct.toString();
            unrelated = text.substring(0, start) + text.substring(end);
        }
        return new Parsed(Outcome.MIGRATION_REQUIRED, start, end, parentDirect, sha(unrelated.getBytes(StandardCharsets.UTF_8)), parentDirect);
    }

    private static String assignmentKey(String line) {
        String clean = stripComment(line).trim();
        int eq = clean.indexOf('=');
        if (eq <= 0) return null;
        String key = clean.substring(0, eq).trim();
        return key.matches("[A-Za-z0-9_.-]+") ? key : null;
    }

    private static String canonical(String block) {
        StringBuilder out = new StringBuilder();
        String[] lines = block.split("\\r?\\n");
        for (String line : lines) {
            String key = assignmentKey(line);
            if (key != null && List.of("command", "args").contains(key)) {
                int eq = stripComment(line).indexOf('=');
                out.append(key).append('=').append(stripComment(line).substring(eq + 1).trim()).append('\n');
            }
        }
        return out.toString();
    }

    private static String stripComment(String line) {
        boolean single = false, dbl = false, esc = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !single && !esc) dbl = !dbl;
            if (c == '\'' && !dbl) single = !single;
            if (c == '#' && !single && !dbl) return line.substring(0, i);
            esc = dbl && c == '\\' && !esc;
            if (c != '\\') esc = false;
        }
        return line;
    }

    private static boolean balanced(String line) {
        boolean single = false, dbl = false, esc = false;
        int square = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !single && !esc) dbl = !dbl;
            if (c == '\'' && !dbl) single = !single;
            if (!single && !dbl) { if (c == '[') square++; if (c == ']') square--; if (square < 0) return false; }
            esc = dbl && c == '\\' && !esc;
            if (c != '\\') esc = false;
        }
        return !single && !dbl && square == 0;
    }

    private record Section(String name, int offset) { }
    private record Parsed(Outcome outcome, int parentStart, int parentEnd, String parentDirect, String unrelated, String synesis) { }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void atomicWrite(Path path, String text) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
