package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.logSuppressed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * File-based local coordination inbox, consulted only when the durable pipeline produced nothing.
 *
 * <p>Separate from the durable projections because it reads a plain JSON inbox rather than the
 * event store, ranks items by its own type priority, and fails quiet on a malformed inbox.</p>
 */
final class LocalCoordinationFallback {

    /**
     * Project-relative location of the local, file-based coordination inbox.
     */
    private static final String LOCAL_ITEMS_FILE = ".synesis/local/coordination/items.json";

    /**
     * Application-state sub-directory inspected for durable command diagnostics.
     */
    private static final String COMMANDS_DIR = "commands";

    /**
     * Placeholder shown for owner-request fields the local item did not provide.
     */
    private static final String PLACEHOLDER_TEXT = "...";

    /**
     * Capability name reported when a local owner request carries none.
     */
    private static final String UNKNOWN_CAPABILITY = "unknown";

    /**
     * Capability-request fields a requester must supply, as reported by the local fallback.
     */
    private static final List<String> LOCAL_CAPABILITY_FIELDS = List.of("inputs", "output", "behavior", "acceptanceTest");

    private LocalCoordinationFallback() {
    }

    /**
     * Resolves the local, file-based fallback when the durable pipeline produced nothing.
     *
     * @param bindingService looks up the caller's provider-session binding for the idle response
     * @param request  the next-action request
     * @param location project location
     * @param worktree the verified assigned worktree
     * @return the idle response when there are no local items, otherwise the response for the
     *         highest-priority item
     */
    static AgentResponse resolveLocalCoordination(ProviderSessionBindingService bindingService, NextActionRequest request, ProjectLocation location, Path worktree) {
        List<CoordinationItem> items = loadCoordinationItems(worktree, location.root(), request.provider());
        if (items.isEmpty()) {
            return idleResponse(bindingService, location, request);
        }
        CoordinationItem top = items.stream().min(Comparator.comparingInt((CoordinationItem item) -> item.type().priority()).thenComparingLong(CoordinationItem::sequence)).orElseThrow();
        return localItemResponse(top, items.size());
    }

