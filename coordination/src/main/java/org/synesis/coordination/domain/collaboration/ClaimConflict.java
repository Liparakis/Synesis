package org.synesis.coordination.domain.collaboration;

/**
 * Describes one existing claim that conflicts with a requested selector.
 *
 * @param participant conflicting participant
 * @param intentId    conflicting intent identifier
 * @param selector    conflicting selector
 */
public record ClaimConflict(String participant, String intentId, ResourceSelector selector) {

}
