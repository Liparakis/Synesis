package org.synesis.coordination.domain.collaboration;

import org.synesis.coordination.domain.identity.RequestId;

/**
 * Decoded response to one coordination request.
 *
 * @param request  request being answered
 * @param status   resulting request status
 * @param proposal response text retained with the decision
 */
record CoordinationResponsePayload(RequestId request, CoordinationRequest.Status status, String proposal) {

}
