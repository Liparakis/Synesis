package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.mcp.application.McpProtocolHandler;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * An agent of a scripted scenario (ADR-0102 item 1): it calls the MCP tools of one computer's project the way a model
 * does, through the same protocol handler a provider's MCP process runs, and follows what {@code get_next_action}
 * recommends. A scenario names what the agent does; it never writes into the log or a lane's worktree itself.
 */
final class ScenarioAgent {

    /** What one {@link #step} found. */
    enum Step {
        /** A recommended tool was called. */
        ACTED,
        /** The agent was told to wait. */
        WAITING
    }

    /** What the agent does when its next action asks for a review verdict. */
    enum Review {
        /** Records an accepted verdict at once, as a model that trusts the snapshot would. The default. */
        ACCEPT_UNREAD,
        /** Reads the snapshot's file first and gives a verdict only if the read succeeded, as a reviewer that will not judge unread code does. */
        READ_THEN_ACCEPT,
        /** Gives no verdict: the agent has stopped before it. */
        WITHHOLD
    }

    final String name;
    final Computer computer;
    private Review review = Review.ACCEPT_UNREAD;
    private String reviewedPath;
    /** Whether the agent holds a reviewer lane, whose next action offers the snapshots to review as a contract and not as a protocol step. */
    private boolean reviewerLane;
    private final McpProtocolHandler handler;
    /** The tools this agent called, with what each answered, oldest first; a failure message carries it. */
    final List<String> trace = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Every {@code get_next_action} answer this agent received, in order: what it was told, for tests that read the whole path. */
    final List<Map<String, Object>> answers = new java.util.concurrent.CopyOnWriteArrayList<>();

    ScenarioAgent(Computer computer, String name) {
        this.computer = computer;
        this.name = name;
        this.handler = new McpProtocolHandler(new AgentSessionService(), computer.location.root(), "codex", name, false, null);
    }

