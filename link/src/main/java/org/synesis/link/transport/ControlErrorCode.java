package org.synesis.link.transport;

/** Internal bounded protocol-error allocation. */
enum ControlErrorCode {
    MALFORMED_FRAME(1),
    OVERSIZED_FRAME(2),
    INVALID_SESSION(3),
    ILLEGAL_STATE(4),
    DUPLICATE_CONTROL_STREAM(5),
    UNSUPPORTED_MESSAGE(6),
    DUPLICATE_TERMINAL_MESSAGE(7),
    UNEXPECTED_STREAM_END(8),
    /** Heartbeat payload or sequence invariant failed. */
    INVALID_HEARTBEAT(9),
    /** A heartbeat sequence reached the non-negative wire limit. */
    HEARTBEAT_SEQUENCE_EXHAUSTED(10);

    final int code;

    ControlErrorCode(int code) { this.code = code; }
}
