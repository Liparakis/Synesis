package org.synesis.workspace.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.synesis.workspace.integration.antigravity.AntigravityHookAdapter;
import org.synesis.workspace.integration.claude.ClaudeCodeHookAdapter;

/**
 * Adapts provider hook streams to structured provider results.
 */
public final class HookApplicationService {

    /**
     * Creates the service.
     */
    public HookApplicationService() {
    }

    /**
     * Processes one Claude Code hook event.
     *
     * @param profile local profile directory
     * @param input   provider event stream
     * @return structured hook result
     */
    public HookExecutionResult claudeCode(Path profile, InputStream input) {
        ClaudeCodeHookAdapter.Result result = new ClaudeCodeHookAdapter(profile).processStream(input);
        return new HookExecutionResult(result.outcome()
                .name(), result.responseJson(), result.humanReason());
    }

    /**
     * Processes one Antigravity hook event.
     *
     * @param profile local profile directory
     * @param input   provider event stream
     * @return structured hook result
     */
    public HookExecutionResult antigravity(Path profile, InputStream input) {
        AntigravityHookAdapter.Result result = new AntigravityHookAdapter(profile).processStream(input);
        return new HookExecutionResult(result.outcome()
                .name(), result.responseJson(), result.humanReason());
    }

    /**
     * Structured provider-hook execution result.
     *
     * @param outcome      adapter outcome
     * @param responseJson provider response JSON
     * @param humanReason  optional diagnostic
     */
    public record HookExecutionResult(String outcome, String responseJson, String humanReason) {

        /**
         * Validates the result.
         */
        public HookExecutionResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(responseJson, "response JSON");
        }
    }
}
