package org.synesis.coordination.domain.identity;

/**
 * One-based position in a project append-only event log.
 *
 * @param value event sequence number
 */
public record EventSequence(long value) {

}
