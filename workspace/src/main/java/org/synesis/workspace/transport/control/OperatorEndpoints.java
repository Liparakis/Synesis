package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Optional;
import org.synesis.workspace.application.conversation.ConversationService;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * What a person does about an operator request from the page: look again, or close one an agent put to them
 * (ADR-0093, ADR-0103 stage 3). Each command runs the service {@code synesis operator recheck} and {@code synesis
 * operator close} run, with the identity of this computer's profile, so the page adds no way to answer a request the
 * command line does not have, and the rule about requests Synesis raised itself stays in that service.
 */
final class OperatorEndpoints {

    private static final int MAX_REQUEST_LENGTH = 256;
    private static final int MAX_BECAUSE_LENGTH = 1_024;
    private static final String DEFAULT_BECAUSE = "Closed by the operator in the browser.";

    private final ControlPlaneReadModel readModel;

    OperatorEndpoints(ControlPlaneReadModel readModel) {
        this.readModel = readModel;
    }

    /**
     * Runs one integration pass for the operator, which is also the one pass that runs the project's validation
     * command again, and reports the requests still open.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  500 when the pass could not run
     */
    void recheck(HttpExchange exchange) throws IOException, ApiFailure {
        ProjectLocation location = readModel.location();
        Map<String, Object> response = newResponse();
        try {
            AgentResponse pass = new IntegrationOrchestrationService().orchestrateIntegration(location.root(), location.openCoordinationStore(), location.loadOrCreateIdentity(), true);
            response.put("state", pass.status().value());
            response.put("reason", pass.reason() == null ? null : pass.reason().value());
            response.put("failed", pass.status() == AgentStatus.FAILED);
            response.put("openRequests", location.openCoordinationStore().operatorRequestProjection().open().size());
        } catch (IOException | GeneralSecurityException | RuntimeException failed) {
            throw failure(500, "OPERATOR_RECHECK_FAILED", "the operator requests could not be checked again");
        }
        sendJson(exchange, 200, response);
    }

    /**
     * Closes a request an agent put to the operator. A request Synesis raised closes when its condition no longer holds,
     * so closing it is refused and the person is pointed at a recheck.
     *
     * @param exchange HTTP exchange
     * @param body     the {@code requestId} and, optionally, {@code because}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 for a malformed body, 409 when the request cannot be closed, 500 when the log failed
     */
    void close(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String id = requiredText(body, "requestId", MAX_REQUEST_LENGTH);
        String because = body.get("because") instanceof String text && !text.isBlank() && text.length() <= MAX_BECAUSE_LENGTH ? text : DEFAULT_BECAUSE;
        try {
            Optional<String> refusal = new ConversationService().closeOperatorRequest(readModel.location(), id, because);
            if (refusal.isPresent()) {
                throw failure(409, "OPERATOR_REQUEST_NOT_CLOSED", refusal.get());
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failed) {
            throw failure(500, "OPERATOR_CLOSE_FAILED", "the operator request could not be closed");
        }
        Map<String, Object> response = newResponse();
        response.put("requestId", id);
        response.put("state", "CLOSED");
        sendJson(exchange, 200, response);
    }
}
