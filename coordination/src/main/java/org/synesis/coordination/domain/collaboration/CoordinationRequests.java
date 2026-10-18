package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Coordination requests and acknowledged inbox items, owned by a {@link CollaborationProjection}. The checks that
 * need participant or intent state stay in the projection and run before {@link #add}.
 */
final class CoordinationRequests {

    private final Map<RequestId, CoordinationRequest> requests = new LinkedHashMap<>();
    private final Set<RequestId> acknowledgedInboxItems = new HashSet<>();
    /** The node whose answer each resolved request holds. */
    private final Map<RequestId, String> answeredBy = new LinkedHashMap<>();

    CoordinationRequests() {
    }

    CoordinationRequests(CoordinationRequests source) {
        requests.putAll(source.requests);
        acknowledgedInboxItems.addAll(source.acknowledgedInboxItems);
        answeredBy.putAll(source.answeredBy);
    }

    List<CoordinationRequest> all() {
        return List.copyOf(requests.values());
    }

    void requireAbsent(CoordinationRequest request) throws IOException {
        if (requests.containsKey(request.identity())) {
            throw new CoordinationException("REQUEST_EXISTS");
        }
    }

    void add(CoordinationRequest request) {
        requests.put(request.identity(), request);
    }

    /**
     * Applies an authorized response to the matching pending coordination request.
     *
     * <p>Off this computer's own append, two answers to one request (its first target and the lane that took it
     * over) both stand, and the answer of the lower node is the request's status, in either order (ADR-0098 step
     * 4, designer item 15).</p>
     *
     * @param response the answer
     * @param answerer node that wrote the answer
     * @param checking whether this computer's own new answer is being judged
     * @throws IOException when the request is unknown or already resolved differently
     */
    void respond(CoordinationResponsePayload response, String answerer, boolean checking) throws IOException {
        CoordinationRequest current = requests.get(response.request());
        if (current == null) {
            throw new MissingReferenceException("REQUEST_NOT_FOUND");
        }
        if (current.status() != CoordinationRequest.Status.PENDING) {
            if (current.status() == response.status()) {
                return;
            }
            if (checking) {
                throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
            }
            String held = answeredBy.get(current.identity());
            if (held != null && held.compareTo(answerer) <= 0) {
                return;
            }
        }
        answeredBy.put(current.identity(), answerer);
        requests.put(current.identity(),
                new CoordinationRequest(current.requestId(),
                        current.projectId(),
                        current.requester(),
                        current.target(),
                        current.conflictingIntentId(),
                        current.kind(),
                        response.proposal().isBlank() ? current.proposal() : response.proposal(),
                        response.status(),
                        current.snapshotId()));
    }

    void acknowledge(RequestId itemId) {
        acknowledgedInboxItems.add(itemId);
    }

    boolean isAcknowledged(RequestId itemId) {
        return acknowledgedInboxItems.contains(itemId);
    }

    /**
     * Moves only review requests whose producer or reviewer identity is being continued.
     *
     * @param source       recovery-held source lane
     * @param target       authorized successor lane
     * @param continuation participant identities for the transition
     */
    void retargetReviews(WorkIntent source, WorkIntent target, LaneContinuation continuation) {
        requests.replaceAll((requestId, request) -> retargetReviewRequest(source, target, continuation, request));
    }

    /**
     * Rebinds one pending review obligation when its producer or reviewer role continues.
     *
     * @return updated request, or the original request when unrelated
     */
    private static CoordinationRequest retargetReviewRequest(WorkIntent source,
            WorkIntent target,
            LaneContinuation continuation,
            CoordinationRequest request) {
        if (request.status() == CoordinationRequest.Status.PENDING
                && request.kind() == CoordinationRequest.Kind.REVIEW && request.targetIdentity()
                .equals(continuation.sourceOwner()) && request.conflictingIntent().equals(source.identity())) {
            return new CoordinationRequest(request.requestId(),
                    request.projectId(),
                    request.requester(),
                    continuation.targetOwner().value(),
                    target.intentId(),
                    request.kind(),
                    request.proposal(),
                    request.status(),
                    request.snapshotId());
        }
        // Not only a reviewer lane asks for reviews: a producer may too (ADR-0089, amended 2026-10-17).
        if ((request.status() == CoordinationRequest.Status.PENDING
                || request.status() == CoordinationRequest.Status.ACCEPTED)
                && request.kind() == CoordinationRequest.Kind.REVIEW && request.requesterIdentity()
                .equals(continuation.sourceOwner())) {
            return new CoordinationRequest(request.requestId(),
                    request.projectId(),
                    continuation.targetOwner().value(),
                    request.target(),
                    request.conflictingIntentId(),
                    request.kind(),
                    request.proposal(),
                    request.status(),
                    request.snapshotId());
        }
        return request;
    }
}
