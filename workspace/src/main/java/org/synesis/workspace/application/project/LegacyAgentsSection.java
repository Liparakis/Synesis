package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.synesis.workspace.infrastructure.filesystem.AtomicFiles;

/**
 * Removes the section earlier versions of Synesis kept in the project root {@code AGENTS.md}.
 *
 * <p>Synesis writes nothing into {@code AGENTS.md} any more: the agent instructions live in the
 * installed {@code synesis-provider-manual} skill, the one place for them (ADR-0103 decision 9).
 * Only the text between the begin and end markers was ever Synesis's; the rest of the file is the
 * user's and is kept, with the line separator the file already uses. A file that holds nothing else
 * is deleted. Anything this class cannot attribute with certainty (a symbolic link, markers that do
 * not pair up) is left exactly as it is, and the section stays for the user to remove.</p>
 */
public final class LegacyAgentsSection {

    /**
     * Name of the file that carried the section.
     */
    public static final String FILE_NAME = "AGENTS.md";

    private static final String BEGIN = "<!-- SYNESIS-BEGIN -->";
    private static final String END = "<!-- SYNESIS-END -->";

    private LegacyAgentsSection() {
    }

    /**
     * Removes the managed section from the project's {@code AGENTS.md}, keeping the user's text.
     *
     * <p>Idempotent: a file without the section is not touched. The file is replaced atomically, so
     * an interrupted run leaves either the old or the new content.</p>
     *
     * @param root project root
     * @return {@code true} when the file was changed or deleted
     * @throws IOException if the file cannot be read or written
     */
    public static boolean retire(Path root) throws IOException {
        Path agents = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(agents, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String existing = Files.readString(agents, StandardCharsets.UTF_8);
        int begin = existing.indexOf(BEGIN);
        int end = existing.indexOf(END);
        if (begin < 0 || end < begin || existing.indexOf(BEGIN, begin + BEGIN.length()) >= 0 || existing.indexOf(END, end + END.length()) >= 0) {
            return false;
        }
        String separator = existing.contains("\r\n") ? "\r\n" : "\n";
        String before = stripTrailingBreaks(existing.substring(0, begin));
        String after = stripLeadingBreaks(existing.substring(end + END.length()));
        if (before.isBlank() && after.isBlank()) {
            Files.delete(agents);
            return true;
        }
        String content = before.isBlank() ? after : after.isBlank() ? before + separator : before + separator + separator + after;
        Path temporary = agents.resolveSibling(agents.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        AtomicFiles.replaceFromTemporary(temporary, agents);
        return true;
    }

    private static String stripTrailingBreaks(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripLeadingBreaks(String value) {
        int start = 0;
        while (start < value.length() && (value.charAt(start) == '\n' || value.charAt(start) == '\r')) {
            start++;
        }
        return value.substring(start);
    }
}