    /** Calls a tool and returns the tool's JSON envelope ({@code status}, {@code result}, {@code nextAction}, ...). */
    @SuppressWarnings("unchecked")
    Map<String, Object> call(String tool, Map<String, Object> arguments) {
        String frame = handler.handleMessage(ProviderJson.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))));
        Map<String, Object> rpc = (Map<String, Object>) ProviderJson.parse(frame);
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        if (result == null) {
            throw new AssertionError(name + " " + tool + " answered without a result: " + frame);
        }
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        Map<String, Object> envelope = new LinkedHashMap<>((Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text")));
        envelope.put("isError", Boolean.TRUE.equals(result.get("isError")));
        if ("get_next_action".equals(tool)) {
            answers.add(envelope);
        }
        if (!"get_next_action".equals(tool)) {
            trace.add(tool + " " + arguments.keySet() + " -> " + envelope.get("status") + " " + envelope.get("reason") + " " + envelope.get("nextAction") + " " + inner(envelope).get("reason"));
        }
        return envelope;
    }

    /** The inner {@code result} of an envelope. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> inner(Map<String, Object> envelope) {
        Object result = envelope.get("result");
        return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> workflow(Map<String, Object> envelope) {
        Object workflow = inner(envelope).get("workflow");
        return workflow instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The participant handle the log knows this agent by, or {@code null} before its session exists. */
    String participant() {
        try {
            return new ProviderSessionBindingService().find(computer.location, "codex", name).map(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId())).orElse(null);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Sets how the agent answers a request for a verdict from now on.
     *
     * @param mode       what it does
     * @param pathToRead the file of the reviewed snapshot it reads first when {@code mode} is {@link Review#READ_THEN_ACCEPT}
     */
    void reviews(Review mode, String pathToRead) {
        this.review = mode;
        this.reviewedPath = pathToRead;
    }

    Map<String, Object> next() {
        return call("get_next_action", Map.of());
    }

    /** Opens the agent's session with no task, as a fresh agent's first {@code ensure_session} does; it then holds no lane. */
    void join() {
        Map<String, Object> ensured = call("ensure_session", Map.of());
        assertFalse(Boolean.TRUE.equals(ensured.get("isError")), name + " joins: " + ensured);
    }

    /**
     * Opens a reviewer lane as a reviewing agent does: it claims the report it will publish and names what it reviews. The
     * lane must be ready.
     */
    void joinAsReviewer(String report, String target) {
        Map<String, Object> ensured = call("ensure_session", Map.of("task", Map.of("role", "reviewer", "goal", "Independently review " + target,
                "acceptance", "Inspect the producer's immutable snapshot of " + target + " and accept it if it is right", "claims", List.of(Map.of("kind", "path_exact", "path", report)),
                "reviewTargets", List.of(Map.of("kind", "path_exact", "path", target)))));
        assertEquals("ready", ensured.get("status"), name + " joins as a reviewer of " + target + ": " + ensured);
        reviewerLane = true;
    }

    /** Claims the paths with a goal, as an agent's first {@code ensure_session} does; the lane must be ready. */
    Map<String, Object> claim(String goal, String... paths) {
        Map<String, Object> ensured = tryClaim(goal, paths);
        assertEquals("ready", ensured.get("status"), name + " claims " + List.of(paths) + ": " + ensured);
        return ensured;
    }

    /** Asks to claim the paths with a goal and returns the answer, whether the lane is ready or the claim was refused. */
    Map<String, Object> tryClaim(String goal, String... paths) {
        List<Map<String, Object>> claims = java.util.Arrays.stream(paths).map(path -> Map.<String, Object>of("path", path)).toList();
        return call("ensure_session", Map.of("task", Map.of("goal", goal, "acceptance", goal + " is done", "claims", claims)));
    }

    /** Creates a file in the agent's lane worktree through {@code apply_patch}. */
    void create(String path, String content) {
        Map<String, Object> patched = call("apply_patch", Map.of("path", path, "create", true, "content", content));
        assertFalse(Boolean.TRUE.equals(patched.get("isError")), name + " creates " + path + ": " + patched);
    }

    /** Replaces text of an existing file in the lane worktree through {@code read_file} and {@code apply_patch}. */
    void edit(String path, String find, String replace) {
        Map<String, Object> read = call("read_file", Map.of("path", path));
        Object hash = inner(read).get("contentHash");
        assertTrue(hash != null, name + " reads " + path + ": " + read);
        Map<String, Object> patched = call("apply_patch",
                Map.of("path", path, "expectedHash", hash, "edits", List.of(Map.of("find", find, "replace", replace, "expectedOccurrences", 1))));
        assertFalse(Boolean.TRUE.equals(patched.get("isError")), name + " edits " + path + ": " + patched);
    }

    /** Replaces the whole text of an existing file in the lane worktree, as an agent does that resolves the conflict markers in it. */
    void rewrite(String path, String content) {
        Map<String, Object> read = call("read_file", Map.of("path", path));
        Object current = inner(read).get("content");
        Object hash = inner(read).get("contentHash");
        assertTrue(current != null && hash != null, name + " reads " + path + ": " + read);
        Map<String, Object> patched = call("apply_patch",
                Map.of("path", path, "expectedHash", hash, "edits", List.of(Map.of("find", current, "replace", content, "expectedOccurrences", 1))));
        assertFalse(Boolean.TRUE.equals(patched.get("isError")), name + " rewrites " + path + ": " + patched);
    }

    /** Asks to finish the lane ({@code finish_lane}) and returns the answer. */
    @SuppressWarnings("unchecked")
    Map<String, Object> finish() {
        Map<String, Object> projection = call("get_next_action", Map.of("completionRequested", true));
        Map<String, Object> workflow = workflow(projection);
        assertEquals("finish_lane", workflow.get("recommendedTool"), name + " finishes: " + projection);
        return call("finish_lane", (Map<String, Object>) workflow.get("arguments"));
    }

    /** Publishes the lane as a snapshot ({@code finish_lane}) and returns its ID; the snapshot then waits for review. */
    String publish() {
        Map<String, Object> published = finish();
        assertEquals("REVIEW_PENDING", inner(published).get("snapshotState"), name + " publishes: " + published);
        return String.valueOf(inner(published).get("snapshotId"));
    }

    /**
     * {@link #publish} for an agent whose computer is linked to others: an entry that arrives from another computer between
     * the agent's next action and its {@code finish_lane} makes the finish evidence stale and the answer is
     * {@code retry_required / SNAPSHOT_COMPLETION_EVIDENCE_STALE}, which sends the agent back to its next action, so this
     * does the same, a bounded number of times.
     */
    String publishWhileLinked() {
        Map<String, Object> published = Map.of();
        for (int attempt = 0; attempt < 30; attempt++) {
            published = finish();
            if (!"retry_required".equals(published.get("status")) || !"SNAPSHOT_COMPLETION_EVIDENCE_STALE".equals(inner(published).get("reason"))) {
                break;
            }
        }
        assertEquals("REVIEW_PENDING", inner(published).get("snapshotState"), name + " publishes: " + published);
        return String.valueOf(inner(published).get("snapshotId"));
    }

    /** The combines this agent's next action offers it; each holds the {@code ensureSession} arguments that take it. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> combinesOffered() {
        return inner(next()).get("combineCandidates") instanceof List<?> listed ? (List<Map<String, Object>>) listed : List.of();
    }

    /** The lanes of computers that are away which this agent's next action offers it to take over (ADR-0098 step 7, items 10 and 11). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> takeoversOffered() {
        return inner(next()).get("continuationCandidates") instanceof List<?> listed ? (List<Map<String, Object>>) listed : List.of();
    }

    /** Asks for the first lane its next action offers to take over, with the request the offer carries, and returns the answer. */
    @SuppressWarnings("unchecked")
    Map<String, Object> takeOver() {
        List<Map<String, Object>> offered = takeoversOffered();
        assertFalse(offered.isEmpty(), name + " is offered a lane to take over: " + next());
        return call("request_coordination", (Map<String, Object>) offered.getFirst().get("continuationRequest"));
    }

    /** What this agent's next action says about its lane having been taken over, or {@code null} when it says nothing. */
    @SuppressWarnings("unchecked")
    Map<String, Object> laneTakenOver() {
        return inner(next()).get("laneTakenOver") instanceof Map<?, ?> notice ? (Map<String, Object>) notice : null;
    }

    /**
     * Takes the one step {@code get_next_action} recommends, as an agent that only follows it does: answers an admission
     * asked of it, asks to review a snapshot, consumes a grant, records an accepted verdict, refreshes its session.
     */
    @SuppressWarnings("unchecked")
    Step step() {
        Map<String, Object> next = next();
        Map<String, Object> workflow = workflow(next);
        Object tool = workflow.get("recommendedTool");
        Map<String, Object> inner = inner(next);
        if (tool != null && List.of("request_coordination", "respond_coordination", "ensure_session").contains(tool) && workflow.get("arguments") instanceof Map<?, ?> arguments) {
            Map<String, Object> called = call((String) tool, (Map<String, Object>) arguments);
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        if ("review_decision".equals(inner.get("nextProtocolAction"))) {
            if (review == Review.WITHHOLD) {
                return Step.WAITING;
            }
            if (review == Review.READ_THEN_ACCEPT) {
                Map<String, Object> read = call("read_file", Map.of("path", reviewedPath));
                if (Boolean.TRUE.equals(read.get("isError")) || !"completed".equals(read.get("status"))) {
                    return Step.WAITING;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) inner.get("nextProtocolPayload"));
            payload.put("result", "accepted");
            Map<String, Object> called = call("respond_coordination", Map.of("kind", "review_validation", "payload", payload));
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        boolean choosesAReview = "request_coordination".equals(inner.get("nextProtocolAction")) || reviewerLane && "REVIEW_CONTRACT".equals(workflow.get("type"));
        if (choosesAReview && inner.get("actionableReviewCandidates") instanceof List<?> listed && !listed.isEmpty()) {
            Map<String, Object> chosen = (Map<String, Object>) listed.getFirst();
            Map<String, Object> called = call("request_coordination", (Map<String, Object>) chosen.get("requestCoordination"));
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        return Step.WAITING;
    }
}
