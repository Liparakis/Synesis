package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.application.agent.run.AgentRunner;
import org.synesis.workspace.application.agent.run.AgentTaskInbox;

/**
 * What a person does about agents from the page: start them, stop what the page started, and give them a task
 * (ADR-0103, stage 4 amendment).
 *
 * <p>The authority is the one every command of this control plane has: a browser session opened with the terminal
 * bootstrap token, loopback only, with the CSRF header. On top of that the request can name only a provider the project
 * has installed, a model and an effort, and a task; it cannot name a command line, a directory or an environment. The
 * agents are started by the {@link AgentRunner} that {@code synesis agents run} uses, and a stop reaches only the runs
 * that runner started. A task is left in the {@link AgentTaskInbox}, which the coordination log never reads, so it can
 * give or replace a task and nothing else.</p>
 */
final class AgentEndpoints {

    private static final int MAX_FIELD_LENGTH = 64;
    private static final int MAX_RUN_ID_LENGTH = 128;

    private final ControlPlaneReadModel readModel;
    private final AgentRunner runner;

    AgentEndpoints(ControlPlaneReadModel readModel) {
        this.readModel = readModel;
        this.runner = new AgentRunner(readModel.providerService());
    }

    /**
     * Starts agents, with a task or to wait for one.
     *
     * @param exchange HTTP exchange
     * @param body     {@code agents}: a list of {@code provider}, optional {@code model} and {@code effort}; and either
     *                 {@code prompt} or {@code waitForTask: true}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 for a malformed body, 409 when a provider is not installed or too many runs are live
     */
    void start(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        boolean wait = Boolean.TRUE.equals(body.get("waitForTask"));
        String prompt = body.get("prompt") instanceof String text && !text.isBlank() ? text : null;
        if (wait == (prompt != null)) {
            throw failure(400, "INVALID_REQUEST", "give a prompt, or waitForTask true, not both and not neither");
        }
        if (prompt != null && prompt.length() > AgentTaskInbox.MAX_TASK_CHARS) {
            throw failure(400, "INVALID_REQUEST", "prompt is too long");
        }
        if (!(body.get("agents") instanceof List<?> requested)) {
            throw failure(400, "INVALID_REQUEST", "agents is missing or invalid");
        }
        List<String> specifications = new ArrayList<>();
        for (Object entry : requested) {
            if (!(entry instanceof Map<?, ?> agent)) {
                throw failure(400, "INVALID_REQUEST", "each agent is an object with a provider");
            }
            specifications.add(specification(agent.get("provider"), agent.get("model"), agent.get("effort")));
        }
        Map<String, Object> response = newResponse();
        try {
            response.put("runId", runner.start(readModel.location(), specifications, prompt));
        } catch (AgentRunner.Refusal refused) {
            throw failure("PROVIDER_NOT_INSTALLED".equals(refused.code()) || "AGENT_RUN_LIMIT".equals(refused.code()) ? 409 : 400, refused.code(), refused.getMessage() == null ? "the agents were not started" : refused.getMessage());
        }
        response.put("state", wait ? "READY" : "RUNNING");
        sendJson(exchange, 200, response);
    }

    /**
     * Stops a run the page started.
     *
     * @param exchange HTTP exchange
     * @param body     the {@code runId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 for a malformed body, 404 when this runtime started no such run
     */
    void stop(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String runId = requiredText(body, "runId", MAX_RUN_ID_LENGTH);
        if (!runner.stop(runId)) {
            throw failure(404, "AGENT_RUN_NOT_FOUND", "this runtime started no such run");
        }
        Map<String, Object> response = newResponse();
        response.put("runId", runId);
        response.put("state", "STOPPING");
        sendJson(exchange, 200, response);
    }

    /**
     * Gives the agents of the project that wait for a task, or work on one, their task.
     *
     * @param exchange HTTP exchange
     * @param body     the {@code text}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 when the text is blank or too long, 500 when it cannot be kept
     */
    void sendTask(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String text = requiredText(body, "text", AgentTaskInbox.MAX_TASK_CHARS);
        Map<String, Object> response = newResponse();
        try {
            response.put("taskId", new AgentTaskInbox(readModel.location()).send(text));
        } catch (IOException failed) {
            throw failure(500, "AGENT_TASK_FAILED", "the task could not be kept");
        }
        response.put("state", "SENT");
        sendJson(exchange, 200, response);
    }

    /** The runs this runtime started, for the snapshot. */
    List<Map<String, Object>> runs() {
        return runner.runs();
    }

    void close() {
        runner.close();
    }

    /** Builds {@code provider[:model[:effort]]} from the fields; a ':' inside one would move the others, so it is refused. */
    private static String specification(Object provider, Object model, Object effort) throws ApiFailure {
        String id = field(provider, "provider", true);
        String modelText = field(model, "model", false);
        String effortText = field(effort, "effort", false);
        return effortText != null ? id + ":" + (modelText == null ? "" : modelText) + ":" + effortText : modelText != null ? id + ":" + modelText : id;
    }

    private static String field(Object value, String name, boolean required) throws ApiFailure {
        if (!required && (value == null || value instanceof String blank && blank.isBlank())) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_FIELD_LENGTH || text.indexOf(':') >= 0) {
            throw failure(400, "INVALID_REQUEST", name + " is missing or invalid");
        }
        return text;
    }
}