    /**
     * Builds the idle response: collaboration discovery plus the durable command diagnostics.
     *
     * @param bindingService looks up the caller's provider-session binding
     * @param location project location
     * @param request  the next-action request
     * @return a {@code READY} response without a recommended action
     */
    private static AgentResponse idleResponse(ProviderSessionBindingService bindingService, ProjectLocation location, NextActionRequest request) {
        Map<String, Object> collaboration = CollaborationView.collaborationDetailsForRequest(bindingService, location, request);
        ProjectCommandDiagnostics.Report command = ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve(COMMANDS_DIR));
        collaboration.put("durableCommands", command.nextActionDetails());
        return new AgentResponse(AgentStatus.READY, null, null, collaboration);
    }

    /**
     * Reads the local coordination items addressed to the target worker.
     *
     * <p>The assigned worktree's inbox takes precedence over the project-level inbox. A malformed
     * file or entry field of the wrong type discards <em>all</em> items (a deliberate fail-quiet
     * inherited from the original implementation; see {@link NextActionSupport#logSuppressed}).</p>
     *
     * @param assignedWorktree verified assigned worktree; must not be {@code null}
     * @param projectRoot      project root used when the worktree has no inbox
     * @param targetWorker     worker (provider) name the items must address, case-insensitively
     * @return the matching, non-obsolete items in file order; empty when there is no inbox or it
     *         cannot be read
     */
    private static List<CoordinationItem> loadCoordinationItems(Path assignedWorktree, Path projectRoot, String targetWorker) {
        Path itemsFile = assignedWorktree.resolve(LOCAL_ITEMS_FILE);
        if (!Files.exists(itemsFile)) {
            itemsFile = projectRoot.resolve(LOCAL_ITEMS_FILE);
        }
        if (!Files.exists(itemsFile)) {
            return List.of();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(itemsFile));
            if (!(parsed instanceof List<?> entries)) {
                return List.of();
            }
            List<CoordinationItem> items = new ArrayList<>();
            long sequence = 0;
            for (Object entry : entries) {
                sequence++;
                if (entry instanceof Map<?, ?> fields) {
                    toCoordinationItem(fields, targetWorker, sequence).ifPresent(items::add);
                }
            }
            return List.copyOf(items);
        } catch (Exception failure) {
            logSuppressed("local coordination inbox", failure);
            return List.of();
        }
    }

    /**
     * Converts one raw inbox entry into a {@link CoordinationItem}.
     *
     * @param fields       the raw JSON object
     * @param targetWorker worker (provider) name the item must address
     * @param sequence     one-based position of the entry in the inbox, used for stable ordering
     * @return the item, or empty when it addresses another worker, is obsolete or completed, or
     *         has no type
     * @throws ClassCastException if {@code workerId}, {@code type}, {@code capability} or
     *                            {@code details} has an unexpected JSON type
     */
    @SuppressWarnings("unchecked")
    private static Optional<CoordinationItem> toCoordinationItem(Map<?, ?> fields, String targetWorker, long sequence) {
        String worker = (String) fields.get("workerId");
        boolean addressedToTarget = worker == null || worker.isBlank() || worker.equalsIgnoreCase(targetWorker);
        if (!addressedToTarget) {
            return Optional.empty();
        }
        boolean obsolete = Boolean.TRUE.equals(fields.get("obsolete")) || Boolean.TRUE.equals(fields.get("completed"));
        if (obsolete) {
            return Optional.empty();
        }
        String type = (String) fields.get("type");
        String capability = (String) fields.get("capability");
        Map<String, Object> details = (Map<String, Object>) fields.get("details");
        if (details == null) {
            details = Map.of();
        }
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(new CoordinationItem(CoordinationItemType.parse(type), capability, details, sequence));
    }

    /**
     * Maps the winning local item to its agent response.
     *
     * @param top          the highest-priority item
     * @param pendingCount total number of local items
     * @return the response for the item's type
     */
    private static AgentResponse localItemResponse(CoordinationItem top, int pendingCount) {
        return switch (top.type()) {
            case SAFETY_FAILURE -> new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case DEPENDENCY_INVALIDATED -> {
                Map<String, Object> result = new LinkedHashMap<>();
                if (top.capability() != null) {
                    result.put(NextActionKeys.CAPABILITY, top.capability());
                }
                result.put(NextActionKeys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.DEPENDENCY_INVALIDATED, AgentNextAction.RETRY, result);
            }
            case OWNER_REQUEST -> {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put(NextActionKeys.CAPABILITY, top.capability() != null ? top.capability() : UNKNOWN_CAPABILITY);
                request.put("inputs", top.details().getOrDefault("inputs", PLACEHOLDER_TEXT));
                request.put("output", top.details().getOrDefault("output", PLACEHOLDER_TEXT));
                request.put("behavior", top.details().getOrDefault("behavior", PLACEHOLDER_TEXT));
                request.put("acceptanceTest", top.details().getOrDefault("acceptanceTest", PLACEHOLDER_TEXT));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", request);
                result.put(NextActionKeys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_REQUEST_PENDING, null, result);
            }
            case NEEDS_CAPABILITY -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(NextActionKeys.CAPABILITY, top.capability());
                result.put(NextActionKeys.REQUIRED_FIELDS, LOCAL_CAPABILITY_FIELDS);
                result.put(NextActionKeys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
            }
            case VALIDATION_REQUIRED -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(NextActionKeys.CAPABILITY, top.capability());
                result.put(NextActionKeys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, null, result);
            }
            case WAITING_FOR_OWNER -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(NextActionKeys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }
            case UNKNOWN -> AgentResponse.ready(CollaborationView.ISOLATED, pendingCount);
        };
    }

    /**
     * Represents a pending item from the local coordination inbox.
     *
     * @param type       item type, which determines both its priority and its response
     * @param capability capability identifier, or {@code null}
     * @param details    additional detail payload; never {@code null}
     * @param sequence   one-based position in the inbox, used for deterministic ordering
     */
    private record CoordinationItem(CoordinationItemType type, String capability, Map<String, Object> details, long sequence) {

    }

    /**
     * Types of local coordination items, declared in descending priority.
     */
    private enum CoordinationItemType {

        /**
         * A safety check failed; a human must intervene.
         */
        SAFETY_FAILURE(1),

        /**
         * A dependency changed underneath the worker, which must retry.
         */
        DEPENDENCY_INVALIDATED(2),

        /**
         * The worker owns a capability request it has to answer.
         */
        OWNER_REQUEST(3),

        /**
         * The worker must describe a capability it needs.
         */
        NEEDS_CAPABILITY(4),

        /**
         * A delivered capability awaits validation.
         */
        VALIDATION_REQUIRED(5),

        /**
         * The worker waits for a capability owner to respond.
         */
        WAITING_FOR_OWNER(6),

        /**
         * Any type this service does not recognize; ranked last and treated as idle.
         */
        UNKNOWN(99);

        /**
         * Sort key; lower values are served first.
         */
        private final int priority;

        /**
         * Creates a type with its priority.
         *
         * @param priority sort key; lower values are served first
         */
        CoordinationItemType(int priority) {
            this.priority = priority;
        }

        /**
         * Returns the sort key of this type.
         *
         * @return the priority; lower values are served first
         */
        int priority() {
            return priority;
        }

        /**
         * Parses an inbox type name case-insensitively.
         *
         * @param raw the raw type name, possibly {@code null}
         * @return the matching type, or {@link #UNKNOWN} when {@code raw} is {@code null} or
         *         unrecognized
         */
        static CoordinationItemType parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            String normalized = raw.toUpperCase(Locale.ROOT);
            for (CoordinationItemType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }
}
