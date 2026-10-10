package org.synesis.workspace.lifecycle.codex;

import org.synesis.workspace.lifecycle.codex.protocol.CodexAppServerProtocolClient;
import org.synesis.workspace.lifecycle.codex.state.CodexEvidenceJournal;
import org.synesis.workspace.lifecycle.codex.state.CodexLifecycleStateStore;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.diagnostic;
import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.value;

/**
 * Routes App Server protocol events into the owning lifecycle service's state machine.
 */
final class CodexAppServerEventListener implements CodexAppServerProtocolClient.Listener {

    private final CodexAppServerLifecycleService owner;
    private final CompletableFuture<String> threadStarted;
    private final CompletableFuture<String> turnStarted;
    private final CodexEvidenceJournal journal;
    private final CodexAppServerLifecycleService.AppServerProcess process;
    private final long attachmentGeneration;
    private final long connectionGeneration;

    private void moveTo(CodexLifecycleStateStore.Checkpoint base, CodexLifecycleStateStore.State state, long rootPid, String diagnostic) throws IOException {
        owner.transition(CodexAppServerLifecycleService.onProcess(base.advance(state, diagnostic).attachedTo(attachmentGeneration, connectionGeneration), process, rootPid));
    }

    CodexAppServerEventListener(CodexAppServerLifecycleService owner, CompletableFuture<String> threadStarted, CompletableFuture<String> turnStarted, CodexEvidenceJournal journal,
            CodexAppServerLifecycleService.AppServerProcess process, long attachmentGeneration, long connectionGeneration) {
        this.owner = owner;
        this.threadStarted = threadStarted;
        this.turnStarted = turnStarted;
        this.journal = journal;
        this.process = process;
        this.attachmentGeneration = attachmentGeneration;
        this.connectionGeneration = connectionGeneration;
    }

    @Override
    public void onEvent(String method, Map<String, Object> params) {
        try {
            switch (method) {
                case "error" -> throw new IOException("codex_server_error");
                case "thread/started", "thread/resumed" -> {
                    String id = value(params, "threadId", "thread_id");
                    if (id != null) {
                        threadStarted.complete(id);
                        CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                        if (current.threadId() != null && !current.threadId().equals(id)) {
                            throw new IOException("thread event identity mismatch");
                        }
                        moveTo(current.withThread(id, current.turnId()), CodexLifecycleStateStore.State.IDLE, process.process().pid(), null);
                    }
                    journal.offer("thread_started", Map.of("threadId", id == null ? "" : id), true);
                }
                case "turn/started" -> {
                    String id = value(params, "turnId", "turn_id");
                    String eventThread = value(params, "threadId", "thread_id");
                    CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                    if (eventThread != null && current.threadId() != null && !current.threadId().equals(eventThread)) {
                        throw new IOException("turn event thread identity mismatch");
                    }
                    if (id != null) {
                        turnStarted.complete(id);
                        moveTo(current.withThread(current.threadId(), id), CodexLifecycleStateStore.State.RUNNING, process.process().pid(), null);
                    }
                    journal.offer("turn_started", Map.of("turnId", id == null ? "" : id), true);
                }
                case "turn/completed" -> {
                    String status = value(params, "status", "turnStatus");
                    CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                    String eventThread = value(params, "threadId", "thread_id");
                    String eventTurn = value(params, "turnId", "turn_id");
                    if (eventThread != null && current.threadId() != null && !current.threadId().equals(eventThread)) {
                        throw new IOException("completed event thread identity mismatch");
                    }
                    if (eventTurn != null && current.turnId() != null && !current.turnId().equals(eventTurn)) {
                        throw new IOException("completed event turn identity mismatch");
                    }
                    CodexLifecycleStateStore.State next = "interrupted".equalsIgnoreCase(status) ? CodexLifecycleStateStore.State.INTERRUPTED : CodexLifecycleStateStore.State.COMPLETED;
                    moveTo(current, next, process.process().pid(), null);
                    if (next == CodexLifecycleStateStore.State.COMPLETED) {
                        owner.providerTurnCompleted(attachmentGeneration, current.threadId(), current.turnId());
                    }
                    journal.offer("turn_completed", Map.of("turnId", current.turnId() == null ? "" : current.turnId(), "status", status == null ? "" : status), true);
                }
                case "thread/closed" -> {
                    CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                    moveTo(current, CodexLifecycleStateStore.State.STOPPED, -1L, null);
                }
                case "thread/status/changed" -> {
                    String status = value(params, "status", "threadStatus");
                    String eventThread = value(params, "threadId", "thread_id");
                    CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                    if (eventThread != null && current.threadId() != null && !current.threadId().equals(eventThread)) {
                        throw new IOException("status event thread identity mismatch");
                    }
                    if ("interaction_required".equalsIgnoreCase(status) || "interactionRequired".equalsIgnoreCase(status)) {
                        moveTo(current, CodexLifecycleStateStore.State.INTERACTION_REQUIRED, process.process().pid(), "interaction_required");
                    } else if ("idle".equalsIgnoreCase(status)) {
                        moveTo(current, CodexLifecycleStateStore.State.IDLE, process.process().pid(), null);
                    } else if ("active".equalsIgnoreCase(status)) {
                        moveTo(current, CodexLifecycleStateStore.State.RUNNING, process.process().pid(), null);
                    } else if ("systemError".equalsIgnoreCase(status)) {
                        moveTo(current, CodexLifecycleStateStore.State.FAILED, process.process().pid(), "system_error");
                    }
                    journal.offer("thread_status_changed", params, false);
                }
                case "auth/required", "interaction_required" -> {
                    CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
                    String eventThread = value(params, "threadId", "thread_id");
                    if (eventThread != null && current.threadId() != null && !current.threadId().equals(eventThread)) {
                        throw new IOException("interaction event thread identity mismatch");
                    }
                    moveTo(current, CodexLifecycleStateStore.State.INTERACTION_REQUIRED, process.process().pid(), method);
                    journal.offer("interaction_required", params, true);
                }
                // This notification describes a Codex-managed child process, not the App Server root.
                // Root exit is authoritative only from the owned ProcessHandle monitor.
                case "process/exited" -> journal.offer("process_child_exit", params, true);
                default -> {
                    // Visible messages and deltas are retained by the protocol event evidence path.
                }
            }
        } catch (Exception failure) {
            onFailure(failure);
        }
    }

    @Override
    public void onFailure(Throwable failure) {
        try {
            CodexLifecycleStateStore.Checkpoint current = owner.checkpoint();
            moveTo(current, CodexLifecycleStateStore.State.FAILED, process.process().isAlive() ? process.process().pid() : -1L, diagnostic(failure));
            journal.offer("protocol_failure", Map.of("diagnostic", diagnostic(failure)), true);
            owner.cleanUpFailedAttachment(current);
        } catch (IOException ignored) {
            // The protocol failure is already surfaced through the journal.
        }
    }
}
