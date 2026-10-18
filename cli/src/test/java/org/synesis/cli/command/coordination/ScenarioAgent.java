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

    final String name;
    final Computer computer;
    private final McpProtocolHandler handler;
    /** The tools this agent called, with what each answered, oldest first; a failure message carries it. */
    final List<String> trace = new java.util.concurrent.CopyOnWriteArrayList<>();

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

    private boolean refreshes = true;

    /**
     * From now on this agent does not answer a recommended {@code ensure_session} that carries no task, which refreshes a
     * stale workspace and gives the agent a new session. A scenario uses it for an agent whose lane is already published.
     */
    ScenarioAgent keepsItsSession() {
        this.refreshes = false;
        return this;
    }

    /** The participant handle the log knows this agent by, or {@code null} before its session exists. */
    String participant() {
        try {
            return new ProviderSessionBindingService().find(computer.location, "codex", name).map(binding -> WorkspaceCollaborationService.participantHandle(binding.sessionId())).orElse(null);
        } catch (Exception unreadable) {
            return null;
        }
    }

    Map<String, Object> next() {
        return call("get_next_action", Map.of());
    }

    /** Opens the agent's session with no task, as a fresh agent's first {@code ensure_session} does; it then holds no lane. */
    void join() {
        Map<String, Object> ensured = call("ensure_session", Map.of());
        assertFalse(Boolean.TRUE.equals(ensured.get("isError")), name + " joins: " + ensured);
    }

    /** Claims the paths with a goal, as an agent's first {@code ensure_session} does; the lane must be ready. */
    Map<String, Object> claim(String goal, String... paths) {
        List<Map<String, Object>> claims = java.util.Arrays.stream(paths).map(path -> Map.<String, Object>of("path", path)).toList();
        Map<String, Object> ensured = call("ensure_session", Map.of("task", Map.of("goal", goal, "acceptance", goal + " is done", "claims", claims)));
        assertEquals("ready", ensured.get("status"), name + " claims " + List.of(paths) + ": " + ensured);
        return ensured;
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

    /** The combines this agent's next action offers it; each holds the {@code ensureSession} arguments that take it. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> combinesOffered() {
        return inner(next()).get("combineCandidates") instanceof List<?> listed ? (List<Map<String, Object>>) listed : List.of();
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
        if ("ensure_session".equals(tool) && !refreshes && workflow.get("arguments") instanceof Map<?, ?> refresh && refresh.isEmpty()) {
            return Step.WAITING;
        }
        if (tool != null && List.of("request_coordination", "respond_coordination", "ensure_session").contains(tool) && workflow.get("arguments") instanceof Map<?, ?> arguments) {
            Map<String, Object> called = call((String) tool, (Map<String, Object>) arguments);
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        if ("review_decision".equals(inner.get("nextProtocolAction"))) {
            Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) inner.get("nextProtocolPayload"));
            payload.put("result", "accepted");
            Map<String, Object> called = call("respond_coordination", Map.of("kind", "review_validation", "payload", payload));
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        if ("request_coordination".equals(inner.get("nextProtocolAction")) && inner.get("actionableReviewCandidates") instanceof List<?> listed && !listed.isEmpty()) {
            Map<String, Object> chosen = (Map<String, Object>) listed.getFirst();
            Map<String, Object> called = call("request_coordination", (Map<String, Object>) chosen.get("requestCoordination"));
            return Boolean.TRUE.equals(called.get("isError")) ? Step.WAITING : Step.ACTED;
        }
        return Step.WAITING;
    }
}
