package org.synesis.workspace.transport.control;

/**
 * Immutable safe event delivered to the control plane.
 *
 * @param sequence local event sequence
 * @param type     event type
 * @param value    redacted bounded value
 */
public record Event(long sequence, String type, String value) {

}