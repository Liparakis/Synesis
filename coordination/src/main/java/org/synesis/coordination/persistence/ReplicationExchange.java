package org.synesis.coordination.persistence;

import java.io.IOException;

/**
 * One request and one answer of the replication protocol ({@link ReplicationMessage}) or of Code over Link
 * ({@link CodeTransferMessage}) carried by some transport: Link between computers, loopback HTTP between a process
 * and its computer's coordination server.
 *
 * <p>The contract on failure is what lets the sender tell "never sent" from "sent, no answer": a
 * {@link java.net.ConnectException} means the request did not leave, any other {@link IOException} means it may
 * have reached the other side.</p>
 */
@FunctionalInterface
public interface ReplicationExchange {

    /**
     * Sends one encoded request and waits for the encoded answer.
     *
     * @param request an encoded {@link ReplicationMessage}
     * @return the encoded answer
     * @throws java.net.ConnectException when nothing was sent
     * @throws IOException               when the request may have been delivered but no answer came
     */
    byte[] exchange(byte[] request) throws IOException;
}
