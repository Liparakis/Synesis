package org.synesis.cli.command.link;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Option;

/**
 * Base of the commands that connect this project to another computer through the project's running runtime
 * (ADR-0098): the runtime owns the Link session, so the session stays after the command ends and is the one the
 * project's log replicates on. The command starts an operation on the runtime, prints its links as
 * {@code KEY=VALUE} lines, and waits until a new session is established or the operation ends.
 */
abstract class LinkOperationCommand implements Callable<Integer> {

    private static final long DEFAULT_POLL_MILLIS = 1_000;

    final CliRuntime runtime;
    private final RuntimeControl.Source source;
    private final long pollMillis;
    @Option(names = "--project", description = "Initialized project directory (default: the current directory).")
    private String project;
    @Option(names = "--wait-seconds", defaultValue = "300", description = "Seconds to wait for the other computer to connect; 0 returns at once (default: 300).")
    private int waitSeconds;

    LinkOperationCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis) {
        this.runtime = runtime;
        this.source = source;
        this.pollMillis = pollMillis;
    }

    LinkOperationCommand(CliRuntime runtime) {
        this(runtime, HttpRuntimeControl.daemon(), DEFAULT_POLL_MILLIS);
    }

    /**
     * Starts the operation on the runtime and prints what the person needs to give the other computer.
     *
     * @param control the runtime's control plane
     * @return the identifier of the pending operation the wait follows
     * @throws IOException               when the runtime cannot be reached
     * @throws RuntimeControl.Rejected when the runtime refuses
     */
    abstract String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected;

    /**
     * Finds the project whose runtime the operation runs on: {@code --project}, else the current directory.
     * {@code link join} overrides it for a project link, which names the project by identity.
     *
     * @return the project directory
     * @throws ProjectApplicationException when the directory holds no initialized project
     * @throws IOException                 when the project cannot be read
     * @throws RuntimeControl.Rejected     when the project cannot be found for another reason, with its code
     */
    Path projectRoot() throws ProjectApplicationException, IOException, RuntimeControl.Rejected {
        return ProjectOptions.locate(runtime, project).root();
    }

    /**
     * @return the {@code --project} directory the person gave, or {@code null}
     */
    final String projectOption() {
        return project;
    }

    @Override
    public final Integer call() {
        try {
            Path root = projectRoot();
            RuntimeControl control = source.open(root);
            // Taken before the operation starts, so that a peer that connects at once is not mistaken for an old session.
            Set<String> before = sessionIds(control);
            String operation = begin(control);
            if (waitSeconds <= 0) {
                runtime.terminal().stdout("LINK_STATE=PENDING");
                return ExitCodes.OK;
            }
            return await(control, operation, before);
        } catch (RuntimeControl.Rejected rejected) {
            LinkCommand.refuse(runtime, rejected);
            return switch (rejected.code()) {
                case "INVITE_INVALID" -> ExitCodes.INVITE_INVALID;
                case "ANSWER_INVALID" -> ExitCodes.ANSWER_INVALID;
                case "INCOMPATIBLE_VERSION" -> ExitCodes.INCOMPATIBLE_VERSION;
                default -> ExitCodes.LOCAL_CONFIGURATION;
            };
        } catch (ProjectApplicationException | IOException failure) {
            LinkCommand.refuse(runtime, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LinkCommand.refuse(runtime, interrupted);
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    private int await(RuntimeControl control, String operation, Set<String> before) throws IOException, RuntimeControl.Rejected, InterruptedException {
        long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
        while (true) {
            if (reportNewPeer(control, before)) {
                return ExitCodes.OK;
            }
            if (!pendingOperations(control).contains(operation)) {
                // It ended; a session that arrived between the two reads still counts.
                if (reportNewPeer(control, before)) {
                    return ExitCodes.OK;
                }
                reportFailure(control, operation);
                return ExitCodes.COMMAND_FAILED;
            }
            if (System.nanoTime() >= deadline) {
                runtime.terminal().stdout("LINK_STATE=WAITING");
                ErrorShape.print(runtime.terminal()::stderr, "LINK_WAIT_TIMEOUT");
                return ExitCodes.COMMAND_FAILED;
            }
            Thread.sleep(pollMillis);
        }
    }

    private boolean reportNewPeer(RuntimeControl control, Set<String> before) throws IOException, RuntimeControl.Rejected {
        for (Map<String, Object> peer : peers(control)) {
            if (Boolean.TRUE.equals(peer.get("usable")) && peer.get("sessionId") instanceof String id && !before.contains(id)) {
                runtime.terminal().stdout("LINK_STATE=CONNECTED");
                runtime.terminal().stdout("PEER_NODE_ID=" + peer.get("nodeId"));
                return true;
            }
        }
        return false;
    }

    /**
     * Says why an operation ended without a new session: the reason the runtime recorded for it, or, for one that
     * connected, that its session was already gone.
     */
    private void reportFailure(RuntimeControl control, String operation) throws IOException, RuntimeControl.Rejected {
        Map<?, ?> how = endedOperation(control, operation);
        String reason;
        String detail;
        if (how == null) {
            reason = "OPERATION_UNKNOWN";
            detail = "the project's runtime no longer knows this operation, for example because it restarted";
        } else if ("CONNECTED".equals(how.get("state"))) {
            reason = "SESSION_ENDED";
            detail = "the other computer connected, but the session ended at once";
        } else {
            reason = String.valueOf(how.get("reason"));
            detail = String.valueOf(how.get("message"));
        }
        runtime.terminal().stdout("LINK_STATE=FAILED");
        runtime.terminal().stdout("LINK_FAILURE=" + reason);
        runtime.terminal().stdout("LINK_FAILURE_DETAIL=" + detail);
        ErrorShape.print(runtime.terminal()::stderr, ErrorShape.explains(reason) ? reason : "LINK_FAILED", detail);
    }

    /**
     * @return how the runtime says the operation ended, or {@code null} while it is pending or once it is forgotten
     */
    static Map<?, ?> endedOperation(RuntimeControl control, String operation) throws IOException, RuntimeControl.Rejected {
        if (control.read("snapshot").get("onboarding") instanceof Map<?, ?> onboarding && onboarding.get("endedOperations") instanceof List<?> ended) {
            for (Object entry : ended) {
                if (entry instanceof Map<?, ?> how && operation.equals(how.get("operationId"))) {
                    return how;
                }
            }
        }
        return null;
    }

    private static Set<String> sessionIds(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        Set<String> ids = new HashSet<>();
        peers(control).forEach(peer -> ids.add(String.valueOf(peer.get("sessionId"))));
        return ids;
    }

    private static List<Map<String, Object>> peers(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        if (control.read("network").get("network") instanceof Map<?, ?> network && network.get("peers") instanceof List<?> peers) {
            return peers.stream().filter(Map.class::isInstance).map(peer -> {
                @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) peer;
                return typed;
            }).toList();
        }
        throw new IOException("runtime network view is malformed");
    }

    private static Set<String> pendingOperations(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        Set<String> ids = new HashSet<>();
        if (control.read("snapshot").get("onboarding") instanceof Map<?, ?> onboarding) {
            for (String key : List.of("pendingHosts", "pendingJoins")) {
                if (onboarding.get(key) instanceof List<?> operations) {
                    operations.stream().filter(Map.class::isInstance).forEach(operation -> ids.add(String.valueOf(((Map<?, ?>) operation).get("operationId"))));
                }
            }
        }
        return ids;
    }
}
