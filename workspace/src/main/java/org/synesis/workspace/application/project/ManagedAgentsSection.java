package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.synesis.workspace.infrastructure.filesystem.AtomicFiles;

/**
 * Owns the Synesis-managed section of the project root {@code AGENTS.md}.
 *
 * <p>Only the text between the begin and end markers belongs to Synesis; all other text is
 * preserved, and the line separator already used by the file is kept.</p>
 */
public final class ManagedAgentsSection {

    /**
     * Name of the file carrying the managed section.
     */
    public static final String FILE_NAME = "AGENTS.md";

    private static final String BEGIN = "<!-- SYNESIS-BEGIN -->";
    private static final String END = "<!-- SYNESIS-END -->";
    private static final String BODY = """
            ## Synesis

            This repository uses Synesis.

            - Use Synesis tools for project reads, file changes, and commands.
            - One persistent MCP connection owns one provider binding and one isolated worker context.
            - Before the first MCP session after `synesis init`, install the integration matching the responding harness in the control project directory: Claude Code uses `synesis provider install claude`, and Codex uses `synesis provider install codex`. The matching `claude` or `codex` executable must be installed and available to that harness. Once `ensure_session` is ready, continue with that provider binding; do not rerun provider installation from the assigned isolated worktree, which may not contain the control project profile.
            - After synesis.ensure_session succeeds, keep the provider in the current project directory and use Synesis MCP for all reads, writes, and commands. Synesis applies those operations internally in the assigned worktree; do not switch branches, cd, or edit another worker's worktree manually. Native provider hooks are optional and may be unavailable in desktop harnesses, but they do not replace the required Synesis integration, so never assume a native mutation was routed. If a native tool is used and Synesis reports workspace_mismatch, stop native mutations and verify state through synesis.read_file.
            - Reads carry revisions; provide the matching revision when applying a patch.
            - Do not modify the control checkout or another worker's files directly.
            - When Synesis reports an identity, ownership, freshness, or workspace failure, stop mutation and inspect read-only state.
            - The MCP surface currently contains exactly 10 tools; follow get_next_action's recommended tool and typed arguments.
            - A ready session is not yet an announced collaboration intent. Before mutating visible task files, call ensure_session with task.goal, task.acceptance, and task.claims containing the exact repository-relative files or subtrees assigned to this responsibility. Use claim kind path_exact or path_subtree, keep claims disjoint, and do not guess or overlap ownership. This is the existing intent/claim announcement; there is no separate announce tool. likelyScopes is descriptive only and does not acquire ownership.
            - If ensure_session returns overlapping_claim, stop attempting to claim or mutate that path. Use the conflict's exact ownerAuthorityLineageId, establish a new intent on a disjoint consumer-file claim, and declare the capability identifier in task.knownDependencies. Submit request_coordination kind capability_request with a complete API contract. If the owner revises it, explicitly accept or counter the exact contractRevision from get_next_action before building against it. Both lanes may implement after acceptance; integration waits for the owner implementation, requester validation, and the owner's implementation snapshot.
            - Call get_next_action with no arguments to read the durable coordination inbox. Its optional integrationCheck input only evaluates explicitly supplied compatibility facts; it never advances a lane or WorkGroup, cannot close or validate work, and must not replace empty-argument polling.
            - A mutating Synesis tool may return a required continuation. When its result contains a concrete nextAction/recommendedTool and typed arguments, execute that exact tool with those exact arguments before ending the turn or choosing another Synesis lifecycle action; the continuation is part of the durable protocol, not an optional suggestion.
            - After any completed Synesis mutation, call get_next_action again before ending the turn; a successful coordination or publication mutation may create the next durable inbox action even when the mutation result has no explicit continuation.
            - When get_next_action reports IMPLEMENT without a concrete recommendedTool and typed arguments, continue the visible assigned coding work normally; do not call finish_lane or another lifecycle tool merely because the coding appears complete. Execute lifecycle actions only when get_next_action projects the exact tool and arguments, then return to get_next_action after coding progress.
            - After implementation, tests, and acceptance criteria are complete, call get_next_action once with completionRequested:true to request the exact publication projection; do not substitute repeated empty-argument polls.
            - For repeated WAIT (including recommendedTool=get_next_action), IMPLEMENT, or blocked projections, compare consecutive workflow.actionId values, honor workflow.retryAfterMs (default 5000 ms), and stop after workflow.maxUnchangedPolls unchanged responses (default 3). Reset the count when actionId changes. At the limit, report the stalled action ID and stop polling until a fresh state-change signal or human input; an active WorkGroup alone does not justify repeated unchanged calls. This is provider guidance, not a server-enforced counter.
            - When get_next_action projects WAIT with recommendedTool=get_next_action and arguments {}, execute that continuation after workflow.retryAfterMs (default 5000 ms), subject to the unchanged-response limit above. Do not report success merely because your own lane is complete.
            """;

    private ManagedAgentsSection() {
    }

    /**
     * Returns the {@code AGENTS.md} content with the managed section created or replaced,
     * preserving all unrelated text.
     *
     * @param root project root
     * @return full file content
     * @throws IOException if the file cannot be read, is a symbolic link, or its markers are malformed
     */
    public static String merged(Path root) throws IOException {
        Path agents = root.resolve(FILE_NAME);
        if (Files.isSymbolicLink(agents)) {
            throw new IOException("AGENTS.md must not be a symbolic link");
        }
        String existing = Files.exists(agents) ? Files.readString(agents, StandardCharsets.UTF_8) : "";
        String separator = existing.contains("\r\n") ? "\r\n" : "\n";
        int begin = existing.indexOf(BEGIN);
        int end = existing.indexOf(END);
        if ((begin < 0) != (end < 0) || count(existing, BEGIN) > 1 || count(existing, END) > 1 || (begin >= 0 && end < begin)) {
            throw new IOException("AGENTS.md contains malformed Synesis markers");
        }

        String section = BEGIN + separator + BODY.replace("\n", separator) + END;
        if (begin >= 0) {
            return existing.substring(0, begin) + section + existing.substring(end + END.length());
        }
        if (existing.isEmpty()) {
            return section + separator;
        }
        String prefix = existing.endsWith("\n") ? separator : separator + separator;
        return existing + prefix + section + separator;
    }

    /**
     * Writes the merged content when it differs from the file, replacing the file atomically.
     *
     * @param root project root
     * @throws IOException if the file cannot be read or written, or its markers are malformed
     */
    public static void ensure(Path root) throws IOException {
        Path agents = root.resolve(FILE_NAME);
        String existing = Files.exists(agents) ? Files.readString(agents, StandardCharsets.UTF_8) : "";
        String content = merged(root);
        if (!content.equals(existing)) {
            Path temporary = agents.resolveSibling(agents.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            AtomicFiles.replaceFromTemporary(temporary, agents);
        }
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int index = value.indexOf(token); index >= 0; index = value.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }
}
