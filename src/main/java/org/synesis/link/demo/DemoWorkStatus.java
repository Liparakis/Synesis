package org.synesis.link.demo;

/** Fixed safe result statuses for the demo work exchange. */
public enum DemoWorkStatus {
    /** The fixed demonstration operation completed. */
    OK,
    /** The request ID was already used on this session. */
    DUPLICATE_REQUEST,
    /** The peer received an operation outside the fixed demo contract. */
    UNSUPPORTED_OPERATION,
    /** The request could not be decoded or was not legal at this boundary. */
    MALFORMED
}
