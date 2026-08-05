package org.synesis.workspace.lifecycle.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/** Immutable identity of one exact MCP process bound to one physical scope.
 * @param anchorId versioned anchor locator
 * @param scopeLocator verified physical-worktree scope locator
 * @param processIdentity exact process evidence
 * @param createdAtEpochMillis anchor creation epoch milliseconds
 */
public record ProjectCommandProcessAnchor(
        String anchorId,
        String scopeLocator,
        SessionProcessIdentity processIdentity,
        long createdAtEpochMillis
) {

    /** Validates anchor identity and process evidence. */
    public ProjectCommandProcessAnchor {
        Objects.requireNonNull(anchorId, "anchorId");
        Objects.requireNonNull(scopeLocator, "scopeLocator");
        Objects.requireNonNull(processIdentity, "processIdentity");
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
    }

    /** Creates an anchor from captured process evidence.
     * @param scopeLocator physical scope locator
     * @param processIdentity process evidence
     * @param createdAtEpochMillis creation epoch milliseconds
     * @return immutable process anchor
     */
    public static ProjectCommandProcessAnchor capture(String scopeLocator,
            SessionProcessIdentity processIdentity, long createdAtEpochMillis) {
        Objects.requireNonNull(scopeLocator, "scopeLocator");
        Objects.requireNonNull(processIdentity, "processIdentity");
        String material = scopeLocator + "\n" + processIdentity.pid() + "\n"
                + processIdentity.executableIdentity() + "\n"
                + processIdentity.commandLine() + "\n"
                + processIdentity.processStartTime() + "\n"
                + processIdentity.connectionNonce() + "\n" + createdAtEpochMillis;
        try {
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return new ProjectCommandProcessAnchor("v1-" + digest, scopeLocator,
                    processIdentity, createdAtEpochMillis);
        } catch (Exception failure) {
            throw new IllegalStateException("command anchor hash unavailable", failure);
        }
    }

    /** Captures one fresh anchor for the current MCP process.
     * @param scopeLocator physical scope locator
     * @return fresh process anchor
     */
    public static ProjectCommandProcessAnchor fresh(String scopeLocator) {
        long now = Instant.now().toEpochMilli();
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse("unknown");
        String commandLine = info.commandLine().orElse(executable);
        long start = info.startInstant().map(Instant::toEpochMilli).orElse(now);
        SessionProcessIdentity identity = new SessionProcessIdentity(
                ProcessHandle.current().pid(), executable, commandLine, start, UUID.randomUUID().toString());
        return capture(scopeLocator, identity, now);
    }
}
