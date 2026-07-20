package org.synesis.link.candidate;

/**
 * Direct-connectivity candidate source categories.
 *
 * @since 1.0
 */
public enum CandidateType {
    /** Address learned from a directly connected local network. */
    LAN,
    /** Globally routable IPv6 address. */
    IPV6,
    /** Automatically mapped IPv4 address. */
    MAPPED_IPV4,
    /** Optional server-reflexive address. */
    SERVER_REFLEXIVE,
    /** Address supplied directly by the caller. */
    MANUAL,
    /** Reserved wire value; direct racing rejects relay candidates in v1. */
    RELAY
}
