package org.synesis.link.transport;

/**
 * Internal bounded control-message allocation.
 */
enum ControlMessageType {
    CONTROL_READY(1),
    GOODBYE(2),
    GOODBYE_ACK(3),
    PROTOCOL_ERROR(4),
    /**
     * Application-level liveness heartbeat.
     */
    HEARTBEAT(5),
    /**
     * Acknowledgement of one heartbeat sequence.
     */
    HEARTBEAT_ACK(6);

    final int code;

    ControlMessageType(int code) {
        this.code = code;
    }

    static ControlMessageType fromCode(int code) throws java.io.IOException {
        for (ControlMessageType value : values()) {
            if (value.code == code) return value;
        }
        throw new java.io.IOException("unknown required control message");
    }
}
